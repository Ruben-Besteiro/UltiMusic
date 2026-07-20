package com.untarlamanteca.ultimusic.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song

/**
 * Lista de la cola 1 que se muestra dentro de la pantalla del iPod. La canción actual enseña el
 * icono de altavoz; el resto muestran su posición **relativa** a la actual: las ya reproducidas con
 * número negativo y en gris; las próximas con número positivo. Al pinchar una fila se salta a ella.
 */
class IPodQueueAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<IPodQueueAdapter.QueueViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var queue1Size: Int = 0

    /** [queue1Size] = nº de canciones de la cola 1 (las primeras de [list]); el resto son cola 2. */
    fun submit(list: List<Song>, current: Int, queue1Size: Int) {
        songs = list
        currentIndex = current
        this.queue1Size = queue1Size
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        // El divisor va tras la última fila de la cola 1, si hay cola 2 debajo.
        val showDivider = position == queue1Size - 1 && position < songs.size - 1
        holder.bind(songs[position], position - currentIndex, showDivider)
    }

    class QueueViewHolder(
        itemView: View,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val speaker: ImageView = itemView.findViewById(R.id.queueSpeaker)
        private val number: TextView = itemView.findViewById(R.id.queueNumber)
        private val subtitle: TextView = itemView.findViewById(R.id.queueSubtitle)
        private val divider: View = itemView.findViewById(R.id.queueDivider)

        /** [offset] = posición relativa a la actual: 0 = suena ahora, <0 = ya sonó, >0 = próxima. */
        fun bind(song: Song, offset: Int, showDivider: Boolean) {
            val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
            subtitle.text = itemView.context.getString(
                R.string.queue_subtitle_format, song.title, artist
            )

            val isCurrent = offset == 0
            speaker.isVisible = isCurrent
            number.isVisible = !isCurrent
            if (!isCurrent) number.text = offset.toString()   // negativas ya llevan el signo "-"

            // Las ya reproducidas (negativas) se atenúan en gris; actual y próximas en blanco.
            val color = ContextCompat.getColor(
                itemView.context,
                if (offset < 0) R.color.um_on_surface_muted else R.color.um_on_background
            )
            number.setTextColor(color)
            subtitle.setTextColor(color)

            divider.isVisible = showDivider

            itemView.setOnClickListener { onItemClick(bindingAdapterPosition) }
        }
    }
}
