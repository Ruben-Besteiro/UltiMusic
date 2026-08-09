package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ProgressBar
import android.widget.TextView
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
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Diálogo de sugerencias del autorrelleno de metadatos: busca en MusicBrainz (a través de
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

    /** Evita devolver dos veces el resultado si el usuario llega a tocar dos filas antes de que
     * se cierre el diálogo (p. ej. mientras se están pidiendo los géneros de la primera). */
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

        val adapter = MetadataSuggestionsAdapter { onSuggestionPicked(it) }
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

        val kind = SuggestionKind.valueOf(requireArguments().getString(ARG_KIND)!!)
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
        messageGroup.isVisible = state is SuggestionsUiState.Empty || state is SuggestionsUiState.Error
        retryButton.isVisible = state is SuggestionsUiState.Error

        when (state) {
            is SuggestionsUiState.Success -> adapter.submit(state.suggestions, state.loadingMore)
            is SuggestionsUiState.Empty -> message.setText(R.string.suggestions_empty)
            is SuggestionsUiState.Error -> message.setText(R.string.suggestions_error)
            SuggestionsUiState.Loading -> Unit
        }
    }

    /**
     * Al elegir una fila, se piden sus géneros (única petición aparte, ver
     * [MetadataSuggestionsViewModel.genresOf]) y SOLO ENTONCES se devuelve el resultado completo:
     * así el editor recibe los géneros ya listos en la misma entrega, en vez de tener que pedirlos
     * él por su cuenta.
     */
    private fun onSuggestionPicked(suggestion: MetadataSuggestion) {
        if (resultSent) return
        resultSent = true
        loading.isVisible = true
        list.isVisible = false
        messageGroup.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val genres = viewModel.genresOf(suggestion)
            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    RESULT_TITLE to suggestion.title,
                    RESULT_ARTIST to suggestion.artist,
                    RESULT_ALBUM to suggestion.albumTitle.orEmpty(),
                    RESULT_YEAR to (suggestion.year ?: NO_VALUE),
                    RESULT_GENRES to genres.joinToString(", "),
                    RESULT_TRACK_NUMBER to (suggestion.trackNumber ?: NO_VALUE),
                    RESULT_DISC_NUMBER to (suggestion.discNumber ?: NO_VALUE),
                    RESULT_COVER_URL to suggestion.coverUrl.orEmpty(),
                    RESULT_COUNTRY to suggestion.country.orEmpty()
                )
            )
            dismiss()
        }
    }

    companion object {
        /** Filas antes del final desde las que ya se pide la página siguiente, para que llegue a
         * tiempo (cargarla de golpe) antes de que el usuario alcance el final de verdad y vea un
         * hueco en blanco esperando la respuesta de MusicBrainz. */
        private const val LOAD_MORE_THRESHOLD = 5

        /** Clave con la que el editor escucha el resultado. */
        const val RESULT_KEY = "metadata_suggestions_result"
        const val RESULT_TITLE = "title"
        const val RESULT_ARTIST = "artist"
        const val RESULT_ALBUM = "album"
        const val RESULT_YEAR = "year"

        /** Géneros ya unidos por ", " — el mismo separador que usan los campos multivalor de los
         * editores, para poder volcarlos tal cual en el `EditText` de géneros. */
        const val RESULT_GENRES = "genres"
        const val RESULT_TRACK_NUMBER = "trackNumber"
        const val RESULT_DISC_NUMBER = "discNumber"
        const val RESULT_COVER_URL = "coverUrl"
        const val RESULT_COUNTRY = "country"

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
