package com.untar.ultimusic.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import kotlinx.coroutines.launch
import java.io.File

/**
 * Buscador de la biblioteca: se escribe un texto y aparecen, en este orden, las **canciones**, los
 * **álbumes**, los **artistas** y los **productores** cuyo nombre lo contiene.
 *
 * Es un [DialogFragment] a pantalla completa, igual que la ficha de detalle o el editor de
 * metadatos: se abre encima de la pantalla principal sin cambiar de Activity, así que el
 * mini-reproductor sigue sonando y el botón "atrás" del sistema cierra la búsqueda y devuelve al
 * usuario justo a la pestaña en la que estaba.
 *
 * Todo el trabajo de filtrar lo hace [SearchViewModel]; aquí solo se pinta lo que él calcula y se
 * decide qué pasa al pulsar cada resultado:
 *
 * - **Canción**: suena, y las demás canciones encontradas quedan detrás en la cola (la búsqueda se
 *   comporta como una colección, igual que un álbum).
 * - **Álbum / artista / productor**: se abre su ficha de detalle encima del buscador, de modo que al
 *   cerrarla se vuelve a los resultados sin perderlos.
 */
class SearchDialogFragment : DialogFragment() {

    private val viewModel: SearchViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_SearchDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_search, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // El teclado se abre solo: si el usuario ha pulsado la lupa es para escribir, y obligarle a
        // tocar además la caja sería un paso de más.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.searchRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.searchToolbar)
        val input = view.findViewById<EditText>(R.id.searchInput)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSearch)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)

        // Se descuentan los huecos del sistema y, además, el del TECLADO (`ime`): sin eso la lista
        // seguiría llegando hasta abajo del todo y los últimos resultados quedarían tapados.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
            toolbar.updatePadding(top = bars.top)
            insets
        }

        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_search)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_search) {
                // El aspa vacía la caja; para salir del buscador está la flecha (y el botón atrás).
                input.setText("")
                input.requestFocus()
                true
            } else false
        }

        val adapter = SearchAdapter(
            onSongClick = { song -> playFromResults(song) },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, EDITOR_TAG)
                }
            },
            onDeleteSong = { song -> showDeleteDialog(song) },
            onAlbumClick = { album ->
                hideKeyboard(input)
                DetailDialogFragment.showAlbum(this, album.id)
            },
            onPersonClick = { kind, person ->
                hideKeyboard(input)
                DetailDialogFragment.showPerson(this, kind, person.id)
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // Misma barra de scroll arrastrable que las pestañas, pero SIN la burbuja de la letra: al no
        // pasarle `sectionFor`, `attachScrollbarDrag` ni la crea. Aquí una letra no diría nada,
        // porque los resultados no van en orden alfabético sino agrupados por secciones.
        val scrollbar = recycler.attachScrollbarDrag()

        input.doAfterTextChanged { text -> viewModel.setQuery(text?.toString().orEmpty()) }
        // La lupa del teclado no busca nada nuevo (los resultados ya se actualizan letra a letra):
        // simplemente recoge el teclado para dejar ver la lista entera.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(input)
                true
            } else false
        }
        input.requestFocus()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.results.collect { results ->
                        adapter.submit(results)
                        showEmptyMessage(emptyView, results.isEmpty())
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

    /**
     * Mensaje del hueco central: cambia según si aún no se ha escrito nada o si lo escrito no
     * encuentra nada. Si hay resultados, no se enseña ninguno.
     */
    private fun showEmptyMessage(emptyView: TextView, noResults: Boolean) {
        emptyView.isVisible = noResults
        if (!noResults) return

        val query = viewModel.query.value.trim()
        emptyView.text =
            if (query.isEmpty()) getString(R.string.search_prompt)
            else getString(R.string.search_no_results, query)
    }

    /**
     * Reproduce la canción pulsada dejando el resto de canciones encontradas detrás, en el mismo
     * orden en que se ven. Se usa `playCollection` (la lógica de álbumes y playlists) y no `play`
     * (la de la pestaña «Canciones»), porque una búsqueda es una selección deliberada: no tendría
     * sentido llenar la cola con toda la biblioteca mezclada.
     */
    private fun playFromResults(song: Song) {
        val songs = viewModel.matchedSongs
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) playerViewModel.playCollection(songs, index)
    }

    /** Igual que en la pestaña «Canciones»: ver `SongsFragment.showAddToPlaylist`. */
    private fun showAddToPlaylist(song: Song) {
        if (parentFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filename = File(song.filePath).name
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContaining(filename)
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(filename, names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(getString(R.string.delete_song_confirm, song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewModel.deleteSong(song)
                val filename = File(song.filePath).name
                viewLifecycleOwner.lifecycleScope.launch {
                    PlaylistRepository.get().removeSongFromAll(filename)
                }
            }
            .show()
    }

    /**
     * Recoge el teclado. Hace falta antes de abrir una ficha encima: si no, el teclado se quedaría
     * levantado sobre una pantalla que ya no tiene dónde escribir.
     */
    private fun hideKeyboard(input: EditText) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    companion object {
        const val TAG = "librarySearch"
        private const val EDITOR_TAG = "metadataEditor"
    }
}
