package com.untarlamanteca.ultimusic.ui.songs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Lista de canciones con dos tipos de fila:
 *  - posición 0: cabecera "Escoger al azar"
 *  - resto: una canción con dos carátulas (álbum y canción), info y menú de 3 puntos.
 */
class SongsAdapter(
    private val onShuffle: () -> Unit,
    private val onSongClick: (Song) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var songs: List<Song> = emptyList()

    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_SONG = 1
    }

    override fun getItemCount(): Int = songs.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == 0) VIEW_TYPE_HEADER else VIEW_TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_shuffle_header, parent, false))
        } else {
            SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(onShuffle)
            is SongViewHolder -> holder.bind(songs[position - 1], onSongClick)
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(onShuffle: () -> Unit) {
            itemView.setOnClickListener { onShuffle() }
        }
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.songSubtitle)
        private val more: View = itemView.findViewById(R.id.btnSongMore)

        fun bind(song: Song, onSongClick: (Song) -> Unit) {
            title.text = song.title

            val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
            val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
            subtitle.text = itemView.context.getString(R.string.song_subtitle_format, artist, album)

            val loader = CoverLoader.get(itemView.context)
            cover.load(CoverArt.cover(song), loader)

            itemView.setOnClickListener { onSongClick(song) }
            // El menú de 3 puntos no hace nada de momento.
            more.setOnClickListener { }
        }
    }
}
