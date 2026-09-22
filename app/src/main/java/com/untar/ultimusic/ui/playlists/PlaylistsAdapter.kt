package com.untar.ultimusic.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.util.PlaylistHistoryStore
import com.untar.ultimusic.util.TimeFormat

/**
 * Lista de listas. Cada fila enseña el nombre, debajo "N canciones | duración" y, si ya sonó algo
 * de ella esta vuelta del ciclo "sin repeticiones" (ver [PlaylistHistoryStore]), "N reproducidas"
 * con un botón de reinicio justo al lado. El botón de 3 puntos abre el menú de renombrar/borrar. Es
 * como [com.untar.ultimusic.ui.library.PeopleAdapter] pero sin imagen, porque una lista no tiene
 * carátula propia.
 *
 * [PlaylistHistoryStore] no es un `StateFlow` -lo escribe `PlaybackService` sin avisar a nadie-, así
 * que la fila de "reproducidas" se relee a mano en cada [bind]; quien nos llame a [submit] con la
 * frecuencia adecuada (ver `PlaylistsFragment`, que además del propio listado de listas combina con
 * la canción actual) es lo único que la mantiene al día.
 */
class PlaylistsAdapter(
    private val onPlaylistClick: (PlaylistSummary) -> Unit,
    private val onRename: (PlaylistSummary) -> Unit,
    private val onDelete: (PlaylistSummary) -> Unit,
    private val onResetHistory: (PlaylistSummary) -> Unit
) : RecyclerView.Adapter<PlaylistsAdapter.PlaylistViewHolder>() {

    private var playlists: List<PlaylistSummary> = emptyList()

    fun submit(list: List<PlaylistSummary>) {
        playlists = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = playlists.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PlaylistViewHolder(inflater.inflate(R.layout.item_playlist, parent, false))
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position], onPlaylistClick, onRename, onDelete, onResetHistory)
    }

    class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.playlistName)
        private val subtitle: TextView = itemView.findViewById(R.id.playlistSubtitle)
        private val more: View = itemView.findViewById(R.id.btnPlaylistMore)
        private val historyRow: View = itemView.findViewById(R.id.playlistHistoryRow)
        private val historyText: TextView = itemView.findViewById(R.id.playlistHistoryText)
        private val btnResetHistory: ImageButton = itemView.findViewById(R.id.btnResetPlaylistHistory)

        fun bind(
            playlist: PlaylistSummary,
            onPlaylistClick: (PlaylistSummary) -> Unit,
            onRename: (PlaylistSummary) -> Unit,
            onDelete: (PlaylistSummary) -> Unit,
            onResetHistory: (PlaylistSummary) -> Unit
        ) {
            val context = itemView.context
            name.text = playlist.name

            val songs = context.resources.getQuantityString(
                R.plurals.song_count, playlist.songCount, playlist.songCount
            )
            subtitle.text = context.getString(
                R.string.playlist_subtitle_format, songs, TimeFormat.hhmmss(playlist.totalDuration)
            )

            val played = PlaylistHistoryStore.getHistory(playlist.name).size
            if (played == 0) {
                historyRow.visibility = View.GONE
            } else {
                historyRow.visibility = View.VISIBLE
                historyText.text = context.resources.getQuantityString(
                    R.plurals.playlist_played_count, played, played
                )
                btnResetHistory.setOnClickListener {
                    onResetHistory(playlist)
                    // Repintado inmediato: PlaylistHistoryStore no es un StateFlow, así que sin esto
                    // habría que esperar al próximo submit() para que la fila se oculte (regla del
                    // CLAUDE.md).
                    historyRow.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onPlaylistClick(playlist) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_playlist_item, menu)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_rename_playlist -> { onRename(playlist); true }
                            R.id.action_delete_playlist -> { onDelete(playlist); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }
}
