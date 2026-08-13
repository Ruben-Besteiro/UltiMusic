package com.untar.ultimusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.EqBand
import com.untar.ultimusic.ui.EqPreset
import com.untar.ultimusic.ui.MainActivity
import com.untar.ultimusic.ui.PlaybackProgress
import com.untar.ultimusic.ui.boostGainDb
import com.untar.ultimusic.ui.BOOST_LIMIT_PERCENT
import com.untar.ultimusic.ui.BOOST_MAX_PERCENT
import com.untar.ultimusic.ui.BOOST_MIN_PERCENT
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.Headphones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * El motor de reproducción de verdad: dueño del [ExoPlayer], de los seis efectos de audio
 * (amplificador + ecualizador), de la cola en memoria y de su persistencia entre procesos. Antes
 * todo esto vivía en `PlayerViewModel` (ver [com.untar.ultimusic.ui.PlayerViewModel]); se ha
 * trasladado aquí, a un `Service`, para que la reproducción sobreviva a que el usuario cierre la
 * aplicación o apague la pantalla, y para poder mostrar la notificación con controles que pide
 * este fichero (ver la cabecera de [UltiMusicNotificationProvider]).
 *
 * **Por qué un `Service` y no un `ViewModel`.** Un `AndroidViewModel` como el de antes vive
 * mientras vive la `Activity`: en cuanto Android decide recuperar memoria quitando la aplicación
 * de "recientes", su `ViewModelStore` se destruye (`onCleared()`) y con él el `ExoPlayer`, aunque
 * el proceso siguiera vivo un instante más. Un `Service` en primer plano (con notificación) es la
 * pieza que el sistema operativo respeta: mientras haya una notificación de reproducción activa, no
 * mata el proceso, y `android:stopWithTask="false"` (ver `AndroidManifest.xml`) evita que cerrar la
 * aplicación desde recientes se lleve el `Service` por delante si en ese momento está sonando algo.
 *
 * **Por qué `MediaSessionService` y no un `Service` a secas.** [MediaSessionService] es la clase de
 * Media3 pensada exactamente para esto: envuelve el [ExoPlayer] en una [MediaSession] y, a cambio,
 * regala gratis buena parte de lo que pide un reproductor "de verdad" — la notificación con
 * [MediaStyle][androidx.media3.session.MediaStyleNotificationHelper.MediaStyle] (que es la única
 * forma de que la notificación sea también operable desde la pantalla de bloqueo) y responder a los
 * botones de auriculares Bluetooth. Eso sí sale de fábrica por el simple hecho de crear la
 * [MediaSession]; no hay una sola línea de código aquí que lo implemente a mano.
 *
 * Lo que **no** sale de fábrica, aunque uno lo dé por supuesto, es convivir con las demás
 * aplicaciones: ceder el audio cuando entra una llamada u otro reproductor, y pausarse al
 * desenchufar los auriculares. Eso son dos opciones del `ExoPlayer` de dentro y hay que pedirlas
 * explícitamente al construirlo (ver [player]); tenerlo envuelto en una [MediaSession] no las activa.
 *
 * **Por qué sigue habiendo una cola en memoria en vez de dársela entera al `ExoPlayer`.** El diseño
 * de toda la vida de UltiMusic es cargar **una** canción cada vez (`player.setMediaItem`, no
 * `setMediaItems`) y decidir la siguiente con lógica propia — importante para la "cola suelta" que
 * sigue con una canción al azar al agotarse (ver [continueWithRandomSong]) y para que
 * [next]/[skipToNext]/[skipToPrevious] entiendan listas, géneros, álbumes... Como el `ExoPlayer`
 * de dentro por tanto NUNCA tiene más de un elemento en su propia lista, no sabe avanzar/retroceder
 * por sí solo; [QueueAwarePlayer] es el envoltorio que le enseña a la sesión (y por tanto a la
 * notificación y a la pantalla de bloqueo) a llamar a los métodos de aquí en vez de a los suyos.
 *
 * **Cómo habla con la interfaz.** `PlayerViewModel` (con ámbito de `MainActivity`) ya no tiene
 * ningún estado propio: al construirse arranca este `Service` (si no estaba ya arrancado) y expone
 * exactamente los mismos `StateFlow` que antes, pero rellenados a partir de los de aquí (ver
 * [instanceFlow] y `PlayerViewModel.serviceState`). Así ningún fragmento ni adaptador de la
 * aplicación ha tenido que cambiar una sola línea: siguen hablando con `PlayerViewModel` exactamente
 * igual que siempre.
 */
class PlaybackService : MediaSessionService() {

    /**
     * Ámbito de corrutinas del propio Service: vive mientras vive el proceso, se cancela en
     * [onDestroy]. `Dispatchers.Main.immediate` a propósito, igual que el `viewModelScope` que
     * tenía antes esta misma lógica: el `ExoPlayer` (como cualquier `Player` de Media3) solo se
     * puede tocar desde el hilo de su `Looper` de aplicación, que aquí es el principal. Sin esto,
     * las corrutinas caerían en `Dispatchers.Default` (un hilo de fondo cualquiera) y cualquier
     * llamada al reproductor desde dentro de un `launch` reventaría.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var mediaSession: MediaSession

    // Igual que en LibraryRepository/DynamicColor: hace falta un Context para construirlo, y un
    // Service NO tiene un Context válido hasta que el sistema llama a onCreate() (antes de eso, el
    // constructor no lo ha "adjuntado" todavía). `by lazy` difiere la construcción hasta el primer
    // uso de verdad, que siempre pasa después de onCreate().
    private val library by lazy { LibraryRepository.get(this) }

    private val playbackPrefs: SharedPreferences by lazy {
        getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    }

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    private val _isLooseQueue = MutableStateFlow(false)
    val isLooseQueue = _isLooseQueue.asStateFlow()

    private val librarySongsPool = MutableStateFlow<List<Song>>(emptyList())

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress = _progress.asStateFlow()

    private val _accentColor = MutableStateFlow(DynamicColor.DEFAULT)
    val accentColor = _accentColor.asStateFlow()

    /**
     * Se incrementa cada vez que [refreshSong] sustituye una canción por su versión recién
     * guardada, aunque por casualidad quede exactamente igual que la anterior (`Song.equals`) —
     * puede pasar con la carátula, porque el editor de metadatos reutiliza el mismo nombre de
     * archivo al reemplazar una imagen (ver
     * [CoverArt.reserveFileName][com.untar.ultimusic.util.CoverArt.reserveFileName]).
     *
     * Sin esto, en ese caso concreto [_currentSong] y [_queue] no reemitirían de verdad
     * (`MutableStateFlow` conflaría el valor nuevo con el viejo por ser iguales), así que ninguna
     * vista que muestre esa carátula se enteraría del cambio. `updateAccent` no tiene este problema
     * porque es una llamada directa, no algo que dependa de que un StateFlow reemita; por eso el
     * acento sí se refrescaba solo y la imagen no. Quien pinte una carátula sacada de
     * `currentSong`/`queue` debe recargarla también cuando esto cambie, no solo cuando esos flujos
     * cambien.
     */
    private val _coverRevision = MutableStateFlow(0)
    val coverRevision = _coverRevision.asStateFlow()

    private val _currentPlaylistName = MutableStateFlow<String?>(null)
    val currentPlaylistName = _currentPlaylistName.asStateFlow()

    private val _currentCollectionKind = MutableStateFlow<CollectionKind?>(null)
    val currentCollectionKind = _currentCollectionKind.asStateFlow()

    private val _missingFile = MutableSharedFlow<Song>(extraBufferCapacity = 1)
    val missingFile = _missingFile.asSharedFlow()

    private val _currentSongAddedToQueue = MutableSharedFlow<Song>(extraBufferCapacity = 1)
    val currentSongAddedToQueue = _currentSongAddedToQueue.asSharedFlow()

    private val _songsAddedToQueue = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val songsAddedToQueue = _songsAddedToQueue.asSharedFlow()

    /** Avisa cuando se intenta añadir a la cola sin que suene nada (p. ej. justo tras abrir la app
     *  en frío, antes de tocar una canción): no hay "cola" a la que añadir. */
    private val _cannotAddToEmptyQueue = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cannotAddToEmptyQueue = _cannotAddToEmptyQueue.asSharedFlow()

    private var accentJob: Job? = null
    private var loadJob: Job? = null

    /**
     * La restauración de la cola guardada, mientras está en marcha (ver [restorePlaybackState]).
     * Resolver los ids contra la base de datos es asíncrono, así que un Service recién creado —el
     * que revive `MainActivity.onResume` al volver de segundo plano— pasa un rato con [_queue]
     * vacía y [_isLooseQueue] en false aunque haya estado guardado. Las órdenes que dependen de la
     * cola esperan a que esto termine en vez de caer en saco roto (ver [withQueue]).
     */
    private var restoreJob: Job? = null

    /** La espera a que cargue la biblioteca para seguir con una canción al azar (ver
     *  [continueWithRandomSong]). Se guarda para poder cancelarla si llega otra orden antes. */
    private var randomContinuationJob: Job? = null

    /**
     * El reproductor. Las dos opciones del constructor son las que convierten un `ExoPlayer` "pelado"
     * en uno que se porta bien con el resto del móvil; **ninguna de las dos viene puesta de fábrica**,
     * hay que pedirlas a mano aunque haya una [MediaSession] por medio:
     *
     * - **[ExoPlayer.Builder.setAudioAttributes] con `handleAudioFocus = true`.** Es lo que hace que
     *   el reproductor pida el foco de audio al empezar y lo suelte al parar, y —lo que de verdad se
     *   nota— que reaccione cuando otro se lo quita: baja el volumen solo mientras suena un aviso del
     *   navegador ("ducking"), se pausa al entrar una llamada o al ponerse a sonar otra aplicación de
     *   música, y vuelve al terminar. Sin esto, UltiMusic seguía sonando por encima de todo lo demás.
     *   Las `AudioAttributes` que se le pasan (`USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`) son, además, lo
     *   que le dice al sistema que esto es música: sirven para que el volumen que se ajuste sea el de
     *   multimedia y para que el móvil lo enrute donde toca.
     * - **[ExoPlayer.Builder.setHandleAudioBecomingNoisy].** Pausa al desenchufar los auriculares o al
     *   desconectarse los Bluetooth, en vez de ponerse a sonar de golpe por el altavoz del móvil.
     *
     * (No confundir con el [AudioDeviceCallback] de más abajo, que mira los aparatos conectados por
     * otro motivo completamente distinto: el tope del amplificador de volumen, ver
     * [enforceHeadphoneLimit].)
     */
    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // Al terminar una canción, avanzamos solos a la siguiente. (Cualificado: sin
                        // el this@, next() se resolvería al next() deprecado del propio ExoPlayer.)
                        if (state == Player.STATE_ENDED) this@PlaybackService.next()
                    }
                })
            }
    }

    // ===================================================================================
    // Amplificador de volumen (ver la cabecera larga de esto en el PlayerViewModel de antes, se
    // mantiene sin cambios de fondo: solo se ha movido de sitio).
    // ===================================================================================

    private val audioPrefs: SharedPreferences by lazy {
        getSharedPreferences("audio_settings", Context.MODE_PRIVATE)
    }

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val _volumeBoost = MutableStateFlow(BOOST_MIN_PERCENT)
    val volumeBoost = _volumeBoost.asStateFlow()

    private val _limitDisabled = MutableStateFlow(false)
    val limitDisabled = _limitDisabled.asStateFlow()

    private val _limitForcedByHeadphones = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val limitForcedByHeadphones = _limitForcedByHeadphones.asSharedFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            enforceHeadphoneLimit()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {}
    }

    // ===================================================================================
    // Ecualizador (ver la cabecera larga de esto en el PlayerViewModel de antes).
    // ===================================================================================

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    var eqBands: List<EqBand> = emptyList()
        private set
    var bassBoostSupported: Boolean = false
        private set
    var virtualizerSupported: Boolean = false
        private set
    var reverbSupported: Boolean = false
        private set
    var dynamicsProcessingSupported: Boolean = false
        private set

    val reverbPresetNames = listOf(
        "Ninguno", "Habitación pequeña", "Habitación mediana", "Sala grande",
        "Salón mediano", "Salón grande", "Plancha"
    )

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled = _eqEnabled.asStateFlow()

    private val _eqBandLevels = MutableStateFlow<List<Int>>(emptyList())
    val eqBandLevels = _eqBandLevels.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength = _virtualizerStrength.asStateFlow()

    private val _reverbPreset = MutableStateFlow(0)
    val reverbPreset = _reverbPreset.asStateFlow()

    private val _dynamicsStrength = MutableStateFlow(0)
    val dynamicsStrength = _dynamicsStrength.asStateFlow()

    private val _eqPreGain = MutableStateFlow(50)
    val eqPreGain = _eqPreGain.asStateFlow()

    private val _currentPresetName = MutableStateFlow(FLAT_PRESET_NAME)
    val currentPresetName = _currentPresetName.asStateFlow()

    private val _userPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val userPresets = _userPresets.asStateFlow()

    private val predefinedPresets = mutableListOf<EqPreset>()
    val predefinedPresetNames: List<String> get() = predefinedPresets.map { it.name }
    val userModifiedPresetName: String get() = USER_MODIFIED_PRESET_NAME

    // ===================================================================================
    // Ciclo de vida del Service.
    // ===================================================================================

    override fun onCreate() {
        super.onCreate()

        probeEqCapabilities()
        createPredefinedPresets()
        restoreUserPresets()
        restoreAudioSettings()
        applyBoost()
        applyEq()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        enforceHeadphoneLimit()

        serviceScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    _progress.value = PlaybackProgress(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.takeIf { it > 0 } ?: 0L
                    )
                }
                delay(500)
            }
        }
        serviceScope.launch {
            library.songs.collect { librarySongsPool.value = it }
        }
        restorePlaybackState()

        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player))
            .setSessionActivity(openAppPendingIntent())
            .setBitmapLoader(CoverBitmapLoader(this))
            .build()
        addSession(mediaSession)
        setMediaNotificationProvider(UltiMusicNotificationProvider(this))

        // ÚLTIMA línea a propósito: publicarse en [_instance] es lo que destraba las órdenes que
        // `PlayerViewModel` hubiera dejado pendientes (ver su `withService`), y esas órdenes pueden
        // llamar a cualquier cosa de aquí. Si esto estuviera al principio de onCreate —como estaba—,
        // una orden pendiente podría entrar con el `mediaSession` todavía sin construir.
        _instance.value = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    // No hace falta sobreescribir onTaskRemoved (cerrar la aplicación desde "recientes"):
    // MediaSessionService ya trae por defecto exactamente el comportamiento que hace falta —
    // sigue en marcha si algo está sonando (gracias también a `stopWithTask="false"` en el
    // manifiesto) y se para solo, limpiamente, si no lo está.

    override fun onDestroy() {
        savePlaybackState()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        loudnessEnhancer?.release(); loudnessEnhancer = null
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        virtualizer?.release(); virtualizer = null
        presetReverb?.release(); presetReverb = null
        dynamicsProcessing?.release(); dynamicsProcessing = null
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        _instance.value = null
        super.onDestroy()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    /** Busca una canción por id entre lo que hay en memoria (la cola, o la que suena). Lo usa
     *  [CoverBitmapLoader] para resolver la carátula que pide la notificación. */
    fun findSong(id: Long): Song? =
        _queue.value.firstOrNull { it.id == id } ?: _currentSong.value?.takeIf { it.id == id }

    /** URI inventado (no apunta a nada real) que identifica la carátula de [song] ante Media3;
     *  [CoverBitmapLoader] lo deshace para volver a sacar el id y resolver la carátula de verdad. */
    private fun coverUriFor(song: Song): Uri = Uri.parse("ultimusic://cover/${song.id}")

    /**
     * Envuelve [player] para que la sesión (y por tanto la notificación y la pantalla de bloqueo)
     * use el "siguiente"/"anterior" de UltiMusic en vez del de ExoPlayer: como aquí solo se carga
     * UNA canción cada vez (ver la cabecera de la clase), el ExoPlayer de dentro nunca tiene una
     * lista real que recorrer. [ForwardingPlayer] es la clase de Media3 pensada para reenviar
     * todo salvo lo que se pise aquí.
     */
    private inner class QueueAwarePlayer(player: Player) : ForwardingPlayer(player) {

        override fun hasNextMediaItem(): Boolean =
            _currentIndex.value < _queue.value.lastIndex || _isLooseQueue.value

        override fun hasPreviousMediaItem(): Boolean = _currentIndex.value > 0

        override fun seekToNext() = this@PlaybackService.skipToNext()
        override fun seekToNextMediaItem() = this@PlaybackService.skipToNext()
        override fun seekToPrevious() = this@PlaybackService.skipToPrevious()
        override fun seekToPreviousMediaItem() = this@PlaybackService.skipToPrevious()

        // ForwardingPlayer.isCommandAvailable() NO se calcula a partir de getAvailableCommands():
        // reenvía sin más al ExoPlayer de dentro (ver su javadoc, que pide sobreescribir los dos
        // juntos). Sin este override, algo tan directo como "¿hay siguiente?" seguiría mirando la
        // lista de reproducción real del ExoPlayer (que nunca tiene más de un elemento) y diría que
        // no, aunque getAvailableCommands() ya dijera que sí.
        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNextMediaItem()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPreviousMediaItem()
            else -> super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands =
            Player.Commands.Builder()
                .addAll(super.getAvailableCommands())
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem())
                .build()
    }

    // ===================================================================================
    // Persistencia del estado de reproducción entre procesos (idéntico a como estaba antes).
    // ===================================================================================

    fun savePlaybackState() {
        val queue = _queue.value
        if (queue.isEmpty()) {
            playbackPrefs.edit().clear().apply()
            return
        }
        val currentSongId = queue.getOrNull(_currentIndex.value)?.id ?: return
        playbackPrefs.edit()
            .putString(KEY_QUEUE, queue.joinToString(",") { it.id.toString() })
            .putLong(KEY_CURRENT_SONG_ID, currentSongId)
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .putString(KEY_PLAYLIST_NAME, _currentPlaylistName.value)
            .putString(KEY_COLLECTION_KIND, _currentCollectionKind.value?.name)
            .putBoolean(KEY_RANDOM_CONTINUATION, _isLooseQueue.value)
            .apply()
    }

    private fun restorePlaybackState() {
        val queueIds = playbackPrefs.getString(KEY_QUEUE, null)
            ?.split(",")?.filter { it.isNotEmpty() }?.map { it.toLong() }
        if (queueIds.isNullOrEmpty()) return
        val currentSongId = playbackPrefs.getLong(KEY_CURRENT_SONG_ID, -1L)
        val positionMs = playbackPrefs.getLong(KEY_POSITION_MS, 0L)
        val playlistName = playbackPrefs.getString(KEY_PLAYLIST_NAME, null)
        val collectionKindName = playbackPrefs.getString(KEY_COLLECTION_KIND, null)
        val wasRandomContinuation = playbackPrefs.getBoolean(KEY_RANDOM_CONTINUATION, false)

        restoreJob = serviceScope.launch {
            val restoredQueue = library.songsByIds(queueIds)
            if (restoredQueue.isEmpty()) return@launch

            // Resolver la cola guardada contra la base de datos es una llamada suspend: tarda lo
            // suyo, y en ese hueco el usuario ya puede haber tocado una canción (una orden que
            // `PlayerViewModel` tenía pendiente y que se ejecuta en cuanto este Service se publica).
            // Si eso ha pasado, la cola de verdad es la suya y NO se pisa: sin esto, el toque se
            // quedaba a medias — la canción elegida saltaba de vuelta a la cola vieja y, encima, en
            // pausa, porque aquí abajo se recarga con shouldPlay = false.
            if (_queue.value.isNotEmpty()) return@launch

            _queue.value = restoredQueue
            _isLooseQueue.value = wasRandomContinuation
            _currentIndex.value = restoredQueue.indexOfFirst { it.id == currentSongId }
                .let { if (it >= 0) it else 0 }
            _currentPlaylistName.value = playlistName
            _currentCollectionKind.value = collectionKindName?.let {
                runCatching { CollectionKind.valueOf(it) }.getOrNull()
            }
            loadCurrent(shouldPlay = false, seekToMs = positionMs)
        }
    }

    // ===================================================================================
    // Cola SUELTA (canciones sueltas, álbum entero desde su ficha) — ver cabecera original.
    // ===================================================================================

    fun play(song: Song) {
        _queue.value = listOf(song)
        _currentIndex.value = 0
        _isLooseQueue.value = true
        _currentPlaylistName.value = null
        _currentCollectionKind.value = null
        playCurrent()
    }

    /**
     * Reproduce una canción al azar de toda la biblioteca (nunca una de la lista gris, ver
     * [librarySongsPool]), como si el usuario la hubiera tocado directamente en Canciones: cola
     * suelta de una sola canción. La usan el botón de play del mini-reproductor y el del iPod
     * cuando no suena nada todavía (ver [MiniPlayerController]/[IPodDialogFragment]), que en ese
     * estado muestran el icono de aleatorio en vez del de play.
     */
    fun playRandomFromLibrary() {
        val pool = librarySongsPool.value
        if (pool.isNotEmpty()) {
            play(pool.random())
            return
        }
        // Mismo motivo que en [continueWithRandomSong]: el pozo puede no haber cargado todavía, y
        // un botón de play que no hace nada es peor que uno que tarda un instante.
        randomContinuationJob?.cancel()
        randomContinuationJob = serviceScope.launch {
            play(librarySongsPool.first { it.isNotEmpty() }.random())
        }
    }

    fun addToQueue(song: Song) {
        val q = _queue.value
        if (q.isEmpty()) {
            serviceScope.launch { _cannotAddToEmptyQueue.emit(Unit) }
            return
        }
        val currentId = q.getOrNull(_currentIndex.value)?.id
        if (song.id == currentId) {
            serviceScope.launch { _currentSongAddedToQueue.emit(song) }
            return
        }
        val idx = q.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            val withoutSong = q.filterIndexed { i, _ -> i != idx }
            _currentIndex.value = withoutSong.indexOfFirst { it.id == currentId }
                .let { if (it >= 0) it else _currentIndex.value }
            _queue.value = withoutSong + song
        } else {
            _queue.value = q + song
        }
        serviceScope.launch { _songsAddedToQueue.emit(1) }
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val q = _queue.value
        if (q.isEmpty()) {
            serviceScope.launch { _cannotAddToEmptyQueue.emit(Unit) }
            return
        }
        val ids = songs.map { it.id }.toHashSet()
        val currentId = q.getOrNull(_currentIndex.value)?.id

        val currentSongs = songs.filter { it.id == currentId }
        if (currentSongs.isNotEmpty()) {
            serviceScope.launch {
                currentSongs.forEach { _currentSongAddedToQueue.emit(it) }
            }
        }

        val toAppend = songs.filter { it.id != currentId }
        if (toAppend.isEmpty()) return

        val withoutThem = q.filterNot { it.id in ids }
        _currentIndex.value = withoutThem.indexOfFirst { it.id == currentId }
            .let { if (it >= 0) it else _currentIndex.value }
        _queue.value = withoutThem + toAppend
        serviceScope.launch { _songsAddedToQueue.emit(toAppend.size) }
    }

    /**
     * Aplica el nuevo orden de [newOrder] a la cola tal cual está sonando (arrastrar y soltar una
     * fila en [com.untar.ultimusic.ui.player.IPodQueueAdapter], modo normal del iPod). Mismas
     * canciones que ya había, solo reordenadas: no toca al reproductor en sí (`player` sigue cargado
     * con la misma canción, solo cambia su posición dentro de la lista), pero si el arrastre movió
     * justo la fila que suena, [_currentIndex] tiene que seguirla a su nueva posición para no
     * desincronizarse (mismo criterio que [addToQueue] al quitar de en medio una canción repetida).
     */
    fun reorderQueue(newOrder: List<Song>) {
        val currentId = _queue.value.getOrNull(_currentIndex.value)?.id
        _queue.value = newOrder
        val newIndex = newOrder.indexOfFirst { it.id == currentId }
        if (newIndex >= 0) _currentIndex.value = newIndex
    }

    // ---------------------------------------------------------------------------------------------
    // COLECCIONES: fichas de álbum/artista/productor, modo navegación del iPod para listas y
    // géneros — ver cabecera original.
    // ---------------------------------------------------------------------------------------------

    fun playCollection(
        collection: List<Song>,
        startIndex: Int,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) {
        if (collection.isEmpty() || startIndex !in collection.indices) return
        _queue.value = collection
        _currentIndex.value = startIndex
        _isLooseQueue.value = false
        _currentPlaylistName.value = playlistName
        _currentCollectionKind.value = collectionKind
        playCurrent()
    }

    fun shuffleCollection(
        collection: List<Song>,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) {
        if (collection.isEmpty()) return
        playCollection(collection.shuffled(), 0, playlistName, collectionKind)
    }

    fun next() {
        if (_currentIndex.value < _queue.value.lastIndex) {
            _currentIndex.value += 1
            loadCurrent(shouldPlay = player.playWhenReady)
        } else if (_isLooseQueue.value) {
            continueWithRandomSong(shouldPlay = player.playWhenReady)
        }
    }

    /**
     * Alarga la cola suelta con una canción al azar de la biblioteca y la reproduce.
     *
     * [librarySongsPool] lo rellena un `Flow` de Room que se empieza a recoger en [onCreate], así
     * que en un Service **recién creado** —el que revive `MainActivity.onResume` al volver de
     * segundo plano— puede estar todavía vacío. Antes eso era un `return` a secas: la cola se
     * acababa, el "siguiente" no hacía nada y no había forma de enterarse. Ahora se espera a la
     * primera lista de verdad y se sigue donde se había quedado.
     */
    private fun continueWithRandomSong(shouldPlay: Boolean) {
        val pool = librarySongsPool.value
        if (pool.isNotEmpty()) {
            appendRandomSong(pool, shouldPlay)
            return
        }
        randomContinuationJob?.cancel()
        randomContinuationJob = serviceScope.launch {
            appendRandomSong(librarySongsPool.first { it.isNotEmpty() }, shouldPlay)
        }
    }

    private fun appendRandomSong(pool: List<Song>, shouldPlay: Boolean) {
        val previousId = _currentSong.value?.id
        val candidates = pool.filter { it.id != previousId }.ifEmpty { pool }
        _queue.value = _queue.value + candidates.random()
        _currentIndex.value = _queue.value.lastIndex
        loadCurrent(shouldPlay = shouldPlay)
    }

    /**
     * Ejecuta [command] cuando la cola ya esté puesta. Con el Service recién creado, [_instance] se
     * publica al final de [onCreate] y eso destraba al instante la orden que `PlayerViewModel`
     * tuviera pendiente (ver su `withService`), pero [restorePlaybackState] todavía está resolviendo
     * los ids contra la base de datos: [_queue] está vacía y [_isLooseQueue] en false. Una orden de
     * "siguiente"/"anterior"/"play" que entrara en ese instante se evaluaba contra esa cola vacía y
     * no hacía nada, en silencio y para siempre. Aquí se espera a que la restauración aterrice.
     *
     * Las órdenes que TRAEN su propia cola ([play], [playCollection]…) no pasan por aquí a propósito:
     * mandan ellas, y la restauración ya se aparta sola si se le adelantan.
     */
    private fun withQueue(command: () -> Unit) {
        val job = restoreJob
        if (job == null || !job.isActive || _queue.value.isNotEmpty()) {
            command()
            return
        }
        serviceScope.launch {
            job.join()
            command()
        }
    }

    fun skipToPrevious() = withQueue { jumpTo(_currentIndex.value - 1, forcePlay = false) }

    fun skipToNext() = withQueue {
        val idx = _currentIndex.value
        if (idx >= _queue.value.lastIndex) {
            if (_isLooseQueue.value) continueWithRandomSong(shouldPlay = player.playWhenReady)
            return@withQueue
        }
        jumpTo(idx + 1, forcePlay = false)
    }

    fun jumpTo(d: Int, forcePlay: Boolean = true) {
        if (d !in _queue.value.indices) return
        val oldIndex = _currentIndex.value
        _currentIndex.value = d

        if (d != oldIndex) {
            loadCurrent(shouldPlay = if (forcePlay) true else player.playWhenReady)
        }
    }

    private fun playCurrent() {
        loadCurrent(shouldPlay = true)
    }

    /**
     * Carga en el reproductor la canción que toca según [_currentIndex].
     *
     * **Por qué el camino normal es SÍNCRONO.** Aquí se llega, entre otros sitios, desde
     * `onPlaybackStateChanged(STATE_ENDED)`: al acabarse una canción hay que encadenar la siguiente.
     * Antes todo esto vivía dentro de un `serviceScope.launch`, así que entre que terminaba una
     * canción y empezaba la otra había un ida y vuelta a [Dispatchers.IO] (lo pide
     * [LibraryRepository.resolvePlayablePath]) durante el cual el `ExoPlayer` se quedaba parado en
     * `STATE_ENDED`. En ese hueco Media3 hace lo que debe: rehace la notificación viéndola ya como
     * "nada sonando" (ver `showPauseButton` en [UltiMusicNotificationProvider]) y saca el Service del
     * primer plano. Si el sistema aprovechaba para pararlo, [onDestroy] cancelaba [serviceScope] y
     * con él la corrutina que iba a cargar la canción siguiente: la reproducción se quedaba muerta
     * **sin error ni aviso ninguno**, que es exactamente el síntoma de "se acaba la última canción de
     * la cola y no ocurre absolutamente nada".
     *
     * Que el Service se muera ahí ya no debería pasar —`PlayerViewModel` lo mantiene *enlazado*, no
     * solo arrancado, ver su cabecera—, pero el camino síncrono se queda igualmente: no depender de
     * que una corrutina sobreviva para encadenar la canción siguiente es lo correcto por sí solo, y
     * de paso el reproductor deja de pasar por `STATE_ENDED` entre canción y canción.
     *
     * Con el camino rápido aquí abajo, la canción siguiente entra en el reproductor dentro de la
     * misma llamada que avisó del final: el reproductor no llega a quedarse quieto en `STATE_ENDED`,
     * la notificación no parpadea a "parado" y no hay corrutina que se pueda quedar a medias.
     * Comprobar que el archivo sigue donde dice la base de datos es un `stat()` sobre almacenamiento
     * local (microsegundos), nada que ver con leer etiquetas o recorrer carpetas; solo cuando ESO
     * falla —el usuario ha movido el archivo— se pasa al camino lento de siempre, que sí necesita
     * segundo plano y donde el retraso ya no importa porque la reproducción se ha cortado igualmente.
     */
    private fun loadCurrent(shouldPlay: Boolean = true, seekToMs: Long = 0L) {
        val index = _currentIndex.value
        val song = _queue.value.getOrNull(index) ?: return
        _currentSong.value = song
        updateAccent(song)

        loadJob?.cancel()

        if (File(song.filePath).exists()) {
            startPlayback(song, song.filePath, shouldPlay, seekToMs)
            return
        }

        // Camino lento: la ruta guardada se ha quedado obsoleta y hay que buscar el archivo por
        // nombre por toda la fonoteca (ver [LibraryRepository.resolvePlayablePath]).
        loadJob = serviceScope.launch {
            val path = library.resolvePlayablePath(song)
            if (path == null) {
                player.stop()
                _missingFile.emit(song)
                return@launch
            }
            rewritePath(index, song, path)
            startPlayback(song, path, shouldPlay, seekToMs)
        }
    }

    private fun startPlayback(song: Song, path: String, shouldPlay: Boolean, seekToMs: Long) {
        player.setMediaItem(buildMediaItem(song, path))
        if (seekToMs > 0L) player.seekTo(seekToMs)
        player.prepare()
        if (shouldPlay) player.play() else player.pause()
    }

    /**
     * Empaqueta la canción en un [MediaItem] con su [MediaMetadata] (título, artista, álbum y el
     * URI inventado de su carátula, ver [coverUriFor]): sin esto, la notificación no tendría qué
     * enseñar más que un hueco en blanco. Antes bastaba con `MediaItem.fromUri(...)` porque nadie
     * miraba esta metadata; ahora la mira la notificación (y, con ella, la pantalla de bloqueo).
     */
    private fun buildMediaItem(song: Song, path: String): MediaItem {
        val artistName = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
        val albumName = song.albums.firstOrNull()?.title
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(artistName)
            .setAlbumTitle(albumName)
            .setArtworkUri(coverUriFor(song))
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(Uri.fromFile(File(path)))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun rewritePath(index: Int, song: Song, path: String) {
        val fixed = song.copy(filePath = path)
        val queue = _queue.value
        if (queue.getOrNull(index)?.id == song.id) {
            _queue.value = queue.toMutableList().also { it[index] = fixed }
        }
        if (_currentSong.value?.id == song.id) _currentSong.value = fixed
    }

    /**
     * Sustituye la canción [fresh] (por su id) en la cola y, si es la que suena, en [_currentSong],
     * por esta versión recién guardada — TODOS sus campos, no solo la carátula. Se llama tras
     * cualquier guardado del editor de metadatos: sin esto, la letra, el título o cualquier otro
     * campo editado de la canción que suena de verdad se quedaban con el valor viejo hasta que
     * arrancaba otra canción (que sí recarga [Song] fresca desde el repositorio, ver [play]), por
     * mucho que Room ya tuviera el cambio guardado.
     */
    fun refreshSong(fresh: Song) {
        fun patch(song: Song) = if (song.id == fresh.id) fresh else song
        _queue.value = _queue.value.map(::patch)
        _coverRevision.value++
        if (_currentSong.value?.id == fresh.id) {
            _currentSong.value = fresh
            updateAccent(fresh)
        }
    }

    private fun updateAccent(song: Song) {
        accentJob?.cancel()
        accentJob = serviceScope.launch {
            _accentColor.value = DynamicColor.fromCover(this@PlaybackService, CoverArt.cover(this@PlaybackService, song))
        }
    }

    fun seekToFraction(fraction: Float) {
        val duration = player.duration
        if (duration > 0) {
            player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    fun togglePlayPause() = withQueue {
        if (_currentSong.value == null) return@withQueue
        if (player.isPlaying) player.pause() else player.play()
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    // --- Amplificador de volumen ---

    fun setVolumeBoost(percent: Int) {
        val max = if (_limitDisabled.value) BOOST_MAX_PERCENT else BOOST_LIMIT_PERCENT
        val clamped = percent.coerceIn(BOOST_MIN_PERCENT, max)
        if (clamped == _volumeBoost.value) return
        _volumeBoost.value = clamped
        applyBoost()
        saveAudioSettings()
    }

    fun setLimitDisabled(disabled: Boolean): Headphones.Check {
        if (disabled) {
            val check = Headphones.check(this)
            if (check != Headphones.Check.NONE) return check
        }
        _limitDisabled.value = disabled
        if (!disabled && _volumeBoost.value > BOOST_LIMIT_PERCENT) {
            _volumeBoost.value = BOOST_LIMIT_PERCENT
            applyBoost()
        }
        saveAudioSettings()
        return Headphones.Check.NONE
    }

    private fun enforceHeadphoneLimit() {
        if (!_limitDisabled.value) return
        if (Headphones.check(this) == Headphones.Check.NONE) return
        setLimitDisabled(false)
        _limitForcedByHeadphones.tryEmit(Unit)
    }

    private fun applyBoost() {
        val percent = _volumeBoost.value
        if (percent <= BOOST_MIN_PERCENT) {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            return
        }
        val enhancer = loudnessEnhancer ?: try {
            LoudnessEnhancer(player.audioSessionId).also { loudnessEnhancer = it }
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val gainMb = (boostGainDb(percent) * 100.0).roundToInt()
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = true
        } catch (e: Exception) {
            // Si el efecto se ha muerto (sin recursos de audio), se sigue oyendo sin amplificar.
        }
    }

    // --- Ecualizador ---

    fun setEqEnabled(enabled: Boolean) {
        if (enabled == _eqEnabled.value) return
        _eqEnabled.value = enabled
        applyEq()
        saveAudioSettings()
    }

    fun setEqBandLevel(band: Int, levelMb: Int) {
        val info = eqBands.getOrNull(band) ?: return
        val clamped = levelMb.coerceIn(info.minLevelMb, info.maxLevelMb)
        val levels = _eqBandLevels.value
        if (band !in levels.indices || levels[band] == clamped) return
        _eqBandLevels.value = levels.toMutableList().apply { this[band] = clamped }
        markEqAsModified()
        try {
            equalizer?.setBandLevel(band.toShort(), effectiveBandLevel(band, clamped).toShort())
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
        saveAudioSettings()
    }

    fun setBassBoostStrength(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == _bassBoostStrength.value) return
        _bassBoostStrength.value = clamped
        markEqAsModified()
        try {
            bassBoost?.setStrength((clamped * 10).toShort())
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
        saveAudioSettings()
    }

    fun setVirtualizerStrength(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == _virtualizerStrength.value) return
        _virtualizerStrength.value = clamped
        markEqAsModified()
        try {
            virtualizer?.setStrength((clamped * 10).toShort())
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
        saveAudioSettings()
    }

    fun setReverbPreset(preset: Int) {
        val clamped = preset.coerceIn(0, reverbPresetNames.lastIndex)
        if (clamped == _reverbPreset.value) return
        _reverbPreset.value = clamped
        markEqAsModified()
        try {
            presetReverb?.preset = clamped.toShort()
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
        saveAudioSettings()
    }

    fun setDynamicsStrength(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == _dynamicsStrength.value) return
        _dynamicsStrength.value = clamped
        markEqAsModified()
        try {
            dynamicsProcessing?.setMbcBandAllChannelsTo(0, buildCompressorBand(clamped))
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
        saveAudioSettings()
    }

    fun setEqPreGain(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == _eqPreGain.value) return
        _eqPreGain.value = clamped
        markEqAsModified()
        applyEq()
        saveAudioSettings()
    }

    fun resetEqualizer() {
        _eqBandLevels.value = eqBands.map { 0 }
        _bassBoostStrength.value = 0
        _virtualizerStrength.value = 0
        _reverbPreset.value = 0
        _dynamicsStrength.value = 0
        _eqPreGain.value = 50
        _currentPresetName.value = FLAT_PRESET_NAME
        applyEq()
        saveAudioSettings()
    }

    private fun preGainMb(): Int = (_eqPreGain.value - 50) * 20

    private fun effectiveBandLevel(index: Int, storedLevel: Int): Int {
        val band = eqBands.getOrNull(index) ?: return storedLevel
        return (storedLevel + preGainMb()).coerceIn(band.minLevelMb, band.maxLevelMb)
    }

    private fun applyEq() {
        if (!_eqEnabled.value) {
            releaseEqEffects()
            return
        }
        ensureEqEffects()
        try {
            equalizer?.let { eq ->
                _eqBandLevels.value.forEachIndexed { index, level ->
                    eq.setBandLevel(index.toShort(), effectiveBandLevel(index, level).toShort())
                }
                eq.enabled = _eqEnabled.value
            }
            bassBoost?.let { bb ->
                bb.setStrength((_bassBoostStrength.value * 10).toShort())
                bb.enabled = _eqEnabled.value
            }
            virtualizer?.let { vt ->
                vt.setStrength((_virtualizerStrength.value * 10).toShort())
                vt.enabled = _eqEnabled.value
            }
            presetReverb?.let { pr ->
                pr.preset = _reverbPreset.value.toShort()
                pr.enabled = _eqEnabled.value
            }
            dynamicsProcessing?.let { dp ->
                dp.setMbcBandAllChannelsTo(0, buildCompressorBand(_dynamicsStrength.value))
                dp.enabled = _eqEnabled.value
            }
        } catch (e: Exception) {
            // Igual que en applyBoost.
        }
    }

    private fun buildCompressorBand(strengthPercent: Int): DynamicsProcessing.MbcBand {
        val t = strengthPercent / 100f
        return DynamicsProcessing.MbcBand(
            /* enabled = */ true,
            /* cutoffFrequency = */ 0f,
            /* attackTime = */ 10f,
            /* releaseTime = */ 150f,
            /* ratio = */ 1f + 3f * t,
            /* threshold = */ -10f - 20f * t,
            /* kneeWidth = */ 5f,
            /* noiseGateThreshold = */ -90f,
            /* expanderRatio = */ 1f,
            /* preGain = */ 0f,
            /* postGain = */ 6f * t
        )
    }

    private fun buildDynamicsProcessingConfig(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ true, /* mbcBandCount = */ 1,
            /* postEqInUse = */ false, /* postEqBandCount = */ 0,
            /* limiterInUse = */ true
        ).build()

    private fun probeEqCapabilities() {
        try {
            val probeEq = try {
                Equalizer(0, player.audioSessionId)
            } catch (e: Exception) {
                null
            }
            eqBands = probeEq?.let { eq ->
                val range = eq.bandLevelRange
                (0 until eq.numberOfBands.toInt()).map { band ->
                    EqBand(
                        centerFreqHz = eq.getCenterFreq(band.toShort()) / 1000,
                        minLevelMb = range[0].toInt(),
                        maxLevelMb = range[1].toInt()
                    )
                }
            } ?: emptyList()
            probeEq?.release()

            val probeBass = try {
                BassBoost(0, player.audioSessionId)
            } catch (e: Exception) {
                null
            }
            bassBoostSupported = probeBass?.strengthSupported == true
            probeBass?.release()

            val probeVirt = try {
                Virtualizer(0, player.audioSessionId)
            } catch (e: Exception) {
                null
            }
            virtualizerSupported = probeVirt?.strengthSupported == true
            probeVirt?.release()

            val probeReverb = try {
                PresetReverb(0, player.audioSessionId)
            } catch (e: Exception) {
                null
            }
            reverbSupported = probeReverb != null
            probeReverb?.release()

            val probeDynamics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    DynamicsProcessing(0, player.audioSessionId, buildDynamicsProcessingConfig())
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            dynamicsProcessingSupported = probeDynamics != null
            probeDynamics?.release()
        } catch (e: Exception) {
            eqBands = emptyList()
            bassBoostSupported = false
            virtualizerSupported = false
            reverbSupported = false
            dynamicsProcessingSupported = false
        }
    }

    private fun ensureEqEffects() {
        if (equalizer == null) {
            equalizer = try { Equalizer(0, player.audioSessionId) } catch (e: Exception) { null }
        }
        if (bassBoost == null) {
            bassBoost = try { BassBoost(0, player.audioSessionId) } catch (e: Exception) { null }
        }
        if (virtualizer == null) {
            virtualizer = try { Virtualizer(0, player.audioSessionId) } catch (e: Exception) { null }
        }
        if (presetReverb == null) {
            presetReverb = try { PresetReverb(0, player.audioSessionId) } catch (e: Exception) { null }
        }
        if (dynamicsProcessing == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = try {
                DynamicsProcessing(0, player.audioSessionId, buildDynamicsProcessingConfig())
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun releaseEqEffects() {
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        virtualizer?.release(); virtualizer = null
        presetReverb?.release(); presetReverb = null
        dynamicsProcessing?.release(); dynamicsProcessing = null
    }

    private fun createPredefinedPresets() {
        if (eqBands.isEmpty()) return
        val numBands = eqBands.size
        val zero = List(numBands) { 0 }

        predefinedPresets.clear()
        predefinedPresets.add(EqPreset(FLAT_PRESET_NAME, zero, 0, 0))
        predefinedPresets.add(EqPreset("Rock",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 80)
                if (numBands >= 3) set(numBands / 3, -50)
                if (numBands >= 1) set(numBands - 1, 100)
            }.toList(), 30, 0))
        predefinedPresets.add(EqPreset("Pop",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 60)
                if (numBands >= 1) set(numBands - 1, 70)
            }.toList(), 20, 10))
        predefinedPresets.add(EqPreset("Jazz",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 100)
                if (numBands >= 1) set(numBands - 1, -80)
            }.toList(), 40, 0))
        predefinedPresets.add(EqPreset("Clásica",
            zero.toMutableList().apply {
                if (numBands >= 3) set(numBands / 2, 50)
            }.toList(), 0, 0))
        predefinedPresets.add(EqPreset("Heavy Metal",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 150)
                if (numBands >= 1) set(numBands - 1, 150)
            }.toList(), 50, 20))
        predefinedPresets.add(EqPreset("Hip-Hop",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 120)
                if (numBands >= 3) set(numBands / 2, -60)
            }.toList(), 60, 0))
        predefinedPresets.add(EqPreset("Dance",
            zero.toMutableList().apply {
                if (numBands >= 2) set(0, 100)
                if (numBands >= 3) set(numBands / 3, -70)
                if (numBands >= 1) set(numBands - 1, 80)
            }.toList(), 50, 30))
    }

    private fun restoreUserPresets() {
        try {
            val presetCount = audioPrefs.getInt(KEY_USER_PRESET_COUNT, 0)
            val presets = mutableListOf<EqPreset>()
            for (i in 0 until presetCount) {
                val name = audioPrefs.getString("$KEY_USER_PRESET_NAME_PREFIX$i", null) ?: continue
                val bandLevelStr = audioPrefs.getString("$KEY_USER_PRESET_LEVELS_PREFIX$i", null) ?: continue
                val bandLevels = bandLevelStr.split(",").mapNotNull { it.toIntOrNull() }
                if (bandLevels.size == eqBands.size) {
                    val bass = audioPrefs.getInt("$KEY_USER_PRESET_BASS_PREFIX$i", 0)
                    val virt = audioPrefs.getInt("$KEY_USER_PRESET_VIRT_PREFIX$i", 0)
                    presets.add(EqPreset(name, bandLevels, bass, virt))
                }
            }
            _userPresets.value = presets
        } catch (e: Exception) {
            _userPresets.value = emptyList()
        }
    }

    fun applyPreset(presetName: String) {
        val preset = predefinedPresets.find { it.name == presetName }
            ?: _userPresets.value.find { it.name == presetName }
            ?: return
        if (preset.bandLevels.size != eqBands.size) return
        _currentPresetName.value = presetName
        _eqBandLevels.value = preset.bandLevels.mapIndexed { index, level ->
            level.coerceIn(eqBands[index].minLevelMb, eqBands[index].maxLevelMb)
        }
        _bassBoostStrength.value = preset.bassBoostStrength.coerceIn(0, 100)
        _virtualizerStrength.value = preset.virtualizerStrength.coerceIn(0, 100)
        applyEq()
        saveAudioSettings()
    }

    fun saveUserPreset(name: String) {
        val newPreset = EqPreset(name, _eqBandLevels.value, _bassBoostStrength.value, _virtualizerStrength.value)
        val existing = _userPresets.value.toMutableList()
        existing.removeAll { it.name == name }
        existing.add(newPreset)
        _userPresets.value = existing
        _currentPresetName.value = name
        persistUserPresets(existing)
    }

    fun deleteUserPreset(name: String) {
        val remaining = _userPresets.value.filter { it.name != name }
        _userPresets.value = remaining
        if (_currentPresetName.value == name) _currentPresetName.value = FLAT_PRESET_NAME
        persistUserPresets(remaining)
    }

    private fun persistUserPresets(presets: List<EqPreset>) {
        val editor = audioPrefs.edit()
        editor.putInt(KEY_USER_PRESET_COUNT, presets.size)
        presets.forEachIndexed { index, preset ->
            editor.putString("$KEY_USER_PRESET_NAME_PREFIX$index", preset.name)
            editor.putString("$KEY_USER_PRESET_LEVELS_PREFIX$index", preset.bandLevels.joinToString(","))
            editor.putInt("$KEY_USER_PRESET_BASS_PREFIX$index", preset.bassBoostStrength)
            editor.putInt("$KEY_USER_PRESET_VIRT_PREFIX$index", preset.virtualizerStrength)
        }
        editor.apply()
    }

    fun markEqAsModified() {
        if (_currentPresetName.value != USER_MODIFIED_PRESET_NAME) {
            _currentPresetName.value = USER_MODIFIED_PRESET_NAME
        }
    }

    private fun restoreAudioSettings() {
        _limitDisabled.value = audioPrefs.getBoolean(KEY_LIMIT_DISABLED, false)
        val max = if (_limitDisabled.value) BOOST_MAX_PERCENT else BOOST_LIMIT_PERCENT
        _volumeBoost.value = audioPrefs.getInt(KEY_VOLUME_BOOST, BOOST_MIN_PERCENT)
            .coerceIn(BOOST_MIN_PERCENT, max)

        _eqEnabled.value = audioPrefs.getBoolean(KEY_EQ_ENABLED, false)
        _eqBandLevels.value = eqBands.mapIndexed { index, band ->
            audioPrefs.getInt("$KEY_EQ_BAND_PREFIX$index", 0).coerceIn(band.minLevelMb, band.maxLevelMb)
        }
        _bassBoostStrength.value = audioPrefs.getInt(KEY_BASS_BOOST, 0).coerceIn(0, 100)
        _virtualizerStrength.value = audioPrefs.getInt(KEY_VIRTUALIZER, 0).coerceIn(0, 100)
        _reverbPreset.value = audioPrefs.getInt(KEY_REVERB_PRESET, 0).coerceIn(0, reverbPresetNames.lastIndex)
        _dynamicsStrength.value = audioPrefs.getInt(KEY_DYNAMICS_STRENGTH, 0).coerceIn(0, 100)
        _eqPreGain.value = audioPrefs.getInt(KEY_EQ_PRE_GAIN, 50).coerceIn(0, 100)
        _currentPresetName.value = audioPrefs.getString(KEY_CURRENT_PRESET, FLAT_PRESET_NAME) ?: FLAT_PRESET_NAME
    }

    private fun saveAudioSettings() {
        val editor = audioPrefs.edit()
            .putInt(KEY_VOLUME_BOOST, _volumeBoost.value)
            .putBoolean(KEY_LIMIT_DISABLED, _limitDisabled.value)
            .putBoolean(KEY_EQ_ENABLED, _eqEnabled.value)
            .putInt(KEY_BASS_BOOST, _bassBoostStrength.value)
            .putInt(KEY_VIRTUALIZER, _virtualizerStrength.value)
            .putInt(KEY_REVERB_PRESET, _reverbPreset.value)
            .putInt(KEY_DYNAMICS_STRENGTH, _dynamicsStrength.value)
            .putInt(KEY_EQ_PRE_GAIN, _eqPreGain.value)
            .putString(KEY_CURRENT_PRESET, _currentPresetName.value)
        _eqBandLevels.value.forEachIndexed { index, level -> editor.putInt("$KEY_EQ_BAND_PREFIX$index", level) }
        editor.apply()
    }

    companion object {
        /**
         * El propio `Service` en cuanto está arrancado (null mientras no lo está). Es el "puente"
         * que usa `PlayerViewModel` para reenviarle todas sus llamadas y sus `StateFlow`: no es una
         * conexión de verdad entre procesos (esto no lo necesita: `PlayerViewModel` y este `Service`
         * viven siempre en el mismo proceso de la aplicación), así que basta con esto en vez de
         * pasar por el `IBinder` de un `bindService`.
         *
         * Ojo, que `PlayerViewModel` **sí** hace `bindService` (ver su `ensureServiceRunning`), pero
         * para otra cosa completamente distinta: para que Android no pueda parar el `Service`
         * mientras la aplicación esté viva. Ese enlace no se usa para hablar; el `IBinder` que
         * devuelve se tira. Para hablar está esto.
         */
        private val _instance = MutableStateFlow<PlaybackService?>(null)
        val instanceFlow = _instance.asStateFlow()

        private const val FLAT_PRESET_NAME = "Plano"
        private const val USER_MODIFIED_PRESET_NAME = "Usuario"
        private const val KEY_QUEUE = "queue_ids"
        private const val KEY_CURRENT_SONG_ID = "current_song_id"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_PLAYLIST_NAME = "playlist_name"
        private const val KEY_COLLECTION_KIND = "collection_kind"
        private const val KEY_RANDOM_CONTINUATION = "random_continuation"
        private const val KEY_VOLUME_BOOST = "volume_boost"
        private const val KEY_LIMIT_DISABLED = "limit_disabled"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_BAND_PREFIX = "eq_band_"
        private const val KEY_BASS_BOOST = "bass_boost"
        private const val KEY_VIRTUALIZER = "virtualizer"
        private const val KEY_REVERB_PRESET = "reverb_preset"
        private const val KEY_DYNAMICS_STRENGTH = "dynamics_strength"
        private const val KEY_EQ_PRE_GAIN = "eq_pre_gain"
        private const val KEY_CURRENT_PRESET = "current_preset"
        private const val KEY_USER_PRESET_COUNT = "user_preset_count"
        private const val KEY_USER_PRESET_NAME_PREFIX = "user_preset_name_"
        private const val KEY_USER_PRESET_LEVELS_PREFIX = "user_preset_levels_"
        private const val KEY_USER_PRESET_BASS_PREFIX = "user_preset_bass_"
        private const val KEY_USER_PRESET_VIRT_PREFIX = "user_preset_virt_"
    }
}
