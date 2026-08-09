package com.untar.ultimusic.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.remote.MusicBrainzApi
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de la búsqueda que pinta [MetadataSuggestionsDialogFragment]. */
sealed interface SuggestionsUiState {
    data object Loading : SuggestionsUiState

    /** [hasMore] dice si a MusicBrainz le queda otra página después de esta (ver
     * [MusicBrainzApi.SearchPage]); [loadingMore] es la página siguiente pidiéndose de fondo,
     * mientras las ya cargadas se siguen viendo (a diferencia de [Loading], que tapa la lista
     * entera y solo es para la búsqueda inicial). */
    data class Success(
        val suggestions: List<MetadataSuggestion>,
        val hasMore: Boolean,
        val loadingMore: Boolean = false
    ) : SuggestionsUiState

    data object Empty : SuggestionsUiState
    data object Error : SuggestionsUiState
}

/**
 * Busca candidatos de autorrelleno en MusicBrainz para [MetadataSuggestionsDialogFragment]. Sirve
 * tanto para canciones (busca por título+artista) como para álbumes (busca por álbum+artista);
 * cuál de las dos toca lo decide [SuggestionKind], que llega desde el diálogo.
 *
 * Es un [ViewModel] normal, no un `AndroidViewModel`: a diferencia de [MetadataEditorViewModel] no
 * necesita ningún `Context` de Android, solo llama a [MusicBrainzApi] (que tampoco lo necesita).
 */
class MetadataSuggestionsViewModel : ViewModel() {

    private companion object {
        /** Cuántas páginas vacías, como mucho, se saltan solas antes de rendirse (ver
         * [fetchAccumulating]). */
        const val MAX_EMPTY_PAGE_SKIPS = 4

        /** Algo más de 1 segundo, el límite de MusicBrainz sin registrarse. */
        const val EMPTY_PAGE_SKIP_DELAY_MS = 1_100L
    }

    private val _state = MutableStateFlow<SuggestionsUiState>(SuggestionsUiState.Loading)
    val state: StateFlow<SuggestionsUiState> = _state.asStateFlow()

    /** Qué se buscó la primera vez, para poder repetir la búsqueda con [retry] sin que quien
     * llame tenga que volver a pasar los argumentos. */
    private var lastQuery: Triple<SuggestionKind, String, String>? = null

    /** `offset` que hay que mandar en la próxima página (ver [MusicBrainzApi.SearchPage]). Vive
     * aquí, no en el estado: solo hace falta para pedir la siguiente página, nunca para pintar. */
    private var nextOffset = 0

    /**
     * Lanza la búsqueda la PRIMERA vez que se llama; si el sistema recrea el diálogo (p. ej. al
     * girar la pantalla) y vuelve a llamar con los mismos argumentos, no la repite — mismo guard
     * que [MetadataEditorViewModel.setSongId].
     */
    fun search(kind: SuggestionKind, primaryQuery: String, secondaryQuery: String) {
        if (lastQuery != null) return
        lastQuery = Triple(kind, primaryQuery, secondaryQuery)
        runSearch()
    }

    /** Botón "Reintentar" del estado de error: repite la última búsqueda desde la primera página. */
    fun retry() = runSearch()

    private fun runSearch() {
        val (kind, primary, secondary) = lastQuery ?: return
        nextOffset = 0
        viewModelScope.launch {
            _state.value = SuggestionsUiState.Loading
            val result = runCatching { fetchAccumulating(kind, primary, secondary, offset = 0) }
            _state.value = result.fold(
                onSuccess = { page ->
                    nextOffset = page.nextOffset
                    if (page.suggestions.isEmpty()) {
                        // Vacía de verdad: o no queda página siguiente, o se agotaron los saltos
                        // de fetchAccumulating sin encontrar ni un candidato aprovechable.
                        SuggestionsUiState.Empty
                    } else {
                        SuggestionsUiState.Success(page.suggestions, page.hasMore)
                    }
                },
                onFailure = { SuggestionsUiState.Error }
            )
        }
    }

    /**
     * Pide la siguiente página y la añade al final de la lista ya mostrada — la llama el diálogo
     * al acercarse el usuario al final de la lista (scroll infinito). Se ignora si ya no queda
     * página siguiente, si ya hay una pidiéndose, o si el estado actual no es [SuggestionsUiState.
     * Success] (p. ej. si todavía está cargando la primera búsqueda).
     */
    fun loadMore() {
        val current = _state.value as? SuggestionsUiState.Success ?: return
        if (!current.hasMore || current.loadingMore) return
        val (kind, primary, secondary) = lastQuery ?: return

        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val result = runCatching { fetchAccumulating(kind, primary, secondary, offset = nextOffset) }
            _state.value = result.fold(
                onSuccess = { page ->
                    nextOffset = page.nextOffset
                    SuggestionsUiState.Success(
                        suggestions = current.suggestions + page.suggestions,
                        hasMore = page.hasMore
                    )
                },
                // Un fallo al cargar MÁS no debe tirar la lista que ya se veía: se deja la
                // página siguiente sin cargar (hasMore sigue en true) y el usuario puede volver a
                // acercarse al final para reintentarlo sin perder lo que ya tenía.
                onFailure = { current.copy(loadingMore = false) }
            )
        }
    }

    /**
     * Como el texto libre de [MusicBrainzApi.searchSongs]/[searchAlbums] no está restringido a
     * ningún campo, casi siempre trae algo aprovechable — pero una página entera puede quedarse en
     * cero candidatos tras filtrar (solo bootlegs/entrevistas, por ejemplo) aunque a MusicBrainz le
     * queden más páginas detrás. Sin esto, esa página vacía se enseñaría como si no hubiera más que
     * buscar (o, en [loadMore], como una lista sin más filas y sin ningún gesto que la reactive,
     * porque no hay nada que hacer scroll). Así que, mientras la página venga vacía y queden más,
     * se pide la siguiente automáticamente — hasta [MAX_EMPTY_PAGE_SKIPS] veces, y con un respiro
     * de [EMPTY_PAGE_SKIP_DELAY_MS] entre una y otra para no saltarse el límite de 1 petición por
     * segundo de MusicBrainz (ver la nota de ritmo en [MusicBrainzApi]).
     */
    private suspend fun fetchAccumulating(
        kind: SuggestionKind,
        primary: String,
        secondary: String,
        offset: Int
    ): MusicBrainzApi.SearchPage {
        var page = fetchPage(kind, primary, secondary, offset)
        var skips = 0
        while (page.suggestions.isEmpty() && page.hasMore && skips < MAX_EMPTY_PAGE_SKIPS) {
            delay(EMPTY_PAGE_SKIP_DELAY_MS)
            page = fetchPage(kind, primary, secondary, page.nextOffset)
            skips++
        }
        return page
    }

    private suspend fun fetchPage(
        kind: SuggestionKind,
        primary: String,
        secondary: String,
        offset: Int
    ) = if (kind == SuggestionKind.SONG) {
        MusicBrainzApi.searchSongs(title = primary, artist = secondary, offset = offset)
    } else {
        MusicBrainzApi.searchAlbums(album = primary, artist = secondary, offset = offset)
    }

    /** Se piden los géneros SOLO del candidato que el usuario elige, en una petición aparte: ver
     * [MusicBrainzApi.fetchGenres] para el motivo (no gastar peticiones en candidatos que ni se
     * miran, y respetar el límite de 1 petición/segundo de MusicBrainz). */
    suspend fun genresOf(suggestion: MetadataSuggestion): List<String> =
        runCatching { MusicBrainzApi.fetchGenres(suggestion.kind, suggestion.genreEntityId) }
            .getOrDefault(emptyList())
}
