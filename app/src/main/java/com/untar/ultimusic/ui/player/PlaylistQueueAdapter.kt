package com.untar.ultimusic.ui.player

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.joinNonBlank
import java.util.Collections

/**
 * Lista de una colección mostrada dentro del iPod en **modo navegación** (donde se elige qué sonará
 * después): una lista o, desde que existe la pestaña de Géneros, también un género (ver
 * [com.untar.ultimusic.ui.player.IPodDialogFragment.enterBrowseMode]). Cada fila enseña
 * "Título | Artista". La fila seleccionada con las flechas del iPod se resalta, para que se vea
 * cuál reproduciría el botón de play.
 *
 * Solo una lista se puede reordenar arrastrando —tiene un orden propio guardado en su archivo—;
 * un género no ([reorderable] a `false`), así que esa colección oculta el manejador de arrastre.
 *
 * Si la canción que suena de verdad en la app (venga de esta colección o de otro sitio) está entre
 * estas, la fila se comporta como en [IPodQueueAdapter]: icono de altavoz en esa fila y números
 * relativos a ella en el resto ([setNowPlaying]). Si no, cada fila muestra su posición absoluta.
 */
class PlaylistQueueAdapter(
    private val onItemClick: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onReordered: (List<Song>) -> Unit,
    private val reorderable: Boolean = true
) : RecyclerView.Adapter<PlaylistQueueAdapter.RowViewHolder>() {

    private val songs: MutableList<Song> = mutableListOf()
    private var selectedIndex: Int = 0

    /** Ruta de archivo de la canción que suena de verdad en la app, o null si ninguna. */
    private var nowPlayingFilePath: String? = null

    /** Índice dentro de [songs] de [nowPlayingFilePath], o -1 si no está aquí. Se recalcula por ruta
     * (no por posición fija) para seguir siendo correcto tras reordenar con arrastre. */
    private var nowPlayingIndex: Int = -1

    /** Color de acento actual (icono de altavoz); por defecto el amarillo de la app. */
    private var accent: Int = DynamicColor.DEFAULT

    @SuppressLint("NotifyDataSetChanged")
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Song>, selected: Int) {
        songs.clear()
        songs.addAll(list)
        selectedIndex = selected
        recomputeNowPlayingIndex()
        notifyDataSetChanged()
    }

    /**
     * Cambia qué canción es la que suena de verdad, comparando por ruta de archivo, y repinta para
     * mostrar el icono de altavoz y las posiciones relativas a ella (o volver a posiciones absolutas
     * si [filePath] es null o no está en esta lista).
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setNowPlaying(filePath: String?) {
        if (filePath == nowPlayingFilePath) return
        nowPlayingFilePath = filePath
        recomputeNowPlayingIndex()
        notifyDataSetChanged()
    }

    private fun recomputeNowPlayingIndex() {
        val path = nowPlayingFilePath
        nowPlayingIndex = if (path == null) -1 else songs.indexOfFirst { it.filePath == path }
    }

    /** True si la canción que suena de verdad está en esta lista (para decidir el botón de menú). */
    fun containsNowPlaying(): Boolean = nowPlayingIndex >= 0

    /** Índice dentro de [songs] de la canción que suena de verdad, o -1 si ninguna. */
    fun nowPlayingIndex(): Int = nowPlayingIndex

    /** True si [position] es exactamente la fila que suena de verdad (para no reiniciarla al pulsar). */
    fun isRowNowPlaying(position: Int): Boolean = nowPlayingIndex >= 0 && position == nowPlayingIndex

    /** True si la fila del cursor ([selectedIndex]) es la que suena de verdad. */
    fun isSelectedNowPlaying(): Boolean = isRowNowPlaying(selectedIndex)

    /** Cambia la fila resaltada (al mover el cursor con las flechas) sin recargar toda la lista. */
    fun setSelected(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old in songs.indices) notifyItemChanged(old)
        if (index in songs.indices) notifyItemChanged(index)
    }

    fun currentSongs(): List<Song> = songs.toList()

    /** Reordena en memoria mientras se arrastra. La persistencia se hace al soltar ([onReordered]). */
    fun moveItem(from: Int, to: Int) {
        Collections.swap(songs, from, to)
        recomputeNowPlayingIndex()
        notifyItemMoved(from, to)
    }

    /** Al soltar la fila arrastrada, avisa del nuevo orden para persistirlo. */
    fun commitReorder() = onReordered(songs.toList())

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_queue_song, parent, false)
        return RowViewHolder(view, onItemClick, onStartDrag)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(songs[position], position, position == selectedIndex, nowPlayingIndex, accent, reorderable)
    }

    class RowViewHolder(
        itemView: View,
        private val onItemClick: (Int) -> Unit,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.playlistQueueRow)
        private val speaker: ImageView = itemView.findViewById(R.id.queueSpeaker)
        private val number: TextView = itemView.findViewById(R.id.queueNumber)
        private val cover: ShapeableImageView = itemView.findViewById(R.id.queueCover)
        private val subtitle: TextView = itemView.findViewById(R.id.queueSubtitle)
        private val handle: ImageView = itemView.findViewById(R.id.dragHandle)

        /**
         * [nowPlayingIndex] = índice de la canción que suena de verdad en esta lista, o -1 si
         * ninguna. Con ella presente, esta fila se numera igual que la cola real: altavoz si es la
         * que suena, y el resto con su posición relativa (negativa y en gris si ya sonó). Sin ella,
         * cada fila muestra sencillamente su posición absoluta.
         */
        @SuppressLint("ClickableViewAccessibility")
        fun bind(
            song: Song,
            position: Int,
            selected: Boolean,
            nowPlayingIndex: Int,
            accent: Int,
            reorderable: Boolean
        ) {
            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
            subtitle.text = joinNonBlank(song.title, artist)
            cover.load(CoverArt.cover(itemView.context, song), CoverLoader.get(itemView.context))
            ImageViewCompat.setImageTintList(speaker, ColorStateList.valueOf(accent))

            if (nowPlayingIndex >= 0) {
                val offset = position - nowPlayingIndex
                val isNowPlaying = offset == 0
                speaker.isVisible = isNowPlaying
                number.isVisible = !isNowPlaying
                if (!isNowPlaying) number.text = offset.toString()   // negativas ya llevan el signo "-"
                val color = ContextCompat.getColor(
                    itemView.context,
                    if (offset < 0) R.color.um_on_surface_muted else R.color.um_on_background
                )
                number.setTextColor(color)
                subtitle.setTextColor(color)
            } else {
                speaker.isVisible = false
                number.isVisible = true
                number.text = (position + 1).toString()
                number.setTextColor(ContextCompat.getColor(itemView.context, R.color.um_on_surface_muted))
                subtitle.setTextColor(ContextCompat.getColor(itemView.context, R.color.um_on_background))
            }

            // La fila seleccionada se resalta con un fondo tenue; el resto, transparente.
            row.setBackgroundColor(
                if (selected) ContextCompat.getColor(itemView.context, R.color.um_divider)
                else 0x00000000
            )

            itemView.setOnClickListener { onItemClick(bindingAdapterPosition) }
            // Sin orden que guardar (género), no hay manejador que mostrar ni arrastre que arrancar.
            handle.isVisible = reorderable
            handle.setOnTouchListener { _, event ->
                if (reorderable && event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }
    }
}
