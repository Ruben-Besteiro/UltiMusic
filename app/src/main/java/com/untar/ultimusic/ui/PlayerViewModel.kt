package com.untar.ultimusic.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlin.math.log2

/** Progreso de reproducción de la canción actual. */
data class PlaybackProgress(val positionMs: Long = 0L, val durationMs: Long = 0L)

/**
 * Qué tipo de colección ocupa la cola cuando NO es una cola suelta (ver
 * [com.untar.ultimusic.playback.PlaybackService.isLooseQueue]). La usa el iPod para elegir la
 * palabra correcta al anunciar cuántas canciones quedan ("en la playlist", "en el álbum", "de este
 * artista"...) y qué decir al llegar a la última (ver
 * [com.untar.ultimusic.ui.player.IPodDialogFragment]). [PLAYLIST] y [GENRE] los pone el modo
 * navegación del iPod al empezar a reproducir esa colección (ver
 * [com.untar.ultimusic.ui.player.IPodDialogFragment.enterBrowseMode]); [ALBUM], [ARTIST] y
 * [PRODUCER] los pone la ficha de detalle (ver [com.untar.ultimusic.ui.library.DetailDialogFragment]).
 */
enum class CollectionKind { PLAYLIST, GENRE, ALBUM, ARTIST, PRODUCER }

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
 * El único hueco real es la ventana, normalmente de una fracción de segundo, entre que se crea este
 * ViewModel (arranca `MainActivity`) y que el sistema termina de llamar a `PlaybackService.onCreate()`:
 * mientras tanto [service] vale `null` y las llamadas se ignoran en silencio (ver cada método). No
 * hay forma de evitar esa ventana del todo —arrancar un `Service` es asíncrono por diseño de
 * Android—, pero en la práctica no da tiempo a que el usuario llegue a tocar nada antes de que se
 * cierre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val serviceFlow = PlaybackService.instanceFlow

    /** El [PlaybackService] ya arrancado, o `null` durante la ventana descrita en la cabecera. */
    private val service: PlaybackService? get() = serviceFlow.value

    init {
        // Arranque NO en primer plano a propósito: si se usara `startForegroundService`, el
        // Service tendría solo 5 segundos para llamar a `startForeground()` o el sistema lo mata
        // (`RemoteServiceException`), y eso solo pasa cuando de verdad empieza a sonar algo (lo
        // gestiona Media3 solo, ver la cabecera de PlaybackService). Aquí, al abrir la aplicación,
        // puede que el usuario tarde en pulsar play —o no llegue a hacerlo—, así que un arranque
        // normal (en segundo plano) es el que corresponde.
        app.startService(Intent(app, PlaybackService::class.java))
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

    fun play(song: Song) {
        service?.play(song)
    }

    fun addToQueue(song: Song) {
        service?.addToQueue(song)
    }

    fun addToQueue(songs: List<Song>) {
        service?.addToQueue(songs)
    }

    fun playCollection(
        collection: List<Song>,
        startIndex: Int,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) {
        service?.playCollection(collection, startIndex, playlistName, collectionKind)
    }

    fun shuffleCollection(
        collection: List<Song>,
        playlistName: String? = null,
        collectionKind: CollectionKind? = null
    ) {
        service?.shuffleCollection(collection, playlistName, collectionKind)
    }

    fun skipToPrevious() {
        service?.skipToPrevious()
    }

    fun skipToNext() {
        service?.skipToNext()
    }

    fun jumpTo(d: Int, forcePlay: Boolean = true) {
        service?.jumpTo(d, forcePlay)
    }

    fun refreshSong(song: Song) {
        service?.refreshSong(song)
    }

    fun seekToFraction(fraction: Float) {
        service?.seekToFraction(fraction)
    }

    fun togglePlayPause() {
        service?.togglePlayPause()
    }

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
