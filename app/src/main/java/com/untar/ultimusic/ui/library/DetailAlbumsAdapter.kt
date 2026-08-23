package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader

/**
 * Carrusel horizontal de álbumes de la ficha de un artista: la misma tarjeta que [AlbumsAdapter]
 * pero en pequeño, con el año siempre en el subtítulo (el artista siempre es el mismo en esta
 * ficha, así que no aporta nada repetirlo).
 */
class DetailAlbumsAdapter(
    private val onAlbumClick: (AlbumSummary) -> Unit
) : RecyclerView.Adapter<DetailAlbumsAdapter.AlbumViewHolder>() {

    private var albums: List<AlbumSummary> = emptyList()

    fun submit(list: List<AlbumSummary>) {
        albums = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = albums.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return AlbumViewHolder(inflater.inflate(R.layout.item_detail_album_card, parent, false))
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(albums[position], onAlbumClick)
    }

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.albumCover)
        private val title: TextView = itemView.findViewById(R.id.albumTitle)
        private val subtitleYear: TextView = itemView.findViewById(R.id.albumSubtitleYear)

        fun bind(album: AlbumSummary, onAlbumClick: (AlbumSummary) -> Unit) {
            title.text = album.title
            subtitleYear.text = album.year?.toString().orEmpty()

            val context = itemView.context
            cover.load(CoverArt.cover(context, album.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onAlbumClick(album) }
        }
    }
}
