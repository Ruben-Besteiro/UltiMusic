package com.untarlamanteca.ultimusic.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.DynamicColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Progreso de reproducción de la canción actual. */
data class PlaybackProgress(val positionMs: Long = 0L, val durationMs: Long = 0L)

/**
 * Posee el [ExoPlayer] y expone el estado de reproducción. Con ámbito de actividad, lo
 * comparten el mini-reproductor de la actividad y el fragmento de canciones.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Cola 1: lo que suena y su contexto (historial ya reproducido + canción actual + lo que el
     * usuario ha encolado a mano). El elemento en [currentIndex] es lo que suena ahora. Es la única
     * cola de la que se reproduce.
     */
    private val _queue1 = MutableStateFlow<List<Song>>(emptyList())
    val queue1 = _queue1.asStateFlow()

    /** Índice de la canción que suena dentro de [queue1]. */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    /**
     * Cola 2: todas las demás canciones (las que no están en [queue1]), mezcladas. Nunca se
     * reproduce directamente de aquí; cuando [queue1] se agota, su elemento 0 pasa al final de la
     * cola 1 y se reproduce. Interna: la interfaz solo necesita la cola 1.
     */
    private val _queue2 = MutableStateFlow<List<Song>>(emptyList())
    val queue2 = _queue2.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress = _progress.asStateFlow()

    /**
     * Color de acento de la aplicación, sacado de la carátula de lo que suena. Lo observan la
     * actividad principal (pestañas, barra de progreso, mini-reproductor) y la ventana del iPod,
     * que así dejan de ser amarillos y se tiñen con la canción. Mientras no hay nada sonando vale
     * [DynamicColor.DEFAULT], es decir, el amarillo de toda la vida.
     */
    private val _accentColor = MutableStateFlow(DynamicColor.DEFAULT)
    val accentColor = _accentColor.asStateFlow()

    /**
     * Nombre de la playlist cuya colección ocupa ahora mismo la cola 1, o null si lo que suena no
     * viene de una playlist (canción suelta, álbum, artista, productor...). Sirve para que el iPod, al
     * reabrir una playlist, sepa si ya es lo que está sonando y deba mostrar el modo normal en vez de
     * entrar en modo navegación.
     */
    private val _currentPlaylistName = MutableStateFlow<String?>(null)
    val currentPlaylistName = _currentPlaylistName.asStateFlow()

    /**
     * Cálculo del acento en curso. Se guarda para poder cancelarlo: si el usuario pasa canciones
     * rápido, el análisis de una portada anterior podría terminar después que el de la actual y
     * dejar el color equivocado.
     */
    private var accentJob: Job? = null

    private val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Al terminar una canción, avanzamos solos a la siguiente.
                // (Cualificado: sin el this@, next() se resolvería al next() deprecado de ExoPlayer.)
                if (state == Player.STATE_ENDED) this@PlayerViewModel.next()
            }
        })
    }

    init {
        viewModelScope.launch {
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
    }

    // ===================================================================================
    // Estrategia de cola de «Canciones».
    //
    // Estos tres métodos CREAN o AMPLÍAN el sistema de dos colas y son, a propósito, exclusivos
    // del fragmento de Canciones (los invoca solo SongsFragment). El resto de fragmentos
    // (Álbumes, Artistas, Géneros…) usarán en el futuro otra lógica de reproducción distinta,
    // todavía por definir, y NO deben llamar a play/playRandom/addToQueue.
    //
    // En cambio, los controles sobre la cola ya existente (next/previous/jumpTo/seek/togglePlayPause)
    // sí los usa el reproductor global —mini-reproductor e iPod— porque operan sobre lo que ya suena.
    // ===================================================================================

    // ---------------------------------------------------------------------------------------------
    // Sistema de dos colas: es la lógica de reproducción EXCLUSIVA del fragmento «Canciones»
    // (SongsFragment). Solo desde ahí se construye la cola (play / playRandom / addToQueue). El resto
    // de fragmentos (Álbumes, Artistas…) usarán en el futuro otra lógica de reproducción distinta, así
    // que no deben llamar a estos métodos.
    // ---------------------------------------------------------------------------------------------

    /**
     * Al pinchar una canción en la lista, se **destruyen ambas colas y se recrean**: la cola 1
     * pasa a contener solo esa canción y la cola 2 todas las demás, mezcladas.
     */
    fun play(song: Song, allSongs: List<Song>) {
        _queue1.value = listOf(song)
        _currentIndex.value = 0
        _queue2.value = allSongs.filter { it.id != song.id }.shuffled()
        _currentPlaylistName.value = null
        playCurrent()
    }

    /** Reproduce una canción al azar de la lista dada (crea una cola con el resto mezclado). */
    fun playRandom(songs: List<Song>) {
        if (songs.isNotEmpty()) play(songs.random(), songs)
    }

    /**
     * Mueve la canción al **final de la cola 1** (la parte que suena y lo encolado a mano). Se quita
     * de donde estuviera —cola 1 o cola 2— para que sea un movimiento y no una duplicación. No corta
     * la reproducción. Si es la canción que suena ahora, no hace nada.
     */
    fun addToQueue(song: Song) {
        val q1 = _queue1.value
        val idx = q1.indexOfFirst { it.id == song.id }
        if (idx == _currentIndex.value) return
        if (idx >= 0) {
            // Ya estaba en la cola 1: la quitamos de su sitio y la reponemos al final.
            _queue1.value = q1.filterIndexed { i, _ -> i != idx } + song
            if (idx < _currentIndex.value) _currentIndex.value -= 1
        } else {
            // Estaba en la cola 2 (o no estaba): la sacamos de la 2 y la añadimos al final de la 1.
            _queue1.value = q1 + song
            _queue2.value = _queue2.value.filter { it.id != song.id }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lógica de reproducción de las COLECCIONES (fichas de álbum, artista y productor).
    //
    // Es distinta de la de «Canciones»: allí se pincha un tema suelto y el resto de la biblioteca
    // pasa a la cola 2 mezclada, porque no hay un "después" natural. Aquí sí lo hay —el álbum
    // entero, en orden—, así que la colección ocupa la cola 1 completa y la cola 2 se queda vacía:
    // al acabar el último tema, se para. Es lo que uno espera al poner un disco.
    // ---------------------------------------------------------------------------------------------

    /**
     * Reproduce [collection] empezando por la canción en la posición [startIndex]. La colección pasa
     * a ser la cola 1 al completo, conservando su orden.
     *
     * [playlistName] se pasa cuando la colección viene de una playlist (ver [currentPlaylistName});
     * null para álbumes, artistas, productores, etc.
     */
    fun playCollection(collection: List<Song>, startIndex: Int, playlistName: String? = null) {
        if (collection.isEmpty() || startIndex !in collection.indices) return
        _queue1.value = collection
        _currentIndex.value = startIndex
        _queue2.value = emptyList()
        _currentPlaylistName.value = playlistName
        playCurrent()
    }

    /** Igual, pero barajando la colección antes (el botón de las flechas cruzadas de la ficha). */
    fun shuffleCollection(collection: List<Song>, playlistName: String? = null) {
        if (collection.isEmpty()) return
        playCollection(collection.shuffled(), 0, playlistName)
    }

    /**
     * Avanza a la siguiente canción. Si quedan canciones por delante en la cola 1, pasa a la
     * siguiente; si la cola 1 se ha agotado, mueve el elemento 0 de la cola 2 al final de la cola 1
     * y lo reproduce. Si no hay nada más, se queda parado.
     *
     * Si está pausada, solo carga la siguiente canción y la mantiene pausada.
     */
    fun next() {
        if (_currentIndex.value < _queue1.value.lastIndex) {
            _currentIndex.value += 1
            loadCurrent(shouldPlay = player.isPlaying)
        } else if (_queue2.value.isNotEmpty()) {
            val nextSong = _queue2.value.first()
            _queue2.value = _queue2.value.drop(1)
            _queue1.value = _queue1.value + nextSong
            _currentIndex.value = _queue1.value.lastIndex
            loadCurrent(shouldPlay = player.isPlaying)
        }
    }

    /**
     * Retrocede a la canción anterior de la cola 1 (si existe) y la reproduce.
     * Si está pausada, solo carga la anterior canción y la mantiene pausada.
     */
    fun previous() {
        if (_currentIndex.value - 1 >= 0) {
            _currentIndex.value -= 1
            loadCurrent(shouldPlay = player.isPlaying)
        }
    }

    /**
     * Salto desde la vista de cola del iPod, que muestra la **lista combinada** cola 1 + cola 2.
     * [d] es el índice dentro de esa lista combinada.
     *
     * Si lo que suena es una playlist ([currentPlaylistName] no nulo), esta es de una sola cola:
     * saltar solo cambia [currentIndex], sin tocar las colas. Si no, es el sistema de dos colas de
     * «Canciones»: la canción pinchada pasa a ser la **última de la cola 1** (lo que asegura que el
     * divisor del iPod aparezca justo tras ella) y todo lo que hubiera después en la lista pasa a la
     * cola 2.
     */
    fun jumpTo(d: Int) {
        val combined = _queue1.value + _queue2.value
        if (d < 0 || d >= combined.size) return

        val oldIndex = _currentIndex.value

        if (_currentPlaylistName.value != null) {
            _currentIndex.value = d
        } else {
            // La canción pinchada siempre pasa a ser el final de la cola 1.
            _queue1.value = combined.subList(0, d + 1).toList()
            _queue2.value = combined.subList(d + 1, combined.size).toList()
            _currentIndex.value = d
        }

        // Si hemos pinchado una canción distinta a la que sonaba, la reproducimos.
        if (d != oldIndex) {
            playCurrent()
        }
    }

    /** Carga en ExoPlayer la canción en [currentIndex] de la cola 1 y la reproduce desde el principio. */
    private fun playCurrent() {
        loadCurrent(shouldPlay = true)
    }

    /** Carga en ExoPlayer la canción en [currentIndex] de la cola 1. Si [shouldPlay] es true, reproduce; si no, mantiene pausa. */
    private fun loadCurrent(shouldPlay: Boolean = true) {
        val song = _queue1.value.getOrNull(_currentIndex.value) ?: return
        _currentSong.value = song
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(song.filePath))))
        player.prepare()
        if (shouldPlay) player.play() else player.pause()
        updateAccent(song)
    }

    /** Recalcula el acento a partir de la carátula de [song] (ver [DynamicColor]). */
    private fun updateAccent(song: Song) {
        accentJob?.cancel()
        accentJob = viewModelScope.launch {
            val app = getApplication<Application>()
            _accentColor.value = DynamicColor.fromCover(app, CoverArt.cover(app, song))
        }
    }

    /** Salta a un punto de la canción actual, indicado como fracción 0..1 de su duración. */
    fun seekToFraction(fraction: Float) {
        val duration = player.duration
        if (duration > 0) {
            player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    /** Alterna play/pausa. Si no hay nada cargado, no hace nada. */
    fun togglePlayPause() {
        if (_currentSong.value == null) return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onCleared() {
        player.release()
    }
}
