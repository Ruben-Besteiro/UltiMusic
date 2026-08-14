package com.untar.ultimusic.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.LrcLibApi
import com.untar.ultimusic.data.remote.retryInSeconds
import com.untar.ultimusic.model.LyricsSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de la búsqueda que pinta [LyricsSuggestionsDialogFragment]. */
sealed interface LyricsSuggestionsUiState {
    data object Loading : LyricsSuggestionsUiState
    data class Success(val suggestions: List<LyricsSuggestion>) : LyricsSuggestionsUiState
    data object Empty : LyricsSuggestionsUiState
    data object Error : LyricsSuggestionsUiState

    /** lrclib.net nos ha limitado las peticiones. Se separa de [Error] porque "espera un minuto" y
     *  "comprueba tu conexión" piden cosas distintas al usuario: con el mensaje genérico se pondría
     *  a darle a Reintentar, que es justo lo que NO ayuda. [retryInSeconds] es cuánto queda. */
    data class RateLimited(val retryInSeconds: Long) : LyricsSuggestionsUiState
}

/**
 * Busca candidatos de letra en lrclib.net para [LyricsSuggestionsDialogFragment]. Mismo patrón que
 * [MetadataSuggestionsViewModel], pero más simple: no tiene scroll infinito (la búsqueda de
 * [LrcLibApi] devuelve todos los candidatos de una vez, sin paginar).
 */
class LyricsSuggestionsViewModel : ViewModel() {

    private val _state = MutableStateFlow<LyricsSuggestionsUiState>(LyricsSuggestionsUiState.Loading)
    val state: StateFlow<LyricsSuggestionsUiState> = _state.asStateFlow()

    /** Qué se buscó la última vez, para poder repetir la búsqueda con [retry] sin que quien llame
     * tenga que volver a pasar los argumentos. Puede ser la búsqueda automática de título+artista
     * (con la que se abre el diálogo) o una manual desde la barra de búsqueda (ver [searchManual]). */
    private sealed interface LastQuery {
        data class Auto(val title: String, val artist: String) : LastQuery
        data class Manual(val text: String) : LastQuery
    }

    private var lastQuery: LastQuery? = null

    /** Duración del archivo que se está editando, fija durante toda la vida del diálogo (ver
     * [LrcLibApi.search]): no cambia entre la búsqueda automática y las manuales. */
    private var songDurationMs: Long = 0L

    /**
     * Lanza la búsqueda automática la PRIMERA vez que se llama; si el sistema recrea el diálogo
     * (p. ej. al girar la pantalla) y vuelve a llamar con los mismos argumentos, no la repite —
     * mismo guard que [MetadataSuggestionsViewModel.search].
     *
     * [songDurationMs] no filtra nada: sirve para marcar y subir los candidatos que duran lo mismo
     * que el archivo (ver [LrcLibApi.search]).
     */
    fun search(title: String, artist: String, songDurationMs: Long) {
        if (lastQuery != null) return
        this.songDurationMs = songDurationMs
        lastQuery = LastQuery.Auto(title, artist)
        runSearch()
    }

    /** Barra de búsqueda manual del diálogo: el usuario ha corregido el texto (útil cuando la
     * búsqueda automática no encuentra nada) y quiere lanzar una nueva. Vacío no busca nada. */
    fun searchManual(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        lastQuery = LastQuery.Manual(trimmed)
        runSearch()
    }

    /** Botón "Reintentar" del estado de error: repite la última búsqueda, sea automática o manual. */
    fun retry() = runSearch()

    private fun runSearch() {
        val query = lastQuery ?: return
        viewModelScope.launch {
            _state.value = LyricsSuggestionsUiState.Loading
            val result = runCatching {
                when (query) {
                    is LastQuery.Auto -> LrcLibApi.search(query.title, query.artist, songDurationMs)
                    is LastQuery.Manual -> LrcLibApi.search(query.text, songDurationMs)
                }
            }
            _state.value = result.fold(
                onSuccess = { list -> if (list.isEmpty()) LyricsSuggestionsUiState.Empty else LyricsSuggestionsUiState.Success(list) },
                onFailure = { error ->
                    if (error is ApiRateLimitException) {
                        LyricsSuggestionsUiState.RateLimited(error.retryInSeconds())
                    } else {
                        LyricsSuggestionsUiState.Error
                    }
                }
            )
        }
    }
}
