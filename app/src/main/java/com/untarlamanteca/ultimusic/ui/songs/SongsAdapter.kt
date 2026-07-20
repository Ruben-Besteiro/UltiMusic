package com.untarlamanteca.ultimusic.ui.songs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverLoader

/**
 * Lista de canciones simple.
 */
class SongsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAddToQueue: (Song) -> Unit
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()

    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], onSongClick, onAddToQueue)
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.songSubtitle)
        private val more: View = itemView.findViewById(R.id.btnSongMore)

        fun bind(song: Song, onSongClick: (Song) -> Unit, onAddToQueue: (Song) -> Unit) {
            title.text = song.title

            val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
            val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
            subtitle.text = itemView.context.getString(R.string.song_subtitle_format, artist, album)

            val loader = CoverLoader.get(itemView.context)
            cover.load(CoverArt.cover(song), loader)

            itemView.setOnClickListener { onSongClick(song) }
            // El menú de 3 puntos ofrece «Añadir a cola de reproducción».
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    setOnMenuItemClickListener { item ->
                        if (item.itemId == R.id.action_add_to_queue) {
                            onAddToQueue(song)
                            true
                        } else false
                    }
                    show()
                }
            }
        }
    }
}
