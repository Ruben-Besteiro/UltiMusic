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

/**
 * Pestañas de Artistas y de Productores. Es **un solo fragmento para las dos** porque artistas y
 * productores se tratan exactamente igual: misma lista, mismo adaptador, misma ficha de detalle.
 *
 * Cuál de las dos es se decide con un argumento ([ARG_KIND]) que se pasa en el `Bundle` al crearlo,
 * que es la forma correcta de parametrizar un fragmento: sus argumentos sobreviven a que el sistema
 * lo destruya y lo recree (al girar la pantalla, por ejemplo), cosa que una propiedad normal no
 * haría.
 */
class PeopleFragment : Fragment(R.layout.fragment_library_list) {

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Qué pestaña somos: la de artistas o la de productores. */
    private val kind: PersonKind
        get() = PersonKind.valueOf(requireArguments().getString(ARG_KIND)!!)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)

        val kind = this.kind
        emptyView.setText(
            if (kind == PersonKind.ARTIST) R.string.no_artists else R.string.no_producers
        )

        val adapter = PeopleAdapter(
            onPersonClick = { person -> DetailDialogFragment.showPerson(this, kind, person.id) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val source = when (kind) {
            PersonKind.ARTIST -> libraryViewModel.artists
            PersonKind.PRODUCER -> libraryViewModel.producers
        }
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

    companion object {
        private const val ARG_KIND = "kind"

        fun newInstance(kind: PersonKind): PeopleFragment = PeopleFragment().apply {
            arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
        }
    }
}

/**
 * Las dos clases de "persona" de la biblioteca. Se guarda por su nombre (`name`) en el Bundle y no
 * por su posición, para que reordenar el enum algún día no cambie el significado de lo guardado.
 */
enum class PersonKind { ARTIST, PRODUCER }
