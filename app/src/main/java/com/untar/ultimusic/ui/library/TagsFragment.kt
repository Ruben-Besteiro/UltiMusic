package com.untar.ultimusic.ui.library

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.untar.ultimusic.R
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * Pestaña de Etiquetas, entre Géneros y Listas (ver [com.untar.ultimusic.ui.MainPagerAdapter]).
 * Calcada de [GenresFragment] en la lista, pero cada fila se pinta como una "salchicha" de color
 * (ver [TagsAdapter]/item_tag.xml) en vez de texto plano, y al pinchar una se abre la misma ficha
 * intermedia que Géneros/Listas (ver [CollectionDetailDialogFragment.showTag]). A diferencia de
 * Géneros/Artistas/Productores, lleva su propio layout ([R.layout.fragment_tags], no el
 * `fragment_library_list.xml` compartido) porque tiene un botón "+" flotante para crear una
 * etiqueta personalizada, igual que [com.untar.ultimusic.ui.playlists.PlaylistsFragment].
 */
class TagsFragment : Fragment(R.layout.fragment_tags) {

    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val createButton = view.findViewById<FloatingActionButton>(R.id.btnCreateTag)
        emptyView.setText(R.string.no_tags)

        val adapter = TagsAdapter(
            onTagClick = { tag -> CollectionDetailDialogFragment.showTag(this, tag.id) },
            onEditTag = { tag -> showEditTagDialog(tag) },
            onDeleteTag = { tag -> showDeleteTagDialog(tag) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(tagsViewModel.visibleTags.value.getOrNull(position)?.name)
        }

        createButton.setOnClickListener {
            TagEditorDialogFragment.newCreate().show(parentFragmentManager, TagEditorDialogFragment.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tagsViewModel.visibleTags.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                // El amarillo dinámico del botón "+" y de la barra de scroll sigue el color de lo
                // que suena (ver PlaylistsFragment, mismo patrón): a diferencia del color propio de
                // CADA etiqueta (fijo, elegido por el usuario, ver TagsAdapter), el FAB en sí es
                // chrome de la app, así que sí le aplica la regla de "amarillo dinámico".
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        createButton.backgroundTintList = ColorStateList.valueOf(accent)
                        AccentTint.contentOnAccent(createButton, accent)
                        scrollbar.setAccentColor(accent)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Por si se editó un archivo de lista desde fuera de la app (mismo motivo que
        // PlaylistsFragment.onResume/CollectionDetailDialogFragment.onResume): recalcula "En ninguna
        // lista", la única etiqueta predefinida que depende de las Listas.
        playlistsViewModel.refresh()
    }

    private fun showEditTagDialog(tag: TagSummary) {
        TagEditorDialogFragment.newEdit(tag).show(parentFragmentManager, TagEditorDialogFragment.TAG)
    }

    /** Confirmación antes de borrar; mismo patrón que `PlaylistsFragment.showDeleteDialog`. */
    private fun showDeleteTagDialog(tag: TagSummary) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.playlist_delete) // genérico ("Eliminar"), reutilizado tal cual
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.tag_delete_confirm), tag.name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.playlist_delete) { _, _ -> tagsViewModel.deleteTag(tag.id) }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }
}
