package com.untar.ultimusic.ui.library

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
import androidx.fragment.app.Fragment
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
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Discografía oficial de un artista: todos sus álbumes y EPs (ver
 * [com.untar.ultimusic.data.remote.MusicBrainzApi]), con sus canciones en blanco si ya están en el
 * dispositivo o en gris si no (ver [DiscographyViewModel.crossReference]). Se abre desde el botón
 * de disco+lupa de la ficha de artista (ver [DetailDialogFragment], `menu_library_detail.xml`).
 *
 * De solo lectura, sin ningún resultado que devolver: a diferencia de
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment] (de donde se copia el andamiaje de
 * carga/vacío/error), aquí no hay nada que elegir, solo que consultar. Mismo patrón de pantalla
 * completa que el resto de diálogos de la aplicación.
 */
class DiscographyDialogFragment : DialogFragment() {

    private val viewModel: DiscographyViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

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
    ): View = inflater.inflate(R.layout.dialog_discography, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.discographyRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.discographyToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        val artistName = requireArguments().getString(ARG_ARTIST_NAME).orEmpty()
        val albumTitle = requireArguments().getString(ARG_ALBUM_TITLE)
        // Desde la ficha de un álbum (ver showForAlbum) el título de la ventana es el del propio
        // álbum, no "Discografía de <artista>": aquí no se está mirando todo su catálogo, solo ESTE
        // disco.
        toolbar.title = albumTitle ?: getString(R.string.discography_title, artistName)

        list = view.findViewById(R.id.discographyList)
        loading = view.findViewById(R.id.discographyLoading)
        messageGroup = view.findViewById(R.id.discographyMessageGroup)
        message = view.findViewById(R.id.discographyMessage)
        retryButton = view.findViewById(R.id.discographyRetry)

        val adapter = DiscographyAdapter()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        retryButton.setOnClickListener { viewModel.retry() }

        viewModel.load(requireArguments().getLong(ARG_ARTIST_ID), artistName, albumTitle)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> render(state, adapter) }
                }
                // Regla del proyecto: todo lo amarillo usa el color dinámico (ver
                // LyricsSuggestionsDialogFragment, de donde se copia este bloque).
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        loading.indeterminateTintList = ColorStateList.valueOf(accent)
                        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
                    }
                }
            }
        }
    }

    private fun render(state: DiscographyUiState, adapter: DiscographyAdapter) {
        loading.isVisible = state is DiscographyUiState.Loading
        list.isVisible = state is DiscographyUiState.Success
        messageGroup.isVisible = state is DiscographyUiState.Empty || state is DiscographyUiState.Error
        retryButton.isVisible = state is DiscographyUiState.Error

        val isAlbumScoped = requireArguments().getString(ARG_ALBUM_TITLE) != null
        when (state) {
            is DiscographyUiState.Success -> adapter.submit(state.albums)
            DiscographyUiState.Empty -> message.setText(
                if (isAlbumScoped) R.string.discography_empty_album else R.string.discography_empty
            )
            DiscographyUiState.Error -> message.setText(R.string.discography_error)
            DiscographyUiState.Loading -> Unit
        }
    }

    companion object {
        private const val TAG = "discography"
        private const val ARG_ARTIST_ID = "artist_id"
        private const val ARG_ARTIST_NAME = "artist_name"
        private const val ARG_ALBUM_TITLE = "album_title"

        fun show(from: Fragment, artistId: Long, artistName: String) =
            showInternal(from, artistId, artistName, albumTitle = null)

        /** Igual que [show], pero acotado a un único álbum de ese artista (ver el botón de
         *  discografía de la ficha de álbum en [DetailDialogFragment]). */
        fun showForAlbum(from: Fragment, artistId: Long, artistName: String, albumTitle: String) =
            showInternal(from, artistId, artistName, albumTitle)

        private fun showInternal(from: Fragment, artistId: Long, artistName: String, albumTitle: String?) {
            if (from.childFragmentManager.findFragmentByTag(TAG) != null) return
            DiscographyDialogFragment().apply {
                arguments = bundleOf(
                    ARG_ARTIST_ID to artistId,
                    ARG_ARTIST_NAME to artistName,
                    ARG_ALBUM_TITLE to albumTitle
                )
            }.show(from.childFragmentManager, TAG)
        }
    }
}
