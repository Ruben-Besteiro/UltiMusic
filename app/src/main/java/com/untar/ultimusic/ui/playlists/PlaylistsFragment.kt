package com.untar.ultimusic.ui.playlists

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.untar.ultimusic.R
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.player.IPodNanoDialogFragment
import kotlinx.coroutines.launch

/**
 * Pestaña de listas de reproducción. Lista las playlists (archivos de `~/UltiMusic/Playlists`), deja
 * crearlas con el botón "+", renombrarlas/borrarlas desde el menú de 3 puntos de cada fila, y al
 * pinchar una abre el iPod en modo navegación mostrando su contenido sin reproducir nada.
 */
class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {

    private val viewModel: PlaylistsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPlaylists)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val createButton = view.findViewById<FloatingActionButton>(R.id.btnCreatePlaylist)

        val adapter = PlaylistsAdapter(
            onPlaylistClick = { playlist ->
                if (parentFragmentManager.findFragmentByTag("ipod") == null) {
                    IPodNanoDialogFragment.newInstance(playlist.name)
                        .show(parentFragmentManager, "ipod")
                }
            },
            onRename = { playlist -> showRenameDialog(playlist) },
            onDelete = { playlist -> showDeleteDialog(playlist) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(viewModel.playlists.value.getOrNull(position)?.name)
        }

        createButton.setOnClickListener { showCreateDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playlists.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                // El amarillo dinámico del botón "+" y de la barra de scroll sigue el color de lo
                // que suena.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        createButton.backgroundTintList = ColorStateList.valueOf(accent)
                        scrollbar.setAccentColor(accent)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Por si el usuario editó los archivos de playlist desde fuera de la app.
        viewModel.refresh()
    }

    /** Diálogo con un campo de texto para crear una playlist vacía. */
    private fun showCreateDialog() {
        showNameDialog(
            title = getString(R.string.playlist_create),
            initial = "",
            positive = getString(R.string.playlist_add_button)
        ) { name -> viewModel.create(name) }
    }

    /** Diálogo para renombrar, precargado con el nombre actual. */
    private fun showRenameDialog(playlist: PlaylistSummary) {
        showNameDialog(
            title = getString(R.string.playlist_rename),
            initial = playlist.name,
            positive = getString(R.string.dialog_ok)
        ) { name -> if (name != playlist.name) viewModel.rename(playlist.name, name) }
    }

    /** Confirmación antes de borrar; deja claro que no se tocan los archivos de audio. */
    private fun showDeleteDialog(playlist: PlaylistSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.playlist_delete)
            .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.playlist_delete) { _, _ -> viewModel.delete(playlist.name) }
            .show()
    }

    /**
     * Diálogo reutilizable de "escribe un nombre". El botón de aceptar se desactiva mientras el campo
     * esté vacío, para no crear/renombrar a una cadena en blanco.
     */
    private fun showNameDialog(
        title: String,
        initial: String,
        positive: String,
        onAccept: (String) -> Unit
    ) {
        val input = EditText(requireContext()).apply {
            setText(initial)
            setSelection(text.length)
            hint = getString(R.string.playlist_name_hint)
            setSingleLine()
        }
        val padding = (resources.displayMetrics.density * 20).toInt()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input, padding, padding / 2, padding, 0)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(positive) { _, _ -> onAccept(input.text.toString().trim()) }
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = input.text.isNotBlank()
            input.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
        }
        dialog.show()
    }
}
