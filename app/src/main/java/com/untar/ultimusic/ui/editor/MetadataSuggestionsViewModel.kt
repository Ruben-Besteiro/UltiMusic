package com.untar.ultimusic.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.GeniusApi
import com.untar.ultimusic.data.remote.ItunesApi
import com.untar.ultimusic.data.remote.retryInSeconds
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

    /** [hasMore] dice si a iTunes le queda otra página después de esta (ver
     * [ItunesApi.SearchPage]); [loadingMore] es la página siguiente pidiéndose de fondo,
     * mientras las ya cargadas se siguen viendo (a diferencia de [Loading], que tapa la lista
     * entera y solo es para la búsqueda inicial). */
    data class Success(
        val suggestions: List<MetadataSuggestion>,
        val hasMore: Boolean,
        val loadingMore: Boolean = false
    ) : SuggestionsUiState

    data object Empty : SuggestionsUiState
    data object Error : SuggestionsUiState

    /** iTunes nos ha limitado las peticiones. Se separa de [Error] porque "espera un minuto" y
     *  "comprueba tu conexión" piden cosas distintas al usuario: con el mensaje genérico se pondría
     *  a darle a Reintentar, que es justo lo que NO ayuda. [retryInSeconds] es cuánto queda. */
    data class RateLimited(val retryInSeconds: Long) : SuggestionsUiState
}

/**
 * Busca candidatos de autorrelleno en iTunes para [MetadataSuggestionsDialogFragment]. Sirve
 * tanto para canciones (busca por título+artista) como para álbumes (busca por álbum+artista);
 * cuál de las dos toca lo decide [SuggestionKind], que llega desde el diálogo.
 *
 * Es un [ViewModel] normal, no un `AndroidViewModel`: a diferencia de [MetadataEditorViewModel] no
 * necesita ningún `Context` de Android, solo llama a [ItunesApi] (que tampoco lo necesita).
 */
class MetadataSuggestionsViewModel : ViewModel() {

    private companion object {
        /** Cuántas páginas vacías, como mucho, se saltan solas antes de rendirse (ver
         * [fetchAccumulating]). */
        const val MAX_EMPTY_PAGE_SKIPS = 4

        /** Respiro entre dos páginas encadenadas. Apple no publica un límite oficial, pero
         * recomienda no pasar de unas 20 peticiones por minuto; este caso (saltarse solo varias
         * páginas seguidas) es el único de la app que puede encadenarlas sin que medie un gesto del
         * usuario, así que es el único que necesita frenarse. */
        const val EMPTY_PAGE_SKIP_DELAY_MS = 1_000L
    }

    private val _state = MutableStateFlow<SuggestionsUiState>(SuggestionsUiState.Loading)
    val state: StateFlow<SuggestionsUiState> = _state.asStateFlow()

    /** Qué se buscó la primera vez, para poder repetir la búsqueda con [retry] sin que quien
     * llame tenga que volver a pasar los argumentos. */
    private var lastQuery: Triple<SuggestionKind, String, String>? = null

    /** `offset` que hay que mandar en la próxima página (ver [ItunesApi.SearchPage]). Vive
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
                onFailure = { error ->
                    if (error is ApiRateLimitException) {
                        SuggestionsUiState.RateLimited(error.retryInSeconds())
                    } else {
                        SuggestionsUiState.Error
                    }
                }
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
                //
                // Con un rate limit sí se corta el scroll infinito (hasMore = false): si no, cada
                // acercamiento al final volvería a pedir la misma página que acaban de denegarnos,
                // en bucle y mientras el usuario siga arrastrando. Lo ya cargado se queda intacto.
                onFailure = { error ->
                    current.copy(
                        loadingMore = false,
                        hasMore = if (error is ApiRateLimitException) false else current.hasMore
                    )
                }
            )
        }
    }

    /**
     * Una página entera puede quedarse en cero candidatos tras deduplicar (todos los resultados de
     * la misma colección, ver [ItunesApi.searchSongs]) aunque a iTunes le queden más páginas
     * detrás. Sin esto, esa página vacía se enseñaría como si no hubiera más que buscar (o, en
     * [loadMore], como una lista sin más filas y sin ningún gesto que la reactive, porque no hay
     * nada que hacer scroll). Así que, mientras la página venga vacía y queden más, se pide la
     * siguiente automáticamente — hasta [MAX_EMPTY_PAGE_SKIPS] veces, y con un respiro de
     * [EMPTY_PAGE_SKIP_DELAY_MS] entre una y otra.
     */
    private suspend fun fetchAccumulating(
        kind: SuggestionKind,
        primary: String,
        secondary: String,
        offset: Int
    ): ItunesApi.SearchPage {
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
        ItunesApi.searchSongs(title = primary, artist = secondary, offset = offset)
    } else {
        ItunesApi.searchAlbums(album = primary, artist = secondary, offset = offset)
    }

    /**
     * Lo que Genius sepa del candidato que el usuario ha ELEGIDO, para rellenar los campos que
     * iTunes no conoce (productores, canción original de un remix y videoclip — la carátula propia
     * de la canción se pide aparte y para TODAS las filas, ver [coverArtOverride]). Ver [GeniusApi]
     * sobre por qué las dos fuentes se reparten así el trabajo.
     *
     * Esto sí se pide solo para el candidato elegido, nunca para los de la lista: son dos peticiones
     * más (buscar y luego la ficha completa) y no tendría sentido gastarlas en veinticinco candidatos
     * que el usuario ni va a mirar, a diferencia de la carátula, que sale ya en la búsqueda.
     *
     * Devuelve null y ya está si no hay token, si falla la red o si Genius no tiene esa canción
     * (ver [GeniusApi.detailsFor]) — el autorrelleno se queda entonces exactamente como quedaría
     * solo con iTunes, nunca peor.
     *
     * En una sugerencia de ÁLBUM no se llama siquiera: Genius no busca álbumes, y ninguno de los
     * campos que aporta (productores, `og*`, vídeo) existe en el editor de álbum.
     */
    suspend fun enrich(kind: SuggestionKind, suggestion: MetadataSuggestion): GeniusApi.SongDetails? {
        if (kind != SuggestionKind.SONG) return null
        return GeniusApi.detailsFor(title = suggestion.title, artist = suggestion.artist)
    }

    /** Caché de [coverArtOverride] por "título|artista": evita repetir la petición a Genius cuando
     * el scroll recicla una fila que ya se había resuelto. Vive en el ViewModel (no en el adaptador)
     * porque sobrevive a que Android recree la vista de la lista, y en un simple `Map` porque solo
     * hace falta mientras dure este diálogo, no entre búsquedas distintas. */
    private val coverArtCache = mutableMapOf<String, String?>()

    /** Si hay token de Genius utilizable. Lo mira el diálogo para decidir si las filas esperan a
     *  Genius o enseñan la miniatura de iTunes directamente (ver [usesGenius]
     *  [MetadataSuggestionsAdapter]): sin token, [coverArtOverride] devolvería null siempre y esperar
     *  solo metería un parpadeo de spinner por fila a cambio de nada. */
    val geniusAvailable: Boolean get() = GeniusApi.isConfigured

    /**
     * Carátula que debe enseñar la fila de una sugerencia de canción en vez de la miniatura de
     * iTunes, o null si debe quedarse con esa.
     *
     * **La regla es la misma aquí y en [MetadataSuggestionsDialogFragment.coverUrlFor]**, y tiene que
     * seguir siéndolo: con token de Genius manda la carátula de Genius, y sin él la de iTunes. Si las
     * dos discreparan, el usuario elegiría una fila viendo una carátula y el editor acabaría con
     * otra distinta.
     *
     * Antes había aquí una excepción para los SINGLE (se daba por hecho que su carátula de iTunes ya
     * era la de la canción, y se ahorraba la petición). Se ha quitado porque incumplía esa regla: con
     * token válido, un single seguía enseñando la de iTunes. Ver [GeniusApi.songArtFor] sobre por qué
     * esto es una petición por fila y no dos.
     */
    suspend fun coverArtOverride(suggestion: MetadataSuggestion): String? {
        if (!GeniusApi.isConfigured) return null
        val key = "${suggestion.title}|${suggestion.artist}"
        coverArtCache[key]?.let { return it }
        if (coverArtCache.containsKey(key)) return null
        val art = runCatching { GeniusApi.songArtFor(suggestion.title, suggestion.artist) }.getOrNull()
        coverArtCache[key] = art
        return art
    }
}
