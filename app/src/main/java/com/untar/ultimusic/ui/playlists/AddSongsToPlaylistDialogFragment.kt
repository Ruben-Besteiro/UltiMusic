package com.untar.ultimusic.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.SongCheckboxAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Añadir varias canciones de golpe a una lista (botón "+" de
 * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment] en la ficha de una lista):
 * pantalla completa con un buscador y la lista de canciones que TODAVÍA no están en ella
 * ([PlaylistsViewModel.songsNotIn]), cada una con su casilla.
 *
 * Calco de [com.untar.ultimusic.ui.library.AddSongsToTagDialogFragment] -mismo layout
 * ([R.layout.dialog_add_songs]), mismo [SongCheckboxAdapter], mismo patrón de "marcar en LOCAL y
 * aplicar todo de golpe al pulsar el tick"-, pero volcando sobre [PlaylistsViewModel.addSongs] en
 * vez de [com.untar.ultimusic.ui.library.TagsViewModel.applyTagsToSongs]: una lista se guarda por
 * NOMBRE de archivo (ver [PlaylistRepository][com.untar.ultimusic.data.playlist.PlaylistRepository]),
 * no por id de canción, así que [checked] guarda ids de todas formas (para casar con
 * [SongCheckboxAdapter]/`songsNotIn`) y solo se convierten a nombre de archivo al aplicar.
 */
class AddSongsToPlaylistDialogFragment : DialogFragment() {

    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private val playlistName: String by lazy { requireArguments().getString(ARG_PLAYLIST_NAME).orEmpty() }

    private val query = MutableStateFlow("")

    /** Ids marcados, pendientes de aplicar al pulsar el tick (ver la cabecera de la clase). */
    private val checked = MutableStateFlow<Set<Long>>(emptySet())

    /** Snapshot de las canciones mostradas, para resolver [checked] a nombres de archivo al aplicar
     *  sin tener que volver a consultar nada (ver [applyAndClose]). */
    private var currentSongs: List<Song> = emptyList()

    private lateinit var adapter: SongCheckboxAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_add_songs, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.addSongsRoot)
        val bar = view.findViewById<View>(R.id.addSongsBar)
        val title = view.findViewById<TextView>(R.id.addSongsTitle)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseAddSongs)
        val btnConfirm = view.findViewById<ImageButton>(R.id.btnConfirmAddSongs)
        val searchInput = view.findViewById<EditText>(R.id.addSongsSearchInput)
        val list = view.findViewById<RecyclerView>(R.id.addSongsList)
        val emptyView = view.findViewById<TextView>(R.id.addSongsEmpty)

        title.text = getString(R.string.add_songs_to_playlist_title, playlistName)

        // La barra de estado es transparente en este tema, así que la barra de arriba se aparta ella
        // sola de la hora y la batería, igual que SettingsDialogFragment/MetadataEditorDialogFragment.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
            bar.updatePadding(top = bars.top)
            insets
        }

        adapter = SongCheckboxAdapter(onToggle = { song -> toggle(song.id) })
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        btnClose.setOnClickListener { dismiss() }
        btnConfirm.setOnClickListener { applyAndClose() }
        searchInput.doAfterTextChanged { text -> query.value = text?.toString().orEmpty() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playlistsViewModel.songsNotIn(playlistName, query).collect { songs ->
                        currentSongs = songs
                        adapter.submit(songs)
                        emptyView.isVisible = songs.isEmpty()
                    }
                }
                launch {
                    checked.collect { ids -> adapter.setChecked(ids) }
                }
                launch {
                    playerViewModel.accentColor.collect { accent -> adapter.setAccentColor(accent) }
                }
            }
        }
    }

    private fun toggle(songId: Long) {
        checked.value = checked.value.toMutableSet().apply {
            if (!add(songId)) remove(songId)
        }
    }

    /** Aplica de golpe lo marcado ([PlaylistsViewModel.addSongs], resolviendo ids a nombres de
     *  archivo contra [currentSongs]) y cierra. Sin nada marcado no hace ninguna escritura de más. */
    private fun applyAndClose() {
        val ids = checked.value
        if (ids.isNotEmpty()) {
            val filenames = currentSongs.filter { it.id in ids }.map { File(it.filePath).name }
            playlistsViewModel.addSongs(playlistName, filenames)
        }
        dismiss()
    }

    companion object {
        private const val ARG_PLAYLIST_NAME = "playlistName"
        const val TAG = "addSongsToPlaylist"

        fun newInstance(playlistName: String): AddSongsToPlaylistDialogFragment =
            AddSongsToPlaylistDialogFragment().apply {
                arguments = bundleOf(ARG_PLAYLIST_NAME to playlistName)
            }
    }
}
