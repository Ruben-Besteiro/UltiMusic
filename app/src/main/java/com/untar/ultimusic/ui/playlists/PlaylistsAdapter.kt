package com.untar.ultimusic.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.util.TimeFormat

/**
 * Lista de playlists. Cada fila enseña el nombre y, debajo, "N canciones | duración". El botón de 3
 * puntos abre el menú de renombrar/borrar. Es como [com.untar.ultimusic.ui.library.PeopleAdapter]
 * pero sin imagen, porque una playlist no tiene carátula propia.
 */
class PlaylistsAdapter(
    private val onPlaylistClick: (PlaylistSummary) -> Unit,
    private val onRename: (PlaylistSummary) -> Unit,
    private val onDelete: (PlaylistSummary) -> Unit
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
        holder.bind(playlists[position], onPlaylistClick, onRename, onDelete)
    }

    class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.playlistName)
        private val subtitle: TextView = itemView.findViewById(R.id.playlistSubtitle)
        private val more: View = itemView.findViewById(R.id.btnPlaylistMore)

        fun bind(
            playlist: PlaylistSummary,
            onPlaylistClick: (PlaylistSummary) -> Unit,
            onRename: (PlaylistSummary) -> Unit,
            onDelete: (PlaylistSummary) -> Unit
        ) {
            val context = itemView.context
            name.text = playlist.name

            val songs = context.resources.getQuantityString(
                R.plurals.song_count, playlist.songCount, playlist.songCount
            )
            subtitle.text = context.getString(
                R.string.playlist_subtitle_format, songs, TimeFormat.hhmmss(playlist.totalDuration)
            )

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
