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
import com.untar.ultimusic.util.CoverArt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Pestaña de Artistas. */
class PeopleFragment : Fragment(R.layout.fragment_library_list) {

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        emptyView.setText(R.string.no_artists)

        val adapter = PeopleAdapter(
            onPersonClick = { person -> DetailDialogFragment.showArtist(this, person.id) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val source = libraryViewModel.artists
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(source.value.getOrNull(position)?.name)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Ver el comentario de CoverArt.revision (mismo motivo que en SongsFragment): la
                    // carátula de una persona sin imagen propia sale de sus canciones (ver
                    // GroupCoverFetcher en CoverArt.kt), así que editar solo esa carátula tampoco
                    // cambiaría nada en el PersonSummary sin esto.
                    combine(source, CoverArt.revision) { list, _ -> list }.collect { list ->
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
