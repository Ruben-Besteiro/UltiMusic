package com.untarlamanteca.ultimusic.ui.songs

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
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
import com.untarlamanteca.ultimusic.data.playlist.PlaylistRepository
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.ui.PlayerViewModel
import com.untarlamanteca.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untarlamanteca.ultimusic.ui.SongsViewModel
import com.untarlamanteca.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untarlamanteca.ultimusic.ui.playlists.PlaylistsViewModel
import kotlinx.coroutines.launch
import java.io.File

private fun Int.dpToPx(density: Float): Int = (this * density).toInt()

/** Primer fragmento: lista de canciones. */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    private val songsViewModel: SongsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)

        val adapter = SongsAdapter(
            onSongClick = { song -> playerViewModel.play(song, songsViewModel.songs.value) },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag("metadataEditor") == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, "metadataEditor")
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val density = resources.displayMetrics.density
        var isDraggingScrollbar = false
        recycler.setOnTouchListener { _, event ->
            val scrollbarWidth = 16.dpToPx(density)
            val isOnScrollbar = event.x >= recycler.width - scrollbarWidth

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isOnScrollbar) {
                        isDraggingScrollbar = true
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingScrollbar) {
                        val fraction = event.y / recycler.height
                        val layoutManager = recycler.layoutManager as LinearLayoutManager
                        val itemCount = adapter.itemCount
                        val visibleCount = layoutManager.childCount
                        val scrollTo = ((itemCount - visibleCount) * fraction).toInt().coerceAtLeast(0)
                        layoutManager.scrollToPosition(scrollTo)
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingScrollbar = false
                }
            }
            false
        }

        /** Pintamos las canciones en la pantalla **/
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    songsViewModel.songs.collect { list ->
                        adapter.submit(list)
                        if (list.isEmpty() && !songsViewModel.loading.value) {
                            emptyView.text = requireContext().getString(R.string.nothing_playing)
                            emptyView.visibility = View.VISIBLE
                        } else if (list.isNotEmpty()) {
                            emptyView.visibility = View.GONE
                        }
                    }
                }
                launch {
                    songsViewModel.loading.collect { loading ->
                        if (loading) {
                            emptyView.text = requireContext().getString(R.string.updating_database)
                            emptyView.visibility = View.VISIBLE
                        } else if (songsViewModel.songs.value.isEmpty()) {
                            emptyView.text = requireContext().getString(R.string.nothing_playing)
                            emptyView.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    songsViewModel.progress.collect { percent ->
                        if (songsViewModel.loading.value) {
                            emptyView.text = String.format(
                                requireContext().getString(R.string.updating_database_percent),
                                percent
                            )
                        }
                    }
                }
                // Qué canciones ya están en alguna playlist (para el resaltado de los 3 puntos).
                launch {
                    playlistsViewModel.songFilenamesInAnyPlaylist.collect { adapter.setMembership(it) }
                }
                // El amarillo dinámico del resaltado sigue el color de lo que suena.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        adapter.setAccent(accent)
                        updateScrollbarColor(recycler, accent)
                    }
                }
            }
        }
    }

    private fun updateScrollbarColor(recycler: RecyclerView, color: Int) {
        val thumb = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 6.dpToPx(resources.displayMetrics.density).toFloat()
        }
        recycler.verticalScrollbarThumbDrawable = thumb
    }

    /**
     * Carga (en una corrutina, porque toca disco) qué playlists existen y en cuáles ya está la
     * canción, y abre el diálogo de casillas de pertenencia con esos datos.
     */
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
}
