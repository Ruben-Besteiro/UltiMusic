package com.untar.ultimusic.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaSessionService
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.playback.PlaybackService
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.Headphones
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.log2

/** Progreso de reproducción de la canción actual. */
data class PlaybackProgress(val positionMs: Long = 0L, val durationMs: Long = 0L)

/**
 * Qué tipo de colección ocupa la cola cuando NO es una cola suelta (ver
 * [com.untar.ultimusic.playback.PlaybackService.isLooseQueue]). La usa el iPod para elegir la
 * palabra correcta al anunciar cuántas canciones quedan ("en la lista", "en el álbum", "de este
 * artista"...) y qué decir al llegar a la última (ver
 * [com.untar.ultimusic.ui.player.IPodDialogFragment]). [LISTA] y [GENRE] los pone la ficha
 * intermedia de una lista o un género al empezar a reproducir esa colección (ver
 * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment]); [ALBUM], [ARTIST] y
 * [PRODUCER] los pone la ficha de detalle (ver [com.untar.ultimusic.ui.library.DetailDialogFragment]).
 */
enum class CollectionKind { LISTA, GENRE, ALBUM, ARTIST, PRODUCER }

/** Volumen normal: sin amplificar. Es el mínimo del amplificador y su valor por defecto. */
const val BOOST_MIN_PERCENT = 100

/**
 * Tope del amplificador mientras el límite está puesto. Por encima de esto hay que marcar
 * "Desactivar límite", que solo se permite sin auriculares (ver
 * [com.untar.ultimusic.playback.PlaybackService.setLimitDisabled]).
 */
const val BOOST_LIMIT_PERCENT = 150

/**
 * Tope absoluto, incluso con el límite quitado.
 *
 * No está para proteger al usuario —de eso se encarga [BOOST_LIMIT_PERCENT]— sino porque **un tope
 * tiene que existir**: sin él, los cálculos de la regla se desbordan y el ajuste se va a números
 * absurdos (ver la cabecera de [com.untar.ultimusic.ui.common.ValueRuler]).
 *
 * 1000 % son unos 33 dB, muy por encima de cualquier uso real: mucho antes de llegar ahí el sonido
 * es solo distorsión. Está puesto tan lejos para que arrastrando no se llegue nunca por accidente.
 */
const val BOOST_MAX_PERCENT = 1000

/**
 * Cuántos decibelios añade el amplificador puesto en [percent]. La explicación completa del porqué
 * de esta fórmula (y no el `20 * log10` que uno esperaría) está en el comentario de
 * [com.untar.ultimusic.playback.PlaybackService.applyBoost], que es quien la usa de verdad.
 *
 * `internal` (no `private`) porque [PlaybackService][com.untar.ultimusic.playback.PlaybackService]
 * vive en otro fichero y otro paquete, pero dentro del mismo módulo de la aplicación.
 */
internal fun boostGainDb(percent: Int): Double = 10.0 * log2(percent / 100.0)

/**
 * Una banda del ecualizador: su frecuencia central (en Hz, para la etiqueta) y el rango de
 * milibelios que admite su slider. El número de bandas y este rango los decide el propio
 * dispositivo (ver [com.untar.ultimusic.playback.PlaybackService.eqBands]), no son un número fijo
 * de la aplicación.
 */
data class EqBand(val centerFreqHz: Int, val minLevelMb: Int, val maxLevelMb: Int)

/**
 * Un preset del ecualizador: nombre, niveles de todas las bandas, y fuerzas de bass boost y
 * virtualizer. Se usa tanto para presets predefinidos (Rock, Pop…) como para presets guardados
 * por el usuario.
 */
data class EqPreset(
    val name: String,
    val bandLevels: List<Int>,  // Nivel en mB para cada banda; orden = eqBands
    val bassBoostStrength: Int,  // 0-100
    val virtualizerStrength: Int  // 0-100
)

/**
 * Fachada de ámbito de actividad sobre [PlaybackService], compartida por el mini-reproductor y la
 * ventana del iPod, igual que antes. **El reproductor de verdad —el `ExoPlayer`, la cola, el
 * amplificador, el ecualizador— ya NO vive aquí**: vive en [PlaybackService], un `Service` en
 * primer plano con notificación (ver su cabecera para el porqué del cambio: en resumen, para que la
 * música y sus controles sobrevivan a cerrar la aplicación o apagar la pantalla, cosa que un
 * `ViewModel` —atado a la vida de la `Activity`— no puede garantizar).
 *
 * Esta clase se ha quedado en una **fachada**: en cuanto se crea arranca el `Service` (si no estaba
 * ya arrancado, [PlaybackService] es un singleton de facto mientras el proceso vive) y a partir de
 * ahí se limita a dos cosas:
 * - Reenviar cada método público a su gemelo de [PlaybackService] (`fun play(song) { service?.play(song) }`,
 *   y así con el resto).
 * - Exponer los mismos `StateFlow`/`Flow` que exponía antes, pero rellenados en caliente a partir de
 *   los de [PlaybackService] con [serviceState]/[serviceEvents].
 *
 * Gracias a eso, **ningún fragmento ni adaptador de la aplicación ha tenido que cambiar una sola
 * línea**: todos siguen hablando con `PlayerViewModel` exactamente igual que antes de este cambio.
 *
 * **Qué pasa cuando no hay Service.** [service] vale `null` en dos situaciones, y las dos son
 * frecuentes de verdad:
 * - Entre que se crea este ViewModel (arranca `MainActivity`) y que el sistema termina de llamar a
 *   `PlaybackService.onCreate()`. Arrancar un `Service` es asíncrono por diseño de Android y, desde
 *   Android 8, los arranques en segundo plano se encolan y pueden retrasarse; en un arranque en frío
 *   el hilo principal va cargadísimo, así que la interfaz puede estar respondiendo al tacto antes de
 *   que el `Service` exista.
 * - Justo después de crearse este ViewModel, mientras el `Service` termina de arrancar.
 *
 * Antes había un segundo caso, y era un bug de los gordos: el `Service` solo se *arrancaba*
 * (`startService`), nunca se *enlazaba*. Un `Service` arrancado y sin nadie enlazado a él es, para
 * Android, prescindible en cuanto deja de estar en primer plano —y este solo está en primer plano
 * mientras suena algo—, así que tras un rato en segundo plano el sistema (o el gestor de batería del
 * fabricante) se lo llevaba por delante. Como el `startService` estaba solo en el [init], no lo
 * volvía a arrancar nadie nunca: [service] se quedaba `null` para siempre y **cada toque en una
 * canción se descartaba en silencio** —se veía el ripple y no sonaba nada— hasta matar la aplicación.
 *
 * Eso se parcheó con [withService] (dejar la orden pendiente y volver a arrancar el `Service`), pero
 * el parche solo tapaba el síntoma de las órdenes que da el usuario: seguía habiendo huecos en los
 * que el `Service` moría a media faena y se llevaba por delante cosas que nadie había pedido —la más
 * grave, encadenar la canción siguiente al acabarse una (ver `PlaybackService.loadCurrent`)—.
 *
 * **El arreglo de fondo es [bindService][android.content.Context.bindService]**, que es lo que hace
 * [ensureServiceRunning] además de arrancarlo. Un `Service` con alguien enlazado no lo para el
 * sistema mientras dure ese enlace: solo desaparece si muere el proceso entero. Y como el enlace lo
 * mantiene este ViewModel —vivo mientras viva `MainActivity`, rotaciones incluidas— el `Service`
 * existe, garantizado, durante toda la vida de la aplicación. Al soltarlo ([onCleared], cuando la
 * `Activity` se destruye de verdad) el `Service` vuelve a depender solo de su arranque: sigue sonando
 * si estaba sonando (ver `stopWithTask="false"` en el manifiesto) y se para solo si no.
 *
 * [withService] se queda igualmente: entre crear este ViewModel y que el `Service` esté listo hay
 * unos milisegundos, y ahí sigue haciendo falta guardar la orden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val serviceFlow = PlaybackService.instanceFlow

    /** El [PlaybackService] ya arrancado, o `null` durante la ventana descrita en la cabecera. */
    private val service: PlaybackService? get() = serviceFlow.value

    /**
     * La orden que el usuario ha dado mientras no había Service, a la espera de que aparezca (ver
     * [withService]). Una sola: son órdenes de reproducción, mutuamente excluyentes por naturaleza,
     * así que si llegan dos seguidas la que vale es la última —justo lo que espera quien toca dos
     * canciones seguidas—. Solo se toca desde el hilo principal.
     */
    private var pendingAction: ((PlaybackService) -> Unit)? = null

    /**
     * El enlace con el [PlaybackService] (ver la cabecera de la clase). No hace falta el `IBinder`
     * que devuelve —`PlayerViewModel` habla con el `Service` por [PlaybackService.instanceFlow], que
     * es directo y sin `Binder` de por medio porque los dos viven en el mismo proceso—: lo que
     * importa es el enlace en sí, que es lo que impide que Android pare el `Service`.
     *
     * Los tres métodos se dejan vacíos a propósito. [ServiceConnection.onNullBinding] no llega a
     * ocurrir (se enlaza con la acción que [MediaSessionService] espera, así que devuelve un
     * `Binder` de verdad) y [ServiceConnection.onServiceDisconnected] solo ocurriría si muriera el
     * proceso del `Service`, que es este mismo: si eso pasa, no queda nadie a quien avisar.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
        override fun onNullBinding(name: ComponentName?) = Unit
    }

    /** Si [serviceConnection] está registrado, para no enlazar dos veces ni soltar lo que no se cogió. */
    private var bound = false

    init {
        ensureServiceRunning()

        // Ejecuta la orden pendiente, si la hay, en cuanto el Service se publica. `Eagerly` no hace
        // falta pedirlo: `viewModelScope` ya es Dispatchers.Main.immediate, el hilo desde el que se
        // puede tocar el reproductor.
        viewModelScope.launch {
            serviceFlow.collect { svc ->
                if (svc == null) return@collect
                val action = pendingAction ?: return@collect
                pendingAction = null
                action(svc)
            }
        }
    }

    /**
     * Deja el [PlaybackService] en marcha y enlazado. Hace las dos cosas porque cada una sirve para
     * algo distinto, y hacen falta las dos (ver la cabecera de la clase):
     *
     * - **Arrancarlo** (`startService`) es lo que le da vida propia, independiente de la `Activity`:
     *   es lo que hace que la música siga sonando con la aplicación cerrada. Arranque NO en primer
     *   plano a propósito: si se usara `startForegroundService`, el `Service` tendría solo 5 segundos
     *   para llamar a `startForeground()` o el sistema lo mata (`RemoteServiceException`), y eso solo
     *   pasa cuando de verdad empieza a sonar algo (lo gestiona Media3 solo, ver la cabecera de
     *   `PlaybackService`). Aquí puede que el usuario tarde en pulsar play —o no llegue a hacerlo—.
     * - **Enlazarlo** (`bindService`) es lo que impide que el sistema lo pare mientras la aplicación
     *   esté viva. Un `Service` solo arrancado, cuando no está en primer plano, es prescindible para
     *   Android; con un enlace vivo, no.
     *
     * Se enlaza con la acción que espera [MediaSessionService] (la misma del `intent-filter` del
     * manifiesto) porque es el contrato documentado de Media3 y así devuelve un `Binder` de verdad,
     * aunque aquí no se use para nada: [BIND_AUTO_CREATE][android.content.Context.BIND_AUTO_CREATE]
     * además crea el `Service` si no existiera, o sea que el enlace por sí solo ya lo revive.
     *
     * La llaman el [init] y `MainActivity.onResume`. Si ya está todo hecho —el caso normal— no hace
     * nada: un `Service` no se crea dos veces y [bound] evita enlazar por duplicado.
     *
     * Los `runCatching` son por si esto llegara a llamarse con la aplicación ya en segundo plano:
     * ahí Android 8+ prohíbe arrancar servicios y `startService` lanzaría `IllegalStateException`
     * (`bindService` sí está permitido desde segundo plano, que es otra razón para tenerlo). No
     * debería pasar —solo se llama desde `onResume` o al tocar algo, con la aplicación visible—, pero
     * de morir la aplicación por esto no se salva nadie, y la orden se queda pendiente igualmente
     * para el siguiente intento.
     */
    fun ensureServiceRunning() {
        val app = getApplication<Application>()
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }

        if (bound) return
        val bindIntent = Intent(app, PlaybackService::class.java)
            .setAction(MediaSessionService.SERVICE_INTERFACE)
        runCatching {
            app.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            // A propósito sin mirar lo que devuelve `bindService`: aunque diga que no ha podido
            // enlazar, la conexión queda registrada y hay que soltarla igual (lo dice su
            // documentación). Con `bound = true` nos aseguramos de que [onCleared] lo hará.
            bound = true
        }
    }

    /**
     * Suelta el enlace del [PlaybackService]. Ocurre cuando `MainActivity` se destruye de verdad
     * (no en una rotación: el `ViewModel` sobrevive a eso). A partir de aquí el `Service` vuelve a
     * depender solo de su arranque, que es justo lo que se quiere: si estaba sonando algo sigue
     * sonando —con su notificación en primer plano, y `stopWithTask="false"` en el manifiesto para
     * que cerrar desde "recientes" tampoco lo corte—, y si no estaba sonando nada se para solo.
     */
    override fun onCleared() {
        if (bound) {
            bound = false
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
        }
        super.onCleared()
    }

    /**
     * Le pasa [action] al [PlaybackService] ya arrancado; si todavía no lo está (o lo han matado),
     * lo arranca y deja la orden pendiente para ejecutarla en cuanto aparezca. Por aquí pasan todas
     * las órdenes de reproducción: son las que el usuario da tocando algo y espera ver cumplidas, y
     * antes se perdían en silencio (ver la cabecera de la clase).
     *
     * Los ajustes de sonido (amplificador, ecualizador) NO pasan por aquí a propósito: no tienen la
     * urgencia de un toque en una canción, y encolarlos aquí desalojaría la orden de reproducción
     * pendiente, que es la que de verdad importa. Además sus diálogos solo se abren desde el
     * reproductor, o sea que para entonces el Service lleva rato vivo.
     */
    private fun withService(action: (PlaybackService) -> Unit) {
        val svc = service
        if (svc != null) {
            action(svc)
            return
        }
        pendingAction = action
        ensureServiceRunning()
    }

    /**
     * Construye un `StateFlow` de ámbito de actividad que seguirá siempre al de [PlaybackService]
     * que señale [selector], usando [default] mientras el Service no esté listo. `flatMapLatest`
     * es lo que permite "seguir" un StateFlow que en sí mismo puede no existir todavía (cuando
     * [serviceFlow] vale null, usa un StateFlow de usar y tirar con el valor por defecto).
     */
    private fun <T> serviceState(default: T, selector: (PlaybackService) -> StateFlow<T>): StateFlow<T> =
        serviceFlow.flatMapLatest { it?.let(selector) ?: MutableStateFlow(default) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, default)

    /** Igual que [serviceState] pero para los avisos de una sola vez ([missingFile], [limitForcedByHeadphones]). */
    private fun <T> serviceEvents(selector: (PlaybackService) -> Flow<T>): Flow<T> =
        serviceFlow.flatMapLatest { it?.let(selector) ?: emptyFlow() }

    val queue: StateFlow<List<Song>> = serviceState(emptyList()) { it.queue }
    val currentIndex: StateFlow<Int> = serviceState(0) { it.currentIndex }
    val isLooseQueue: StateFlow<Boolean> = serviceState(false) { it.isLooseQueue }
    val currentSong: StateFlow<Song?> = serviceState(null) { it.currentSong }
    val isPlaying: StateFlow<Boolean> = serviceState(false) { it.isPlaying }
    val progress: StateFlow<PlaybackProgress> = serviceState(PlaybackProgress()) { it.progress }

    /**
     * Color de acento de la aplicación, sacado de la carátula de lo que suena. Lo observan la
     * actividad principal (pestañas, barra de progreso, mini-reproductor) y la ventana del iPod,
     * que así dejan de ser amarillos y se tiñen con la canción. Mientras no hay nada sonando (o el
     * Service todavía no está listo) vale [DynamicColor.DEFAULT], el amarillo de toda la vida.
     */
    val accentColor: StateFlow<Int> = serviceState(DynamicColor.DEFAULT) { it.accentColor }

    /** Ver [PlaybackService.coverRevision]. Quien pinte una carátula sacada de [currentSong] o
     * [queue] debe recargarla también cuando esto cambie, no solo cuando esos dos cambien: si no,
     * reemplazar una carátula por otra con el mismo nombre de archivo (lo habitual, ver el editor
     * de metadatos) no se refrescaría en esa vista aunque el acento sí lo haga. */
    val coverRevision: StateFlow<Int> = serviceState(0) { it.coverRevision }

    val currentPlaylistName: StateFlow<String?> = serviceState(null) { it.currentPlaylistName }
    val currentCollectionKind: StateFlow<CollectionKind?> = serviceState(null) { it.currentCollectionKind }

    /** Avisa de que el archivo de una canción no aparece por ningún lado (ver `MainActivity`). */
    val missingFile: Flow<Song> = serviceEvents { it.missingFile }

    /** Avisa cuando se intenta agregar la canción que se está reproduciendo actualmente a la cola. */
    val currentSongAddedToQueue: Flow<Song> = serviceEvents { it.currentSongAddedToQueue }

    /** Avisa (con el nº de canciones) cada vez que se añade algo a la cola, para el Toast de MainActivity. */
    val songsAddedToQueue: Flow<Int> = serviceEvents { it.songsAddedToQueue }

    /** Avisa cuando se intenta añadir a la cola sin que suene nada todavía (ver `PlaybackService`). */
    val cannotAddToEmptyQueue: Flow<Unit> = serviceEvents { it.cannotAddToEmptyQueue }

    val volumeBoost: StateFlow<Int> = serviceState(BOOST_MIN_PERCENT) { it.volumeBoost }
    val limitDisabled: StateFlow<Boolean> = serviceState(false) { it.limitDisabled }
    val limitForcedByHeadphones: Flow<Unit> = serviceEvents { it.limitForcedByHeadphones }

    val eqBands: List<EqBand> get() = service?.eqBands ?: emptyList()
    val bassBoostSupported: Boolean get() = service?.bassBoostSupported ?: false
    val virtualizerSupported: Boolean get() = service?.virtualizerSupported ?: false
    val reverbSupported: Boolean get() = service?.reverbSupported ?: false
    val dynamicsProcessingSupported: Boolean get() = service?.dynamicsProcessingSupported ?: false

    /** Nombres de las salas de `PresetReverb`: una lista fija, así que aquí vale un respaldo propio
     *  para la breve ventana sin Service en vez de devolver una lista vacía. */
    val reverbPresetNames: List<String> get() = service?.reverbPresetNames ?: FALLBACK_REVERB_PRESET_NAMES

    val eqEnabled: StateFlow<Boolean> = serviceState(false) { it.eqEnabled }
    val eqBandLevels: StateFlow<List<Int>> = serviceState(emptyList()) { it.eqBandLevels }
    val bassBoostStrength: StateFlow<Int> = serviceState(0) { it.bassBoostStrength }
    val virtualizerStrength: StateFlow<Int> = serviceState(0) { it.virtualizerStrength }
    val reverbPreset: StateFlow<Int> = serviceState(0) { it.reverbPreset }
    val dynamicsStrength: StateFlow<Int> = serviceState(0) { it.dynamicsStrength }
    val eqPreGain: StateFlow<Int> = serviceState(50) { it.eqPreGain }
    val currentPresetName: StateFlow<String> = serviceState(FALLBACK_FLAT_PRESET_NAME) { it.currentPresetName }
    val userPresets: StateFlow<List<EqPreset>> = serviceState(emptyList()) { it.userPresets }
    val predefinedPresetNames: List<String> get() = service?.predefinedPresetNames ?: emptyList()
    val userModifiedPresetName: String get() = service?.userModifiedPresetName ?: FALLBACK_USER_MODIFIED_PRESET_NAME

    /** Debe llamarla la actividad al pasar a segundo plano (`onStop`): ver `PlaybackService.savePlaybackState`. */
    fun savePlaybackState() {
        service?.savePlaybackState()
    }

    fun play(song: Song) = withService { it.play(song) }

    /** Ver `PlaybackService.playRandomFromLibrary`. */
    fun playRandomFromLibrary() = withService { it.playRandomFromLibrary() }

    fun addToQueue(song: Song) = withService { it.addToQueue(song) }

    fun addToQueue(songs: List<Song>) = withService { it.addToQueue(songs) }

    /** Ver `PlaybackService.reorderQueue` (arrastrar y soltar una fila de la cola en el iPod). */
    fun reorderQueue(songs: List<Song>) = withService { it.reorderQueue(songs) }

    fun playCollection(
        collection: List<Song>,
        startIndex: Int,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) = withService { it.playCollection(collection, startIndex, playlistName, collectionKind) }

    fun shuffleCollection(
        collection: List<Song>,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) = withService { it.shuffleCollection(collection, playlistName, collectionKind) }

    fun skipToPrevious() = withService { it.skipToPrevious() }

    fun skipToNext() = withService { it.skipToNext() }

    fun jumpTo(d: Int, forcePlay: Boolean = true) = withService { it.jumpTo(d, forcePlay) }

    fun refreshSong(song: Song) {
        service?.refreshSong(song)
    }

    fun seekToFraction(fraction: Float) {
        service?.seekToFraction(fraction)
    }

    fun togglePlayPause() = withService { it.togglePlayPause() }

    /** Ver `PlaybackService.currentPositionMs` (lo usa `VideoScreenController` para sincronizar el vídeo). */
    fun currentPositionMs(): Long = service?.currentPositionMs() ?: 0L

    // --- Amplificador de volumen ---

    fun setVolumeBoost(percent: Int) {
        service?.setVolumeBoost(percent)
    }

    fun setLimitDisabled(disabled: Boolean): Headphones.Check =
        service?.setLimitDisabled(disabled) ?: Headphones.Check.NONE

    // --- Ecualizador ---

    fun setEqEnabled(enabled: Boolean) {
        service?.setEqEnabled(enabled)
    }

    fun setEqBandLevel(band: Int, levelMb: Int) {
        service?.setEqBandLevel(band, levelMb)
    }

    fun setBassBoostStrength(percent: Int) {
        service?.setBassBoostStrength(percent)
    }

    fun setVirtualizerStrength(percent: Int) {
        service?.setVirtualizerStrength(percent)
    }

    fun setReverbPreset(preset: Int) {
        service?.setReverbPreset(preset)
    }

    fun setDynamicsStrength(percent: Int) {
        service?.setDynamicsStrength(percent)
    }

    fun setEqPreGain(percent: Int) {
        service?.setEqPreGain(percent)
    }

    fun resetEqualizer() {
        service?.resetEqualizer()
    }

    fun applyPreset(presetName: String) {
        service?.applyPreset(presetName)
    }

    fun saveUserPreset(name: String) {
        service?.saveUserPreset(name)
    }

    fun deleteUserPreset(name: String) {
        service?.deleteUserPreset(name)
    }

    fun markEqAsModified() {
        service?.markEqAsModified()
    }

    private companion object {
        const val FALLBACK_FLAT_PRESET_NAME = "Plano"
        const val FALLBACK_USER_MODIFIED_PRESET_NAME = "Usuario"
        val FALLBACK_REVERB_PRESET_NAMES = listOf(
            "Ninguno", "Habitación pequeña", "Habitación mediana", "Sala grande",
            "Salón mediano", "Salón grande", "Plancha"
        )
    }
}
