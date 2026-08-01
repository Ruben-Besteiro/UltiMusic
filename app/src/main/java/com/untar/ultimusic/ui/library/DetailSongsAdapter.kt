package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.AlbumTrack
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.TimeFormat

/**
 * Lista de canciones de una ficha de detalle: número de pista a la izquierda, título y duración en
 * el centro y el menú de tres puntos a la derecha.
 *
 * No lleva carátula (al contrario que [com.untar.ultimusic.ui.songs.SongsAdapter]) porque
 * dentro de un álbum todas serían la misma imagen repetida; la carátula ya preside la cabecera.
 */
class DetailSongsAdapter(
    private val onSongClick: (Int) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit
) : RecyclerView.Adapter<DetailSongsAdapter.TrackViewHolder>() {

    private var tracks: List<AlbumTrack> = emptyList()

    fun submit(list: List<AlbumTrack>) {
        tracks = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return TrackViewHolder(inflater.inflate(R.layout.item_detail_song, parent, false))
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], position, onSongClick, onAddToQueue, onEditMetadata, onDeleteSong)
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.trackNumber)
        private val title: TextView = itemView.findViewById(R.id.trackTitle)
        private val duration: TextView = itemView.findViewById(R.id.trackDuration)
        private val more: View = itemView.findViewById(R.id.btnTrackMore)

        fun bind(
            track: AlbumTrack,
            position: Int,
            onSongClick: (Int) -> Unit,
            onAddToQueue: (Song) -> Unit,
            onEditMetadata: (Song) -> Unit,
            onDeleteSong: (Song) -> Unit
        ) {
            val song = track.song
            title.text = song.title
            duration.text = TimeFormat.mmss(song.duration)

            // En las fichas de artista/productor no hay número de pista: se oculta la columna en vez
            // de dejar un hueco vacío que descuadraría los títulos.
            number.isVisible = track.trackNumber != null
            number.text = track.trackNumber?.toString().orEmpty()

            // La posición en la lista, no el id: el reproductor necesita saber por dónde empezar
            // dentro de la colección para poder seguir con las siguientes.
            itemView.setOnClickListener { onSongClick(position) }

            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    // "Añadir a playlist" es exclusiva de la pestaña de Canciones (comparten menú).
                    menu.findItem(R.id.action_add_to_playlist)?.isVisible = false
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_queue -> { onAddToQueue(song); true }
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
