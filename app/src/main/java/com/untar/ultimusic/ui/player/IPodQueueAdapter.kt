package com.untar.ultimusic.ui.player

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.joinNonBlank

/**
 * Cola de reproducción tal cual está: la canción actual enseña el icono de altavoz; el resto
 * muestran su posición **relativa** a la actual: las ya reproducidas con número negativo y en gris
 * (historial); las próximas (encoladas a mano) con número positivo. Al pinchar una fila se salta a
 * ella (ver [PlayerViewModel.jumpTo]).
 */
class IPodQueueAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<IPodQueueAdapter.QueueViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var currentIndex: Int = 0

    /**
     * Si se muestra el divisor de "final de cola" en la última fila. Lo decide
     * [IPodDialogFragment.refreshEndDivider] mirando si la cola llena la pantalla o no: con hueco
     * en blanco debajo de la última fila, el divisor aclara que ahí se acaba de verdad la cola.
     */
    private var showEndDivider = false

    /** Color de acento actual (icono de altavoz); por defecto el amarillo de la app. */
    private var accent: Int = DynamicColor.DEFAULT

    fun submit(list: List<Song>, current: Int) {
        songs = list
        currentIndex = current
        notifyDataSetChanged()
    }

    /** Ver [showEndDivider]. Solo repinta la última fila, que es la única que puede cambiar. */
    fun setShowEndDivider(show: Boolean) {
        if (show == showEndDivider) return
        showEndDivider = show
        if (songs.isNotEmpty()) notifyItemChanged(songs.lastIndex)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val showDivider = showEndDivider && position == songs.lastIndex
        holder.bind(songs[position], position - currentIndex, showDivider, accent)
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
        fun bind(song: Song, offset: Int, showDivider: Boolean, accent: Int) {
            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
            subtitle.text = joinNonBlank(song.title, artist)

            val isCurrent = offset == 0
            speaker.isVisible = isCurrent
            ImageViewCompat.setImageTintList(speaker, ColorStateList.valueOf(accent))
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
