package com.untar.ultimusic.ui.search

import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.PersonKind
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

private const val ANIM_DURATION_MS = 180L
private const val EDITOR_TAG = "metadataEditor"

/**
 * Convierte la barra de búsqueda de [com.untar.ultimusic.ui.MainActivity] en un buscador de verdad,
 * sin pasar por un diálogo aparte: antes tocar la lupa abría un `SearchDialogFragment` a pantalla
 * completa, con su propia caja de texto; ahora la caja ES la barra de la toolbar, y lo único que
 * cambia al "abrir" el buscador es qué hay debajo de ella.
 *
 * Al tocar la barra ([expand]):
 * - El engranaje, la ayuda y "más opciones" desaparecen (`GONE`); como la barra es el único hijo
 *   con `layout_weight` de esa fila, se estira sola para ocupar el hueco que dejan libre. El icono
 *   de la lupa pasa a ser una flecha "atrás" que colapsa el buscador.
 * - `browseContent` (pestañas + reproductor) se cambia por `searchResultsContainer` (la lista de
 *   resultados), igual que hacía el diálogo pero sin abrir una ventana nueva.
 * - El botón "atrás" del sistema queda enganchado para colapsar en vez de cerrar la Activity
 *   mientras el buscador esté abierto (ver [backCallback]).
 *
 * Las dos transiciones usan [TransitionManager] (de plataforma, no hace falta la librería
 * `androidx.transition`) para que el cambio de anchos y visibilidades se anime solo, en vez de dar
 * un salto brusco.
 *
 * Todo el filtrado real lo sigue haciendo [SearchViewModel]; esta clase solo pinta lo que él calcula
 * y decide qué pasa al pulsar cada resultado, exactamente igual que hacía el diálogo anterior
 * (canción: suena con las demás encontradas detrás; álbum/artista/productor: abre su ficha encima).
 */
class SearchBarController(
    private val activity: FragmentActivity,
    private val topBar: ViewGroup,
    private val btnSettings: View,
    private val btnHelp: View,
    private val btnMore: View,
    private val searchLeadingIcon: ImageView,
    private val searchInput: EditText,
    private val btnSearchClear: View,
    private val contentArea: ViewGroup,
    private val browseContent: View,
    private val searchResultsContainer: View,
    private val recycler: RecyclerView,
    private val emptyView: TextView,
    private val viewModel: SearchViewModel,
    private val playerViewModel: PlayerViewModel,
    private val lifecycleOwner: LifecycleOwner
) {
    private var expanded = false

    /** Habilitado solo mientras el buscador está abierto (ver [expand] / [collapse]). */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapse()
    }

    fun bind() {
        activity.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)

        val adapter = SearchAdapter(
            onSongClick = ::playFromResults,
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = ::showAddToPlaylist,
            onEditMetadata = { song ->
                if (activity.supportFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(activity.supportFragmentManager, EDITOR_TAG)
                }
            },
            onDeleteSong = ::showDeleteDialog,
            onGoToAlbum = { song ->
                song.albums.firstOrNull()?.let {
                    hideKeyboard()
                    DetailDialogFragment.showAlbum(activity, it.id)
                }
            },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let {
                    hideKeyboard()
                    DetailDialogFragment.showPerson(activity, PersonKind.ARTIST, it.id)
                }
            },
            onGoToProducer = { song ->
                song.producers.firstOrNull()?.let {
                    hideKeyboard()
                    DetailDialogFragment.showPerson(activity, PersonKind.PRODUCER, it.id)
                }
            },
            onAlbumClick = { album ->
                hideKeyboard()
                DetailDialogFragment.showAlbum(activity, album.id)
            },
            onPersonClick = { kind, person ->
                hideKeyboard()
                DetailDialogFragment.showPerson(activity, kind, person.id)
            }
        )
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        // Misma barra de scroll arrastrable que las pestañas, sin la burbuja de la letra: los
        // resultados no van en orden alfabético sino agrupados por secciones (ver ScrollbarDrag).
        val scrollbar = recycler.attachScrollbarDrag()

        // Tocar cualquier punto de la píldora (o la propia caja) expande el buscador y le da el
        // foco; la flecha/lupa de la izquierda, en cambio, colapsa cuando ya está expandido.
        searchInput.setOnClickListener { expand() }
        searchInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) expand() }
        searchLeadingIcon.setOnClickListener { if (expanded) collapse() else expand() }

        searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            viewModel.setQuery(query)
            btnSearchClear.isVisible = query.isNotEmpty()
        }
        btnSearchClear.setOnClickListener {
            searchInput.setText("")
            searchInput.requestFocus()
        }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Ver el comentario de CoverArt.revision: los resultados salen de las mismas
                    // listas de Canciones/Álbumes/Artistas/Productores, así que sufren la misma
                    // laguna si solo cambia una carátula reutilizando su nombre de archivo.
                    combine(viewModel.results, CoverArt.revision) { results, _ -> results }
                        .collect { results ->
                            adapter.submit(results)
                            showEmptyMessage(results.isEmpty())
                        }
                }
                // Los títulos de sección y la barra de scroll van con el color de la canción que
                // suena, como en las pestañas.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        adapter.setAccent(accent)
                        scrollbar.setAccentColor(accent)
                    }
                }
            }
        }
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        backCallback.isEnabled = true

        TransitionManager.beginDelayedTransition(topBar, AutoTransition().setDuration(ANIM_DURATION_MS))
        btnSettings.visibility = View.GONE
        btnHelp.visibility = View.GONE
        btnMore.visibility = View.GONE
        searchLeadingIcon.setImageResource(R.drawable.ic_arrow_back)
        searchLeadingIcon.contentDescription = activity.getString(R.string.search_back)

        TransitionManager.beginDelayedTransition(contentArea, AutoTransition().setDuration(ANIM_DURATION_MS))
        browseContent.visibility = View.GONE
        searchResultsContainer.visibility = View.VISIBLE

        showEmptyMessage(viewModel.results.value.isEmpty())
        searchInput.post { showKeyboard() }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        backCallback.isEnabled = false

        hideKeyboard()
        searchInput.clearFocus()
        searchInput.setText("") // dispara doAfterTextChanged -> viewModel.setQuery("")

        TransitionManager.beginDelayedTransition(topBar, AutoTransition().setDuration(ANIM_DURATION_MS))
        btnSettings.visibility = View.VISIBLE
        btnHelp.visibility = View.VISIBLE
        btnMore.visibility = View.VISIBLE
        searchLeadingIcon.setImageResource(R.drawable.ic_search)
        searchLeadingIcon.contentDescription = activity.getString(R.string.action_search)

        TransitionManager.beginDelayedTransition(contentArea, AutoTransition().setDuration(ANIM_DURATION_MS))
        searchResultsContainer.visibility = View.GONE
        browseContent.visibility = View.VISIBLE
    }

    /** Mensaje del hueco central: cambia según si aún no se ha escrito nada o si no hay resultados. */
    private fun showEmptyMessage(noResults: Boolean) {
        emptyView.isVisible = noResults
        if (!noResults) return

        val query = viewModel.query.value.trim()
        emptyView.text =
            if (query.isEmpty()) activity.getString(R.string.search_prompt)
            else activity.getString(R.string.search_no_results, query)
    }

    /**
     * Reproduce la canción pulsada dejando el resto de canciones encontradas detrás, en el mismo
     * orden en que se ven (igual que un álbum: `playCollection`, no `play`).
     */
    private fun playFromResults(song: Song) {
        val songs = viewModel.matchedSongs
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) playerViewModel.playCollection(songs, index)
    }

    /** Igual que en la pestaña «Canciones»: ver `SongsFragment.showAddToPlaylist`. */
    private fun showAddToPlaylist(song: Song) {
        if (activity.supportFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        lifecycleOwner.lifecycleScope.launch {
            val filename = File(song.filePath).name
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(listOf(filename))
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(listOf(filename), names, checked)
                .show(activity.supportFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(activity.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewModel.deleteSong(song)
                val filename = File(song.filePath).name
                lifecycleOwner.lifecycleScope.launch {
                    PlaylistRepository.get().removeSongFromAll(filename)
                }
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /** Recoge el teclado. Hace falta antes de abrir una ficha encima (álbum/artista/productor). */
    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showKeyboard() {
        searchInput.requestFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }
}
