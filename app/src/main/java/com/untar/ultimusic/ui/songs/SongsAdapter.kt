package com.untar.ultimusic.ui.songs

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.ui.common.FlowLayout
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.bindSongSubtitle

/**
 * Lista de canciones simple.
 *
 * El menú de 3 puntos ofrece encolar, **añadir a lista**, ir al álbum/artista (los que tenga la
 * canción) y editar metadatos, para ESA canción sola; sigue ahí y funciona igual
 * aunque haya una selección múltiple en marcha (ver [selectedIds]), que tiene su propio menú de
 * 3 puntos en la toolbar para actuar sobre TODAS las marcadas a la vez (ver
 * [com.untar.ultimusic.ui.MainActivity]).
 *
 * Selección múltiple: mantener pulsada una fila la marca (y a partir de ahí, tocar cualquier fila
 * alterna su marca en vez de reproducir; ver [SongsFragment]). Se pinta con un velo y un check
 * sobre la carátula (ver item_song.xml), tiñendo el círculo del check con el acento dinámico como
 * manda el proyecto para todo lo amarillo (ver [AccentTint]).
 *
 * [setTags] añade una tercera línea con las etiquetas de cada canción en miniatura, siempre visible.
 *
 * La fila 0 es siempre la cabecera de resumen (ver [setSummary] e item_songs_header.xml): va
 * dentro de la lista -no como vista fija en fragment_songs.xml- para que haga scroll con el resto
 * en vez de quedarse flotando arriba. El resto de posiciones son canciones, desplazadas 1 respecto
 * a [songs].
 */
class SongsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onSongLongClick: (Song) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onEditTags: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onGoToAlbum: (Song) -> Unit,
    private val onGoToArtist: (Song) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SONG = 1
    }

    private var songs: List<Song> = emptyList()

    /** Ids de las canciones marcadas; no vacío = selección múltiple activa (ver [SongsFragment]). */
    private var selectedIds: Set<Long> = emptySet()

    private var accentColor: Int = 0xFFFFD000.toInt()

    /** Ver [setTags]: de dónde sacar las etiquetas de la tercera línea de cada fila. */
    private var tagsById: Map<Long, List<TagSummary>> = emptyMap()

    private var summaryText: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    /** Texto de la fila 0 (nº de canciones + duración total), ver SongsFragment. */
    fun setSummary(text: String) {
        if (summaryText == text) return
        summaryText = text
        notifyItemChanged(0)
    }

    /** Etiquetas de TODA la biblioteca de golpe (ver `LibraryRepository.songTagsById`), indexadas por
     *  id de canción: de ahí sale la tercera línea de cada fila, siempre visible. */
    @SuppressLint("NotifyDataSetChanged")
    fun setTags(byId: Map<Long, List<TagSummary>>) {
        if (tagsById == byId) return
        tagsById = byId
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelection(ids: Set<Long>) {
        if (selectedIds == ids) return
        selectedIds = ids
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        if (selectedIds.isNotEmpty()) notifyDataSetChanged()
    }

    // +1 por la cabecera de resumen, que va siempre en la posición 0 (ver comentario de la clase).
    override fun getItemCount(): Int = songs.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == 0) VIEW_TYPE_HEADER else VIEW_TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_songs_header, parent, false))
        } else {
            SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.bind(summaryText)
            return
        }
        val song = songs[position - 1]
        (holder as SongViewHolder).bind(
            song,
            selected = song.id in selectedIds,
            accentColor = accentColor,
            tags = tagsById[song.id].orEmpty(),
            onSongClick, onSongLongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onEditTags,
            onDeleteSong, onGoToAlbum, onGoToArtist
        )
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val summary: TextView = itemView.findViewById(R.id.songsSummary)
        fun bind(text: String) {
            summary.text = text
        }
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val selectionScrim: View = itemView.findViewById(R.id.selectionScrim)
        private val selectionCheck: ImageView = itemView.findViewById(R.id.selectionCheck)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitleArtist: TextView = itemView.findViewById(R.id.songSubtitleArtist)
        private val subtitleRest: TextView = itemView.findViewById(R.id.songSubtitleRest)
        private val youtubeIcon: ImageView = itemView.findViewById(R.id.songYoutubeIcon)
        private val youtubeViews: TextView = itemView.findViewById(R.id.songYoutubeViews)
        private val tagsContainer: FlowLayout = itemView.findViewById(R.id.songTagsContainer)
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        fun bind(
            song: Song,
            selected: Boolean,
            accentColor: Int,
            tags: List<TagSummary>,
            onSongClick: (Song) -> Unit,
            onSongLongClick: (Song) -> Unit,
            onAddToQueue: (Song) -> Unit,
            onAddToPlaylist: (Song) -> Unit,
            onEditMetadata: (Song) -> Unit,
            onEditTags: (Song) -> Unit,
            onDeleteSong: (Song) -> Unit,
            onGoToAlbum: (Song) -> Unit,
            onGoToArtist: (Song) -> Unit
        ) {
            title.text = song.title
            bindSongSubtitle(song, subtitleArtist, subtitleRest, youtubeIcon, youtubeViews)
            bindTags(tags)

            val loader = CoverLoader.get(itemView.context)
            cover.load(CoverArt.cover(itemView.context, song), loader)

            selectionScrim.visibility = if (selected) View.VISIBLE else View.GONE
            selectionCheck.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected) AccentTint.fill(itemView, R.id.selectionCheck, accentColor)

            // El menú de 3 puntos de la fila se queda tal cual, seleccionando o no: sigue actuando
            // sobre ESTA canción sola (ver la cabecera de la clase).
            itemView.setOnClickListener { onSongClick(song) }
            itemView.setOnLongClickListener { onSongLongClick(song); true }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    // Solo tiene sentido "ir a" lo que la canción de verdad tenga.
                    menu.findItem(R.id.action_go_to_album)?.isVisible = song.album != null
                    menu.findItem(R.id.action_go_to_artist)?.isVisible = song.artists.isNotEmpty()
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_queue -> { onAddToQueue(song); true }
                            R.id.action_add_to_playlist -> { onAddToPlaylist(song); true }
                            R.id.action_go_to_album -> { onGoToAlbum(song); true }
                            R.id.action_go_to_artist -> { onGoToArtist(song); true }
                            R.id.action_edit_metadata -> { onEditMetadata(song); true }
                            R.id.action_edit_tags -> { onEditTags(song); true }
                            R.id.action_delete_song -> { onDeleteSong(song); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }

        /** Tercera línea de la fila (ver item_song.xml/setTags): una "salchicha" en miniatura por
         *  etiqueta (item_tag_chip_mini.xml) dentro de un [FlowLayout] que las desborda a una línea
         *  nueva en vez de recortarlas si no caben todas, reinflada en cada bind porque el número de
         *  etiquetas cambia de una canción a otra. Oculta entera si la canción no tiene ninguna. */
        private fun bindTags(tags: List<TagSummary>) {
            tagsContainer.isVisible = tags.isNotEmpty()
            tagsContainer.removeAllViews()
            if (tags.isEmpty()) return
            val inflater = LayoutInflater.from(itemView.context)
            for (tag in tags) {
                val chip = inflater.inflate(R.layout.item_tag_chip_mini, tagsContainer, false) as TextView
                chip.text = tag.name
                // Colores FIJOS por etiqueta, no el acento dinámico: igual que en TagsAdapter, cada
                // etiqueta tiene su propio color de verdad, no le aplica la regla de amarillo dinámico.
                AccentTint.fill(chip, R.id.tagChipMini, DynamicColor.dim(tag.colorArgb))
                AccentTint.stroke(chip, R.id.tagChipMini, tag.colorArgb, R.dimen.tag_chip_stroke_width)
                tagsContainer.addView(chip)
            }
        }
    }
}
