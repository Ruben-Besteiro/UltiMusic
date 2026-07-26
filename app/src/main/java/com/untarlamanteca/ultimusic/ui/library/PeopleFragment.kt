package com.untarlamanteca.ultimusic.ui.library

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
import com.untarlamanteca.ultimusic.R
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                source.collect { list ->
                    adapter.submit(list)
                    emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
