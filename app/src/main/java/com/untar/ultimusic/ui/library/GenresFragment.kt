package com.untar.ultimusic.ui.library

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.player.IPodDialogFragment
import kotlinx.coroutines.launch

/**
 * Pestaña de Géneros. Lista de solo texto, igual que [PeopleFragment] pero sin imagen (ver
 * item_genre.xml): un género no tiene ficha de detalle propia, solo nombre y cuántas canciones lo
 * llevan. Al pinchar uno se abre el iPod en modo navegación mostrando sus canciones para elegir
 * qué sonará, EXACTAMENTE igual que al pinchar una lista (ver
 * [IPodDialogFragment.newInstanceForGenre]), salvo que no se puede reordenar: un género no es una
 * lista propia del usuario, así que no hay orden que guardar.
 */
class GenresFragment : Fragment(R.layout.fragment_library_list) {

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        emptyView.setText(R.string.no_genres)

        val adapter = GenresAdapter(
            onGenreClick = { genre ->
                if (parentFragmentManager.findFragmentByTag("ipod") == null) {
                    IPodDialogFragment.newInstanceForGenre(genre.name)
                        .show(parentFragmentManager, "ipod")
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(libraryViewModel.genres.value.getOrNull(position)?.name)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    libraryViewModel.genres.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                // La barra de scroll sigue el amarillo dinámico de lo que suena, igual que en el
                // resto de pestañas.
                launch {
                    playerViewModel.accentColor.collect { accent -> scrollbar.setAccentColor(accent) }
                }
            }
        }
    }
}
