package com.untar.ultimusic.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.remote.LrcLibApi
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
}

/**
 * Busca candidatos de letra en lrclib.net para [LyricsSuggestionsDialogFragment]. Mismo patrón que
 * [MetadataSuggestionsViewModel], pero más simple: la búsqueda de [LrcLibApi] ya trae la letra
 * completa de cada candidato, así que no hace falta ninguna petición aparte al elegir uno (a
 * diferencia de los géneros de MusicBrainz, que sí piden su propia llamada).
 */
class LyricsSuggestionsViewModel : ViewModel() {

    private val _state = MutableStateFlow<LyricsSuggestionsUiState>(LyricsSuggestionsUiState.Loading)
    val state: StateFlow<LyricsSuggestionsUiState> = _state.asStateFlow()

    /** Qué se buscó la primera vez, para poder repetir la búsqueda con [retry] sin que quien llame
     * tenga que volver a pasar los argumentos. */
    private var lastQuery: Pair<String, String>? = null

    /**
     * Lanza la búsqueda la PRIMERA vez que se llama; si el sistema recrea el diálogo (p. ej. al
     * girar la pantalla) y vuelve a llamar con los mismos argumentos, no la repite — mismo guard
     * que [MetadataSuggestionsViewModel.search].
     */
    fun search(title: String, artist: String) {
        if (lastQuery != null) return
        lastQuery = title to artist
        runSearch()
    }

    /** Botón "Reintentar" del estado de error: repite la última búsqueda. */
    fun retry() = runSearch()

    private fun runSearch() {
        val (title, artist) = lastQuery ?: return
        viewModelScope.launch {
            _state.value = LyricsSuggestionsUiState.Loading
            val result = runCatching { LrcLibApi.search(title, artist) }
            _state.value = result.fold(
                onSuccess = { list -> if (list.isEmpty()) LyricsSuggestionsUiState.Empty else LyricsSuggestionsUiState.Success(list) },
                onFailure = { LyricsSuggestionsUiState.Error }
            )
        }
    }
}
