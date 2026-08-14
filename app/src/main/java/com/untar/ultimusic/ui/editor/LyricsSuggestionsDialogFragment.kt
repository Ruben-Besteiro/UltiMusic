package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
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
import com.untar.ultimusic.model.LyricsSuggestion
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Diálogo del buscador de letras de lrclib.net: busca por título+artista (a través de
 * [LyricsSuggestionsViewModel]) y deja al usuario elegir un candidato, que se devuelve a
 * [MetadataEditorDialogFragment] para que rellene el campo "Letra". Nunca guarda nada por su
 * cuenta: solo entrega datos.
 *
 * Esa búsqueda inicial es automática, con lo que ya haya en los campos de título/artista del
 * editor, pero puede no encontrar nada (mayúsculas, "feat.", erratas...). Por eso arriba del todo
 * hay una barra de búsqueda manual, prellenada con ese mismo texto, con la que el usuario puede
 * corregirlo y relanzar la búsqueda (botón de lupa o Enter, ver [runManualSearch]).
 *
 * Mismo patrón que [MetadataSuggestionsDialogFragment] (que a su vez sigue el de
 * [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]): un [DialogFragment] a pantalla
 * completa que devuelve su resultado con la API de resultados entre fragmentos
 * (`setFragmentResult`/`setFragmentResultListener`).
 *
 * A diferencia del de metadatos, aquí el resultado es un único texto (la letra elegida, ya
 * sincronizada o plana) en vez de un formulario entero de campos que volcar.
 */
class LyricsSuggestionsDialogFragment : DialogFragment() {

    private val viewModel: LyricsSuggestionsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Evita devolver dos veces el resultado si el usuario llega a tocar dos filas antes de que se
     * cierre el diálogo. */
    private var resultSent = false

    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var messageGroup: View
    private lateinit var message: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var searchButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_lyrics_suggestions, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.lyricsSuggestionsRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.lyricsSuggestionsToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        list = view.findViewById(R.id.lyricsSuggestionsList)
        loading = view.findViewById(R.id.lyricsSuggestionsLoading)
        messageGroup = view.findViewById(R.id.lyricsSuggestionsMessageGroup)
        message = view.findViewById(R.id.lyricsSuggestionsMessage)
        retryButton = view.findViewById(R.id.lyricsSuggestionsRetry)
        searchInput = view.findViewById(R.id.lyricsSuggestionsSearchInput)
        searchButton = view.findViewById(R.id.lyricsSuggestionsSearchButton)

        val adapter = LyricsSuggestionsAdapter { onSuggestionPicked(it) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        retryButton.setOnClickListener { viewModel.retry() }

        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val artist = requireArguments().getString(ARG_ARTIST).orEmpty()

        // Prellenada con lo que ya se buscó automáticamente, para que corregirlo sea editar en vez
        // de volver a escribirlo entero.
        searchInput.setText(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
        searchButton.setOnClickListener { runManualSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runManualSearch()
                true
            } else {
                false
            }
        }

        viewModel.search(title, artist, requireArguments().getLong(ARG_DURATION_MS))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> render(state, adapter) }
                }
                // Regla del proyecto: todo lo amarillo usa el color dinámico (ver
                // MetadataSuggestionsDialogFragment, de donde se copia este bloque).
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        loading.indeterminateTintList = ColorStateList.valueOf(accent)
                        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
                    }
                }
            }
        }
    }

    private fun render(state: LyricsSuggestionsUiState, adapter: LyricsSuggestionsAdapter) {
        loading.isVisible = state is LyricsSuggestionsUiState.Loading
        list.isVisible = state is LyricsSuggestionsUiState.Success
        messageGroup.isVisible = state is LyricsSuggestionsUiState.Empty ||
            state is LyricsSuggestionsUiState.Error ||
            state is LyricsSuggestionsUiState.RateLimited
        // Sin botón de reintentar con un rate limit: reintentar es justo lo que no hay que hacer, y
        // el freno lo denegaría igualmente sin llegar a salir a la red (ver RateLimitGuard).
        retryButton.isVisible = state is LyricsSuggestionsUiState.Error

        when (state) {
            is LyricsSuggestionsUiState.Success -> adapter.submit(state.suggestions)
            is LyricsSuggestionsUiState.Empty -> message.setText(R.string.lyrics_suggestions_empty)
            is LyricsSuggestionsUiState.Error -> message.setText(R.string.lyrics_suggestions_error)
            is LyricsSuggestionsUiState.RateLimited ->
                message.text = getString(R.string.suggestions_rate_limited, state.retryInSeconds)
            LyricsSuggestionsUiState.Loading -> Unit
        }
    }

    /** Lanza una búsqueda con el texto de la barra manual (botón de lupa o Enter en el teclado). */
    private fun runManualSearch() {
        val query = searchInput.text?.toString().orEmpty()
        if (query.isBlank()) return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        viewModel.searchManual(query)
    }

    /** La letra sincronizada gana siempre que exista; si no, se devuelve la plana (ver
     * [LyricsSuggestion.syncedLyrics]). */
    private fun onSuggestionPicked(suggestion: LyricsSuggestion) {
        if (resultSent) return
        resultSent = true
        setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_LYRICS to (suggestion.syncedLyrics ?: suggestion.plainLyrics))
        )
        dismiss()
    }

    companion object {
        /** Clave con la que el editor escucha el resultado. */
        const val RESULT_KEY = "lyrics_suggestions_result"
        const val RESULT_LYRICS = "lyrics"

        private const val ARG_TITLE = "title"
        private const val ARG_ARTIST = "artist"
        private const val ARG_DURATION_MS = "duration_ms"

        /** [durationMs] es la duración del archivo que se está editando, para marcar y subir en la
         * lista los candidatos que sean de la misma grabación (ver
         * [com.untar.ultimusic.data.remote.LrcLibApi.search]); 0 si se desconoce. */
        fun newInstance(title: String, artist: String, durationMs: Long) =
            LyricsSuggestionsDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_ARTIST to artist,
                    ARG_DURATION_MS to durationMs
                )
            }
    }
}
