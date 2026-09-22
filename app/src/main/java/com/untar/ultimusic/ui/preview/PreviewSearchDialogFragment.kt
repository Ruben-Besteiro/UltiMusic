package com.untar.ultimusic.ui.preview

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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.DeezerApi
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Buscador de fragmentos: lo abre el botón "?" de la barra superior (ver
 * `MainActivity.setupToolbar`). Busca canciones en Deezer (ver [PreviewSearchViewModel] y
 * [com.untar.ultimusic.data.remote.DeezerApi]) y, al tocar un resultado, reproduce su fragmento de
 * 30 s por streaming, sin necesidad de tener la canción en el dispositivo. No guarda ni entrega
 * nada: es solo para escuchar.
 *
 * Mismo esqueleto que [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]: diálogo a
 * pantalla completa, barra de búsqueda manual (botón de lupa o Enter) y lista con
 * carga/vacío/error, más el scroll infinito de
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment].
 */
class PreviewSearchDialogFragment : DialogFragment() {

    private val viewModel: PreviewSearchViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

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
    ): View = inflater.inflate(R.layout.dialog_preview_search, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /** Un fragmento no debe seguir sonando con la app en segundo plano. */
    override fun onStop() {
        super.onStop()
        viewModel.pause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.previewSearchRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.previewSearchToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        list = view.findViewById(R.id.previewSearchList)
        loading = view.findViewById(R.id.previewSearchLoading)
        messageGroup = view.findViewById(R.id.previewSearchMessageGroup)
        message = view.findViewById(R.id.previewSearchMessage)
        retryButton = view.findViewById(R.id.previewSearchRetry)
        searchInput = view.findViewById(R.id.previewSearchInput)
        searchButton = view.findViewById(R.id.previewSearchButton)

        val adapter = PreviewTrackAdapter(
            onTapped = { viewModel.onTrackTapped(it) },
            onStoresTapped = { showStores(it) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        retryButton.setOnClickListener { viewModel.retry() }

        searchButton.setOnClickListener { runSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }

        // Scroll infinito: mismo criterio que MetadataSuggestionsDialogFragment (loadMore() ya se
        // protege solo contra llamadas repetidas).
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val itemCount = recyclerView.adapter?.itemCount ?: 0
                if (lastVisible >= itemCount - LOAD_MORE_THRESHOLD) viewModel.loadMore()
            }
        })

        // El diálogo se abre para buscar: teclado a la vista solo la primera vez (no al girar).
        if (savedInstanceState == null) {
            searchInput.post {
                searchInput.requestFocus()
                requireContext().getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it, adapter) } }
                launch { viewModel.playback.collect { adapter.setPlayback(it) } }
                launch {
                    viewModel.playbackErrors.collect {
                        Toast.makeText(requireContext(), R.string.preview_playback_error, Toast.LENGTH_SHORT).show()
                    }
                }
                // Regla del proyecto: todo lo amarillo usa el color dinámico.
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

    private fun render(state: PreviewSearchUiState, adapter: PreviewTrackAdapter) {
        loading.isVisible = state is PreviewSearchUiState.Loading
        list.isVisible = state is PreviewSearchUiState.Success
        messageGroup.isVisible = state is PreviewSearchUiState.Idle ||
            state is PreviewSearchUiState.Empty ||
            state is PreviewSearchUiState.Error ||
            state is PreviewSearchUiState.RateLimited
        // Sin reintentar con un rate limit: es justo lo que no hay que hacer (ver RateLimitGuard).
        retryButton.isVisible = state is PreviewSearchUiState.Error

        when (state) {
            is PreviewSearchUiState.Success -> adapter.submit(state.tracks, state.loadingMore)
            PreviewSearchUiState.Idle -> message.setText(R.string.preview_search_idle)
            PreviewSearchUiState.Empty -> message.setText(R.string.suggestions_empty)
            PreviewSearchUiState.Error -> message.setText(R.string.suggestions_error)
            is PreviewSearchUiState.RateLimited ->
                message.text = getString(R.string.suggestions_rate_limited, state.retryInSeconds)
            PreviewSearchUiState.Loading -> Unit
        }
    }

    /** Diálogo de tiendas donde conseguir el archivo de [track]. Se cuelga de la actividad y no de
     * este fragmento (mismo criterio que el resto de diálogos encadenados de la app). */
    private fun showStores(track: DeezerApi.PreviewTrack) {
        val manager = requireActivity().supportFragmentManager
        if (manager.findFragmentByTag(StoreLinksDialogFragment.TAG) != null) return
        StoreLinksDialogFragment.newInstance(track.title, track.artist, track.id)
            .show(manager, StoreLinksDialogFragment.TAG)
    }

    private fun runSearch() {
        val query = searchInput.text?.toString().orEmpty()
        if (query.isBlank()) return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        viewModel.search(query)
    }

    companion object {
        const val TAG = "PreviewSearchDialog"

        /** Filas antes del final desde las que ya se pide la página siguiente. */
        private const val LOAD_MORE_THRESHOLD = 5

        fun newInstance() = PreviewSearchDialogFragment()
    }
}
