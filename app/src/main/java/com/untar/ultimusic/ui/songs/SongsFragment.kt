package com.untar.ultimusic.ui.songs

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.SongsViewModel
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.CoverArt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Primer fragmento: lista de canciones. */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    private val songsViewModel: SongsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val loadingSpinner = view.findViewById<ProgressBar>(R.id.loadingSpinner)

        val adapter = SongsAdapter(
            onSongClick = { song -> playerViewModel.play(song) },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag("metadataEditor") == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, "metadataEditor")
                }
            },
            onDeleteSong = { song -> showDeleteDialog(song) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(songsViewModel.songs.value.getOrNull(position)?.title)
        }

        /** Pintamos las canciones en la pantalla **/
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Se combina con CoverArt.revision (ver su comentario) para que editar SOLO la
                    // carátula de una canción -reutilizando su nombre de archivo, el caso normal-
                    // repinte esta lista igual: sin esto, songsViewModel.songs no reemitiría nada
                    // porque el Song que sale de Room sería idéntico al de antes.
                    combine(songsViewModel.songs, CoverArt.revision) { list, _ -> list }.collect { list ->
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
                            emptyView.text = requireContext().getString(R.string.no_songs)
                            emptyView.visibility = View.VISIBLE
                        } else {
                            // La reconciliación puede terminar sin cambiar ninguna fila (biblioteca ya al
                            // día): el flujo `songs` no reemite y el otro collector nunca se dispara, así
                            // que hay que ocultar el aviso aquí también o se queda clavado en pantalla.
                            emptyView.visibility = View.GONE
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
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        scrollbar.setAccentColor(accent)
                        loadingSpinner.indeterminateTintList = ColorStateList.valueOf(accent)
                    }
                }
            }
        }
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
            val contained = repo.playlistsContainingAll(listOf(filename))
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(listOf(filename), names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                songsViewModel.delete(song)
                playlistsViewModel.forgetSong(File(song.filePath).name)
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }
}
