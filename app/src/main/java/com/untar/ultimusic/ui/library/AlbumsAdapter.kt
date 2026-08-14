package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.joinNonBlank

/**
 * Rejilla de álbumes: una carátula cuadrada grande con el título y el artista debajo. El número de
 * columnas no lo decide el adaptador, sino el `GridLayoutManager` que le pone [AlbumsFragment].
 */
class AlbumsAdapter(
    private val onAlbumClick: (AlbumSummary) -> Unit
) : RecyclerView.Adapter<AlbumsAdapter.AlbumViewHolder>() {

    private var albums: List<AlbumSummary> = emptyList()

    fun submit(list: List<AlbumSummary>) {
        albums = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = albums.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return AlbumViewHolder(inflater.inflate(R.layout.item_album_card, parent, false))
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(albums[position], onAlbumClick)
    }

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.albumCover)
        private val title: TextView = itemView.findViewById(R.id.albumTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.albumSubtitle)
        private val songCount: TextView = itemView.findViewById(R.id.albumSongCount)

        fun bind(album: AlbumSummary, onAlbumClick: (AlbumSummary) -> Unit) {
            val context = itemView.context
            title.text = album.title
            // El año ya viene con su propio fallback resuelto en SQL (el más tardío de las
            // canciones del álbum si no hay uno puesto a mano, ver LibraryDao.observeAlbumSummaries).
            subtitle.text = joinNonBlank(album.artistName ?: MusicScanner.UNKNOWN_ARTIST, album.year?.toString())
            songCount.text = context.resources.getQuantityString(
                R.plurals.song_count, album.songCount, album.songCount
            )
            cover.load(CoverArt.cover(context, album.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onAlbumClick(album) }
        }
    }
}
