package com.untar.ultimusic.ui.songs

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.joinNonBlank

/**
 * Lista de canciones simple.
 *
 * El menú de 3 puntos ofrece encolar, **añadir a lista**, ir al álbum/artista/productor (los
 * que tenga la canción) y editar metadatos.
 */
class SongsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onGoToAlbum: (Song) -> Unit,
    private val onGoToArtist: (Song) -> Unit,
    private val onGoToProducer: (Song) -> Unit
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
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
        val song = songs[position]
        holder.bind(song, onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onDeleteSong, onGoToAlbum, onGoToArtist, onGoToProducer)
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.songSubtitle)
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        fun bind(
            song: Song,
            onSongClick: (Song) -> Unit,
            onAddToQueue: (Song) -> Unit,
            onAddToPlaylist: (Song) -> Unit,
            onEditMetadata: (Song) -> Unit,
            onDeleteSong: (Song) -> Unit,
            onGoToAlbum: (Song) -> Unit,
            onGoToArtist: (Song) -> Unit,
            onGoToProducer: (Song) -> Unit
        ) {
            title.text = song.title

            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
            val album = song.albums.joinToString(", ") { it.title }.ifBlank { MusicScanner.UNKNOWN_ALBUM }
            subtitle.text = joinNonBlank(artist, album)

            val loader = CoverLoader.get(itemView.context)
            cover.load(CoverArt.cover(itemView.context, song), loader)

            itemView.setOnClickListener { onSongClick(song) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    // Solo tiene sentido "ir a" lo que la canción de verdad tenga.
                    menu.findItem(R.id.action_go_to_album)?.isVisible = song.albums.isNotEmpty()
                    menu.findItem(R.id.action_go_to_artist)?.isVisible = song.artists.isNotEmpty()
                    menu.findItem(R.id.action_go_to_producer)?.isVisible = song.producers.isNotEmpty()
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_queue -> { onAddToQueue(song); true }
                            R.id.action_add_to_playlist -> { onAddToPlaylist(song); true }
                            R.id.action_go_to_album -> { onGoToAlbum(song); true }
                            R.id.action_go_to_artist -> { onGoToArtist(song); true }
                            R.id.action_go_to_producer -> { onGoToProducer(song); true }
                            R.id.action_edit_metadata -> { onEditMetadata(song); true }
                            R.id.action_delete_song -> { onDeleteSong(song); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }
}
