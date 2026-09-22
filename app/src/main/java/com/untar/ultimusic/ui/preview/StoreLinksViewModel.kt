package com.untar.ultimusic.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.DeezerApi
import com.untar.ultimusic.data.remote.MusicBrainzApi
import com.untar.ultimusic.data.remote.retryInSeconds
import com.untar.ultimusic.model.StoreLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de la búsqueda de tiendas que pinta [StoreLinksDialogFragment]. */
sealed interface StoreLinksUiState {
    data object Loading : StoreLinksUiState
    data class Success(val links: List<StoreLink>) : StoreLinksUiState

    /** La consulta ha ido bien pero MusicBrainz no conoce ninguna tienda para esta canción. */
    data object Empty : StoreLinksUiState
    data object Error : StoreLinksUiState

    /** Mismo reparto que [PreviewSearchUiState.RateLimited]: "espera" y "comprueba tu conexión"
     * piden cosas distintas al usuario. */
    data class RateLimited(val retryInSeconds: Long) : StoreLinksUiState
}

/**
 * Busca las tiendas de descarga de una canción para [StoreLinksDialogFragment]: pide su ISRC a
 * Deezer (identifica la grabación exacta, ver [DeezerApi.isrcOf]) y con él consulta los enlaces de
 * MusicBrainz (ver [MusicBrainzApi.downloadStores]). Si Deezer no da el ISRC, o falla, se sigue
 * igualmente con título+artista.
 */
class StoreLinksViewModel : ViewModel() {

    private class Query(val title: String, val artist: String, val deezerTrackId: Long)

    private val _state = MutableStateFlow<StoreLinksUiState>(StoreLinksUiState.Loading)
    val state: StateFlow<StoreLinksUiState> = _state.asStateFlow()

    private var query: Query? = null

    /** Lanza la búsqueda la PRIMERA vez; si el sistema recrea el diálogo (al girar la pantalla) y
     * vuelve a llamar, no la repite — mismo guard que `MetadataSuggestionsViewModel.search`. */
    fun load(title: String, artist: String, deezerTrackId: Long) {
        if (query != null) return
        query = Query(title, artist, deezerTrackId)
        run()
    }

    fun retry() = run()

    private fun run() {
        val q = query ?: return
        viewModelScope.launch {
            _state.value = StoreLinksUiState.Loading
            _state.value = runCatching {
                val isrc = runCatching { DeezerApi.isrcOf(q.deezerTrackId) }.getOrNull()
                MusicBrainzApi.downloadStores(q.title, q.artist, isrc)
            }.fold(
                onSuccess = { links ->
                    if (links.isEmpty()) StoreLinksUiState.Empty else StoreLinksUiState.Success(links)
                },
                onFailure = { error ->
                    if (error is ApiRateLimitException) {
                        StoreLinksUiState.RateLimited(error.retryInSeconds())
                    } else {
                        StoreLinksUiState.Error
                    }
                }
            )
        }
    }
}
