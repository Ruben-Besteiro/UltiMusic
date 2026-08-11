package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.ApiUnauthorizedException
import com.untar.ultimusic.data.remote.GeniusApi
import com.untar.ultimusic.data.remote.ItunesApi
import com.untar.ultimusic.data.remote.retryInSeconds
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Diálogo de sugerencias del autorrelleno de metadatos: busca en iTunes (a través de
 * [MetadataSuggestionsViewModel]) y deja al usuario elegir un candidato, que se devuelve a quien
 * abrió el diálogo — [MetadataEditorDialogFragment] o [AlbumEditorDialogFragment] — para que
 * rellene sus campos. Nunca guarda nada por su cuenta: solo entrega datos.
 *
 * Mismo patrón que [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]: un [DialogFragment]
 * a pantalla completa que devuelve su resultado con la API de resultados entre fragmentos
 * (`setFragmentResult`/`setFragmentResultListener`), la forma recomendada de que dos fragmentos se
 * comuniquen sin que uno guarde una referencia directa al otro (esa referencia se quedaría
 * colgando si el sistema recrea la ventana, por ejemplo al girar la pantalla).
 *
 * El resultado viaja en un [Bundle] de solo tipos primitivos (texto y números) en vez de mandar el
 * propio [MetadataSuggestion]: el proyecto no tiene instalado el plugin `kotlin-parcelize`
 * (necesario para poder meter una `data class` en un `Bundle` tal cual), y no compensa añadirlo
 * solo para este resultado.
 */
class MetadataSuggestionsDialogFragment : DialogFragment() {

    private val viewModel: MetadataSuggestionsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Si se buscan canciones o álbumes. Decide qué se busca en iTunes y si se le pregunta a
     * Genius al elegir (ver [MetadataSuggestionsViewModel.enrich]). */
    private val kind: SuggestionKind by lazy {
        SuggestionKind.valueOf(requireArguments().getString(ARG_KIND)!!)
    }

    /** Evita devolver dos veces el resultado si el usuario llega a tocar dos filas en el mismo
     * instante, antes de que el diálogo termine de cerrarse. */
    private var resultSent = false

    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var messageGroup: View
    private lateinit var message: TextView
    private lateinit var retryButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_metadata_suggestions, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.suggestionsRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.suggestionsToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        list = view.findViewById(R.id.suggestionsList)
        loading = view.findViewById(R.id.suggestionsLoading)
        messageGroup = view.findViewById(R.id.suggestionsMessageGroup)
        message = view.findViewById(R.id.suggestionsMessage)
        retryButton = view.findViewById(R.id.suggestionsRetry)

        // Genius solo entra en juego en sugerencias de CANCIÓN y con token utilizable; en cualquier
        // otro caso la carátula es la de iTunes y se sabe ya, sin esperar a nada (ver la cabecera
        // del adaptador y MetadataSuggestionsViewModel.coverArtOverride).
        val adapter = MetadataSuggestionsAdapter(
            usesGenius = kind == SuggestionKind.SONG && viewModel.geniusAvailable,
            scope = viewLifecycleOwner.lifecycleScope,
            coverOverride = { suggestion -> viewModel.coverArtOverride(suggestion) },
            onPicked = { onSuggestionPicked(it) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        retryButton.setOnClickListener { viewModel.retry() }

        // Scroll infinito: al acercarse a las últimas filas (mientras se baja, dy > 0) se pide la
        // página siguiente. loadMore() ya se protege solo contra llamadas repetidas (mira
        // hasMore/loadingMore antes de pedir nada), así que no hace falta ningún guard aquí.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val itemCount = recyclerView.adapter?.itemCount ?: 0
                if (lastVisible >= itemCount - LOAD_MORE_THRESHOLD) viewModel.loadMore()
            }
        })

        viewModel.search(
            kind,
            requireArguments().getString(ARG_PRIMARY_QUERY).orEmpty(),
            requireArguments().getString(ARG_SECONDARY_QUERY).orEmpty()
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> render(state, adapter) }
                }
                // El spinner de carga y el botón de "Reintentar" salen por defecto de
                // colorPrimary (amarillo fijo, ver themes.xml); la regla del proyecto es que
                // todo lo amarillo use el color dinámico, así que se tiñen igual que
                // SongsFragment.loadingSpinner / los botones filled del resto de la app (ver
                // AccentTint.kt).
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        loading.indeterminateTintList = ColorStateList.valueOf(accent)
                        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
                        adapter.setAccent(accent)
                    }
                }
            }
        }
    }

    private fun render(state: SuggestionsUiState, adapter: MetadataSuggestionsAdapter) {
        loading.isVisible = state is SuggestionsUiState.Loading
        list.isVisible = state is SuggestionsUiState.Success
        messageGroup.isVisible = state is SuggestionsUiState.Empty ||
            state is SuggestionsUiState.Error ||
            state is SuggestionsUiState.RateLimited
        // Sin botón de reintentar con un rate limit: reintentar es justo lo que no hay que hacer, y
        // el freno lo denegaría igualmente sin llegar a salir a la red (ver RateLimitGuard).
        retryButton.isVisible = state is SuggestionsUiState.Error

        when (state) {
            is SuggestionsUiState.Success -> adapter.submit(state.suggestions, state.loadingMore)
            is SuggestionsUiState.Empty -> message.setText(R.string.suggestions_empty)
            is SuggestionsUiState.Error -> message.setText(R.string.suggestions_error)
            is SuggestionsUiState.RateLimited ->
                message.text = getString(R.string.suggestions_rate_limited, state.retryInSeconds)
            SuggestionsUiState.Loading -> Unit
        }
    }

    /**
     * Al elegir una fila se le pide a Genius lo que iTunes no sabe de esa canción (ver
     * [MetadataSuggestionsViewModel.enrich]) y SOLO ENTONCES se devuelve el resultado completo, para
     * que el editor lo reciba todo junto en la misma entrega. Mientras tanto se tapa la lista con el
     * spinner, porque son dos peticiones y se nota.
     *
     * Si Genius no está configurado o no encuentra la canción, `enrich` devuelve null y el resultado
     * es exactamente el que daría iTunes por su cuenta: este paso solo puede añadir campos, nunca
     * quitar ni empeorar los que ya había.
     *
     * De Genius solo se avisa de los dos fallos ante los que el usuario puede hacer algo (ver
     * [com.untar.ultimusic.data.remote.GeniusApi.detailsFor]), y **ninguno de los dos interrumpe la
     * entrega**: el candidato que eligió se aplica igual con lo que dio iTunes, porque su elección no
     * tiene la culpa de que Genius falle.
     * - **Rate limit**: un aviso pasajero ([android.widget.Toast]) diciendo cuánto esperar.
     * - **Token inválido**: no cabe en un Toast (hay que crear otro API Client), así que en vez de
     *   avisar aquí se marca [RESULT_GENIUS_TOKEN_INVALID] en el resultado y es el editor quien
     *   encadena los diálogos DESPUÉS de aplicar la sugerencia. Para cuando esto pasa, `GeniusApi` ya
     *   ha borrado el token guardado.
     */
    private fun onSuggestionPicked(suggestion: MetadataSuggestion) {
        if (resultSent) return
        resultSent = true
        loading.isVisible = true
        list.isVisible = false
        messageGroup.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            var tokenInvalid = false
            val details = try {
                viewModel.enrich(kind, suggestion)
            } catch (limited: ApiRateLimitException) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.suggestions_genius_rate_limited, limited.retryInSeconds()),
                    Toast.LENGTH_LONG
                ).show()
                null
            } catch (unauthorized: ApiUnauthorizedException) {
                tokenInvalid = true
                null
            }
            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    RESULT_TITLE to suggestion.title,
                    RESULT_ARTIST to artistsFor(suggestion, details),
                    RESULT_ALBUM to suggestion.albumTitle.orEmpty(),
                    RESULT_YEAR to (suggestion.year ?: NO_VALUE),
                    RESULT_GENRES to suggestion.genre.orEmpty(),
                    RESULT_TRACK_NUMBER to (suggestion.trackNumber ?: NO_VALUE),
                    RESULT_DISC_NUMBER to (suggestion.discNumber ?: NO_VALUE),
                    RESULT_COVER_URL to coverUrlFor(suggestion, details).orEmpty(),
                    RESULT_PRODUCERS to details?.producers?.joinToString(", ").orEmpty(),
                    RESULT_VIDEO_URL to details?.videoUrl.orEmpty(),
                    RESULT_OG_TITLE to details?.ogTitle.orEmpty(),
                    RESULT_OG_ARTIST to details?.ogArtist.orEmpty(),
                    RESULT_OG_ALBUM to details?.ogAlbum.orEmpty(),
                    RESULT_OG_YEAR to (details?.ogYear ?: NO_VALUE),
                    RESULT_GENIUS_TOKEN_INVALID to tokenInvalid
                )
            )
            dismiss()
        }
    }

    /**
     * Los artistas acreditados, separados por comas como espera el campo multivalor del editor.
     *
     * Se prefiere SIEMPRE la lista de Genius, aunque el resto de campos vengan de iTunes: Genius
     * los da uno a uno ([GeniusApi.SongDetails.artists]), mientras que iTunes acredita una única
     * cadena con todos pegados ("The Weeknd & ROSALÍA") que no hay forma de partir sin romper a los
     * grupos que llevan "&" en su propio nombre. Como el editor sustituye este campo por lo que
     * llegue aquí, esa diferencia decide si acaban tres artistas bien separados o un artista
     * inventado con el nombre de dos.
     *
     * Sin Genius (sin token, o canción que no tiene) se manda la cadena de iTunes tal cual: es lo
     * único que hay, y el usuario puede separarla a mano en el formulario antes de guardar.
     */
    private fun artistsFor(
        suggestion: MetadataSuggestion,
        details: GeniusApi.SongDetails?
    ): String {
        val fromGenius = details?.artists.orEmpty()
        return if (fromGenius.isNotEmpty()) fromGenius.joinToString(", ") else suggestion.artist
    }

    /**
     * Qué carátula se lleva el editor, ya en la resolución final (lista para descargarla tal cual,
     * sin que quien la reciba tenga que saber de qué fuente vino).
     *
     * La regla es simple y es **la misma que aplica la lista** (ver
     * [MetadataSuggestionsViewModel.coverArtOverride], que es quien pinta las filas): con token de
     * Genius manda la carátula de Genius, que es la de la canción concreta y no la del disco entero;
     * sin token —o si Genius no tiene arte propio para esa canción— manda la de iTunes, que siempre
     * está.
     *
     * Que las dos funciones coincidan no es cosmético: si discreparan, el usuario elegiría una fila
     * viendo una carátula y el editor acabaría rellenado con otra distinta.
     */
    private fun coverUrlFor(
        suggestion: MetadataSuggestion,
        details: GeniusApi.SongDetails?
    ): String? {
        val itunesCover = suggestion.coverUrl?.let { ItunesApi.fullResCoverUrl(it) }
        return details?.songArtUrl ?: itunesCover
    }

    companion object {
        /** Filas antes del final desde las que ya se pide la página siguiente, para que llegue a
         * tiempo (cargarla de golpe) antes de que el usuario alcance el final de verdad y vea un
         * hueco en blanco esperando la respuesta de iTunes. */
        private const val LOAD_MORE_THRESHOLD = 5

        /** Clave con la que el editor escucha el resultado. */
        const val RESULT_KEY = "metadata_suggestions_result"
        const val RESULT_TITLE = "title"
        const val RESULT_ARTIST = "artist"
        const val RESULT_ALBUM = "album"
        const val RESULT_YEAR = "year"

        /** El género del candidato, listo para volcarlo tal cual en el `EditText` de géneros.
         * iTunes solo da uno por publicación, así que aquí nunca hay varios que unir con el ", "
         * de los campos multivalor de los editores — la clave conserva el nombre en plural porque
         * el campo del editor sí admite varios, escritos a mano. */
        const val RESULT_GENRES = "genres"
        const val RESULT_TRACK_NUMBER = "trackNumber"
        const val RESULT_DISC_NUMBER = "discNumber"

        /** URL de la carátula **ya en resolución final**: quien la recibe solo tiene que
         * descargarla. Se resuelve aquí, y no en el editor, porque el tamaño se pide de forma
         * distinta según de qué fuente venga y solo este diálogo sabe cuál ganó (ver
         * [coverUrlFor]). */
        const val RESULT_COVER_URL = "coverUrl"

        /** Los cuatro campos que aporta Genius y que iTunes no conoce (ver [GeniusApi]). Llegan
         * vacíos, o con [NO_VALUE] en el año, cuando no hay token configurado, cuando Genius no
         * tiene la canción, o en una sugerencia de álbum (donde no se le pregunta). */
        const val RESULT_PRODUCERS = "producers"
        const val RESULT_VIDEO_URL = "videoUrl"
        const val RESULT_OG_TITLE = "ogTitle"
        const val RESULT_OG_ARTIST = "ogArtist"
        const val RESULT_OG_ALBUM = "ogAlbum"
        const val RESULT_OG_YEAR = "ogYear"

        /**
         * `true` si al enriquecer este candidato Genius rechazó el token por inválido. No es un dato
         * de la sugerencia: viaja aquí para que el editor pueda aplicar primero lo que sí se ha
         * conseguido y avisar después (ver [onSuggestionPicked] y `MetadataEditorDialogFragment`).
         */
        const val RESULT_GENIUS_TOKEN_INVALID = "geniusTokenInvalid"

        /** Centinela de "sin valor" para año/pista/disco en el [Bundle] del resultado: los reales
         * son siempre positivos, así que no hay ambigüedad posible. */
        const val NO_VALUE = -1

        private const val ARG_KIND = "kind"
        private const val ARG_PRIMARY_QUERY = "primaryQuery"
        private const val ARG_SECONDARY_QUERY = "secondaryQuery"

        /**
         * [primaryQuery]/[secondaryQuery] son título+artista para [SuggestionKind.SONG], o
         * álbum+artista para [SuggestionKind.ALBUM].
         */
        fun newInstance(
            kind: SuggestionKind,
            primaryQuery: String,
            secondaryQuery: String
        ) = MetadataSuggestionsDialogFragment().apply {
            arguments = bundleOf(
                ARG_KIND to kind.name,
                ARG_PRIMARY_QUERY to primaryQuery,
                ARG_SECONDARY_QUERY to secondaryQuery
            )
        }
    }
}
