package com.untar.ultimusic.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.DeezerApi
import com.untar.ultimusic.data.remote.retryInSeconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de la búsqueda que pinta [PreviewSearchDialogFragment]. */
sealed interface PreviewSearchUiState {
    /** Todavía no se ha buscado nada: el diálogo enseña la invitación a buscar. */
    data object Idle : PreviewSearchUiState
    data object Loading : PreviewSearchUiState

    /** [loadingMore] es la página siguiente pidiéndose de fondo (ver
     * [com.untar.ultimusic.ui.editor.SuggestionsUiState.Success], mismo esquema). */
    data class Success(
        val tracks: List<DeezerApi.PreviewTrack>,
        val hasMore: Boolean,
        val loadingMore: Boolean = false
    ) : PreviewSearchUiState

    data object Empty : PreviewSearchUiState
    data object Error : PreviewSearchUiState
    data class RateLimited(val retryInSeconds: Long) : PreviewSearchUiState
}

/** Qué canción tiene el reproductor de fragmentos y en qué punto está. */
data class PreviewPlayback(val trackId: Long?, val status: Status) {
    enum class Status { IDLE, LOADING, PLAYING, PAUSED }

    companion object {
        val IDLE = PreviewPlayback(null, Status.IDLE)
    }
}

/**
 * Busca en Deezer y reproduce los fragmentos de 30 s de [PreviewSearchDialogFragment].
 *
 * Lleva su **propio [ExoPlayer]**, independiente del de `PlaybackService`: un fragmento no es una
 * canción de la biblioteca, no debe entrar en la cola, en las estadísticas ni en la notificación de
 * reproducción. Con `handleAudioFocus = true` el de la biblioteca se pausa solo al empezar un
 * fragmento (pierde el foco de audio), sin que este ViewModel tenga que conocerlo.
 *
 * El reproductor vive lo que el diálogo: es un ViewModel de fragmento, así que cerrar el diálogo lo
 * libera ([onCleared]) y el fragmento se corta. Es solo streaming: ExoPlayer no cachea a disco si
 * no se le configura una caché, y aquí no se le configura ninguna (ver los términos en [DeezerApi]).
 */
class PreviewSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<PreviewSearchUiState>(PreviewSearchUiState.Idle)
    val state: StateFlow<PreviewSearchUiState> = _state.asStateFlow()

    private val _playback = MutableStateFlow(PreviewPlayback.IDLE)
    val playback: StateFlow<PreviewPlayback> = _playback.asStateFlow()

    private val _playbackErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Se emite cuando un fragmento no ha podido reproducirse (ni tras pedir una URL nueva). */
    val playbackErrors: SharedFlow<Unit> = _playbackErrors.asSharedFlow()

    private var lastQuery: String? = null
    private var nextIndex = 0
    private var searchJob: Job? = null

    /** Creado la primera vez que se toca un fragmento: abrir el diálogo solo para buscar no debería
     * reservar un decodificador. */
    private var exo: ExoPlayer? = null
    private var currentTrackId: Long? = null

    /** Canción para la que ya se ha pedido una URL nueva tras un error; evita el bucle si la
     * segunda también falla. Se limpia al llegar a reproducirse o al fallar del todo. */
    private var retriedForId: Long? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retriedForId = null
            syncPlayback()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlayback()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncPlayback()

        override fun onPlayerError(error: PlaybackException) = handlePlayerError()
    }

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(getApplication<Application>())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also {
            it.addListener(listener)
            exo = it
        }

    // --- Búsqueda ---

    /** Lanza una búsqueda nueva desde la primera página; vacío no busca nada. */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        lastQuery = trimmed
        runSearch()
    }

    /** Botón "Reintentar" del estado de error. */
    fun retry() = runSearch()

    private fun runSearch() {
        val query = lastQuery ?: return
        nextIndex = 0
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = PreviewSearchUiState.Loading
            _state.value = runCatching { DeezerApi.search(query, index = 0) }.fold(
                onSuccess = { page ->
                    nextIndex = page.nextIndex
                    if (page.tracks.isEmpty()) {
                        PreviewSearchUiState.Empty
                    } else {
                        PreviewSearchUiState.Success(page.tracks, page.hasMore)
                    }
                },
                onFailure = { error ->
                    if (error is ApiRateLimitException) {
                        PreviewSearchUiState.RateLimited(error.retryInSeconds())
                    } else {
                        PreviewSearchUiState.Error
                    }
                }
            )
        }
    }

    /** Scroll infinito: añade la página siguiente al final. Se ignora si no hay más, si ya hay una
     * pidiéndose o si el estado no es [PreviewSearchUiState.Success]. */
    fun loadMore() {
        val current = _state.value as? PreviewSearchUiState.Success ?: return
        if (!current.hasMore || current.loadingMore) return
        val query = lastQuery ?: return

        _state.value = current.copy(loadingMore = true)
        searchJob = viewModelScope.launch {
            _state.value = runCatching { DeezerApi.search(query, index = nextIndex) }.fold(
                onSuccess = { page ->
                    nextIndex = page.nextIndex
                    PreviewSearchUiState.Success(current.tracks + page.tracks, page.hasMore)
                },
                // Un fallo al cargar MÁS no tira la lista que ya se veía; con un rate limit se corta
                // el scroll infinito para no volver a pedir la página que acaban de denegar.
                onFailure = { error ->
                    current.copy(
                        loadingMore = false,
                        hasMore = if (error is ApiRateLimitException) false else current.hasMore
                    )
                }
            )
        }
    }

    // --- Reproducción ---

    /**
     * Tocar una fila: si es otra canción, la reproduce; si es la que ya está cargada, alterna
     * pausa/reproducción (y si había terminado, la reinicia).
     */
    fun onTrackTapped(track: DeezerApi.PreviewTrack) {
        val p = player()
        if (currentTrackId == track.id) {
            when {
                p.playbackState == Player.STATE_ENDED -> {
                    p.seekTo(0)
                    p.play()
                }
                p.playWhenReady -> p.pause()
                else -> p.play()
            }
            return
        }
        retriedForId = null
        start(track.id, track.previewUrl)
    }

    /** Pausa sin liberar nada; el diálogo lo llama al pasar a segundo plano. */
    fun pause() {
        exo?.pause()
    }

    private fun start(trackId: Long, url: String) {
        currentTrackId = trackId
        _playback.value = PreviewPlayback(trackId, PreviewPlayback.Status.LOADING)
        val p = player()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    private fun syncPlayback() {
        val id = currentTrackId ?: return
        val p = exo ?: return
        val status = when {
            p.playbackState == Player.STATE_ENDED -> PreviewPlayback.Status.PAUSED
            p.playbackState == Player.STATE_BUFFERING && p.playWhenReady -> PreviewPlayback.Status.LOADING
            p.isPlaying -> PreviewPlayback.Status.PLAYING
            else -> PreviewPlayback.Status.PAUSED
        }
        _playback.value = PreviewPlayback(id, status)
    }

    /** La URL firmada del fragmento puede haber caducado: se pide una nueva UNA vez antes de rendirse. */
    private fun handlePlayerError() {
        val id = currentTrackId ?: return
        if (retriedForId == id) {
            failPlayback()
            return
        }
        retriedForId = id
        viewModelScope.launch {
            val url = runCatching { DeezerApi.freshPreviewUrl(id) }.getOrNull()
            if (currentTrackId != id) return@launch // el usuario tocó otra canción mientras tanto
            if (url == null) failPlayback() else start(id, url)
        }
    }

    private fun failPlayback() {
        exo?.stop()
        currentTrackId = null
        retriedForId = null
        _playback.value = PreviewPlayback.IDLE
        _playbackErrors.tryEmit(Unit)
    }

    override fun onCleared() {
        exo?.release()
        exo = null
    }
}
