package com.untarlamanteca.ultimusic.ui.library

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untarlamanteca.ultimusic.R
import kotlinx.coroutines.launch

/** Segunda pestaña: rejilla de álbumes. Al pulsar uno se abre su ficha ([DetailDialogFragment]). */
class AlbumsFragment : Fragment(R.layout.fragment_library_grid) {

    private val libraryViewModel: LibraryViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        emptyView.setText(R.string.no_albums)

        val adapter = AlbumsAdapter(
            onAlbumClick = { album -> DetailDialogFragment.showAlbum(this, album.id) }
        )
        // Dos columnas, como en el boceto. GridLayoutManager es el mismo RecyclerView de siempre,
        // solo que colocando los elementos en cuadrícula en vez de en una única columna.
        recycler.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                libraryViewModel.albums.collect { list ->
                    adapter.submit(list)
                    emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private companion object {
        const val GRID_COLUMNS = 2
    }
}
