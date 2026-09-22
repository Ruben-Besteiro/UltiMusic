package com.untar.ultimusic.ui.preview

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.R
import com.untar.ultimusic.model.StoreLink
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * Diálogo con las tiendas donde se puede conseguir el archivo de una canción (ver
 * [StoreLinksViewModel]): lo abre la flecha hacia abajo de cada fila del buscador de fragmentos
 * ([PreviewSearchDialogFragment]). Solo salen las tiendas de las que MusicBrainz tiene un enlace, es
 * decir, donde la canción consta; si no hay ninguna, lo dice y ya (sin buscar el álbum ni nada
 * parecido). Al tocar una se cierra y abre [StoreWebDialogFragment] con esa página dentro de la app.
 *
 * Es un diálogo pequeño con `AlertDialog` (patrón de `SortDialogFragment`): como su vista la pone
 * `setView` y no `onCreateView`, la colección del estado se lanza aquí mismo en [onCreateDialog].
 */
class StoreLinksDialogFragment : DialogFragment() {

    private val viewModel: StoreLinksViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = layoutInflater.inflate(R.layout.dialog_store_links, null)
        val loading = content.findViewById<ProgressBar>(R.id.storeLinksLoading)
        val messageGroup = content.findViewById<View>(R.id.storeLinksMessageGroup)
        val message = content.findViewById<TextView>(R.id.storeLinksMessage)
        val retry = content.findViewById<MaterialButton>(R.id.storeLinksRetry)
        val list = content.findViewById<LinearLayout>(R.id.storeLinksList)

        retry.setOnClickListener { viewModel.retry() }
        viewModel.load(
            title = requireArguments().getString(ARG_TITLE).orEmpty(),
            artist = requireArguments().getString(ARG_ARTIST).orEmpty(),
            deezerTrackId = requireArguments().getLong(ARG_TRACK_ID)
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.store_links_title)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener { AccentTint.buttons(dialog, playerViewModel.accentColor.value) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        loading.isVisible = state is StoreLinksUiState.Loading
                        list.isVisible = state is StoreLinksUiState.Success
                        messageGroup.isVisible = state is StoreLinksUiState.Empty ||
                            state is StoreLinksUiState.Error ||
                            state is StoreLinksUiState.RateLimited
                        // Sin reintentar con un rate limit: es justo lo que no hay que hacer.
                        retry.isVisible = state is StoreLinksUiState.Error

                        when (state) {
                            is StoreLinksUiState.Success -> renderLinks(list, state.links)
                            StoreLinksUiState.Empty -> message.setText(R.string.store_links_empty)
                            StoreLinksUiState.Error -> message.setText(R.string.suggestions_error)
                            is StoreLinksUiState.RateLimited ->
                                message.text = getString(R.string.suggestions_rate_limited, state.retryInSeconds)
                            StoreLinksUiState.Loading -> Unit
                        }
                    }
                }
                // Regla del proyecto: todo lo amarillo usa el color dinámico.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        loading.indeterminateTintList = ColorStateList.valueOf(accent)
                        retry.backgroundTintList = ColorStateList.valueOf(accent)
                    }
                }
            }
        }
        return dialog
    }

    private fun renderLinks(container: LinearLayout, links: List<StoreLink>) {
        container.removeAllViews()
        for (link in links) {
            val row = layoutInflater.inflate(R.layout.item_store_link, container, false)
            row.findViewById<TextView>(R.id.storeName).text = link.name
            row.findViewById<View>(R.id.storeFree).isVisible = link.free
            row.setOnClickListener { openStore(link) }
            container.addView(row)
        }
    }

    private fun openStore(link: StoreLink) {
        val manager = requireActivity().supportFragmentManager
        dismiss()
        if (manager.findFragmentByTag(StoreWebDialogFragment.TAG) != null) return
        StoreWebDialogFragment.newInstance(link.name, link.url).show(manager, StoreWebDialogFragment.TAG)
    }

    companion object {
        const val TAG = "StoreLinksDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_ARTIST = "artist"
        private const val ARG_TRACK_ID = "trackId"

        /** [deezerTrackId] es el id de Deezer de la fila, para pedirle el ISRC. */
        fun newInstance(title: String, artist: String, deezerTrackId: Long) =
            StoreLinksDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_ARTIST to artist,
                    ARG_TRACK_ID to deezerTrackId
                )
            }
    }
}
