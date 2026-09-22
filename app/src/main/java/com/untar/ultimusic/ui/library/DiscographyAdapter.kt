package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.TimeFormat

/**
 * Lista plana de la discografía: cada [DiscographyAlbumUi] se aplana en una fila de cabecera
 * (carátula + "Título - Año") seguida de una fila por cada una de sus pistas — mismo patrón de
 * "filas de dos tipos" que [TagsAdapter], sin `DiffUtil` porque la lista entera se sustituye de
 * golpe con cada carga (nunca se edita fila a fila).
 *
 * El color de cada fila (blanco si está en el dispositivo, gris si no, ver
 * [DiscographyViewModel.crossReference]) es la única lógica visual que tiene esta pantalla: no hay
 * clic ni menú, es de solo lectura.
 */
class DiscographyAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_ALBUM = 0
        const val VIEW_TYPE_TRACK = 1
    }

    private sealed interface Row {
        data class AlbumRow(val album: DiscographyAlbumUi) : Row
        data class TrackRow(val track: DiscographyTrackUi) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(albums: List<DiscographyAlbumUi>) {
        rows = albums.flatMap { album ->
            listOf(Row.AlbumRow(album)) + album.tracks.map { Row.TrackRow(it) }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.AlbumRow) VIEW_TYPE_ALBUM else VIEW_TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ALBUM) {
            AlbumViewHolder(inflater.inflate(R.layout.item_discography_album, parent, false))
        } else {
            TrackViewHolder(inflater.inflate(R.layout.item_discography_track, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.AlbumRow -> (holder as AlbumViewHolder).bind(row.album)
            is Row.TrackRow -> (holder as TrackViewHolder).bind(row.track)
        }
    }

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.discographyAlbumCover)
        private val title: TextView = itemView.findViewById(R.id.discographyAlbumTitle)

        fun bind(album: DiscographyAlbumUi) {
            val context = itemView.context
            title.text = album.album.year?.let { "${album.album.title} - $it" } ?: album.album.title
            title.setTextColor(
                ContextCompat.getColor(context, if (album.isOnDevice) R.color.um_on_background else R.color.um_on_surface_muted)
            )
            cover.load(album.album.coverUrl, CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }
        }
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.discographyTrackNumber)
        private val title: TextView = itemView.findViewById(R.id.discographyTrackTitle)
        private val duration: TextView = itemView.findViewById(R.id.discographyTrackDuration)

        fun bind(track: DiscographyTrackUi) {
            val context = itemView.context
            val color = ContextCompat.getColor(
                context,
                if (track.isOnDevice) R.color.um_on_background else R.color.um_on_surface_muted
            )
            number.text = track.track.trackNumber.takeIf { it > 0 }?.toString() ?: "-"
            title.text = track.track.title
            duration.text = track.track.durationMs?.let { TimeFormat.mmss(it) }.orEmpty()
            number.setTextColor(color)
            title.setTextColor(color)
            duration.setTextColor(color)
        }
    }
}
