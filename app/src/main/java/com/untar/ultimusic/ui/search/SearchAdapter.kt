package com.untar.ultimusic.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.bindPersonSubscribers
import com.untar.ultimusic.util.bindSongSubtitle
import com.untar.ultimusic.util.joinNonBlank

/**
 * Lista de resultados del buscador. A diferencia del resto de adaptadores de la app, aquí las filas
 * NO son todas iguales: hay cabeceras de sección, canciones, álbumes y personas en una única lista.
 *
 * Eso se resuelve con los **tipos de vista** del RecyclerView: [getItemViewType] dice de qué clase es
 * cada posición y [onCreateViewHolder] infla el layout que toque. El RecyclerView recicla las vistas
 * por separado según su tipo, así que al desplazarse nunca intenta reutilizar la fila de un álbum
 * para pintar un artista.
 *
 * Las filas reutilizan los layouts que ya existen (`item_song`, `item_person`), de modo que una
 * canción se ve exactamente igual aquí que en su pestaña.
 */
class SearchAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onEditTags: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onGoToAlbum: (Song) -> Unit,
    private val onGoToArtist: (Song) -> Unit,
    private val onAlbumClick: (AlbumSummary) -> Unit,
    private val onPersonClick: (PersonSummary) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Diferencias calculadas en segundo plano: al teclear, solo se tocan las filas que entran o
     *  salen de los resultados, en vez de rehacer la lista entera (y recargar cada carátula) en cada
     *  letra como hacía el `notifyDataSetChanged` de antes. */
    private val differ = AsyncListDiffer(this, DIFF)

    private val items: List<SearchResult> get() = differ.currentList

    /** Color con el que se pintan los títulos de sección; sigue al de la canción que suena. */
    private var accent: Int = DynamicColor.DEFAULT

    fun submit(list: List<SearchResult>) {
        differ.submitList(list)
    }

    /** Solo las cabeceras llevan el acento: ni las canciones ni los álbumes ni las personas cambian. */
    fun setAccent(color: Int) {
        if (accent == color) return
        accent = color
        items.forEachIndexed { index, item ->
            if (item is SearchResult.Header || (item is SearchResult.SqlRow && item.isHeader)) notifyItemChanged(index, PAYLOAD_REBIND)
        }
    }

    /** Ver [com.untar.ultimusic.util.CoverArt.revision]: repinta lo visible para recoger una carátula
     *  que ha cambiado de contenido sin cambiar de nombre. Con payload para que no haya fundido. */
    fun refreshCovers() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_REBIND)
    }

    override fun getItemCount(): Int = items.size

    /** La canción de [position], o `null` si esa fila no es una canción (cabecera, álbum o
     *  persona). Lo usa [com.untar.ultimusic.ui.common.attachSwipeToQueue] para saber qué encolar
     *  al arrastrar una fila hacia la derecha. */
    fun songAt(position: Int): Song? = (items.getOrNull(position) as? SearchResult.SongRow)?.song

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchResult.Header -> TYPE_HEADER
        is SearchResult.SongRow -> TYPE_SONG
        is SearchResult.AlbumRow -> TYPE_ALBUM
        is SearchResult.PersonRow -> TYPE_PERSON
        is SearchResult.SqlRow -> TYPE_SQL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_search_header, parent, false))
            TYPE_SONG -> SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album_row, parent, false))
            TYPE_SQL -> SqlViewHolder(inflater.inflate(R.layout.item_sql_row, parent, false))
            else -> PersonViewHolder(inflater.inflate(R.layout.item_person, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchResult.Header -> (holder as HeaderViewHolder).bind(item.titleRes, accent)
            is SearchResult.SongRow -> (holder as SongViewHolder).bind(
                item.song, onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onEditTags,
                onDeleteSong, onGoToAlbum, onGoToArtist
            )
            is SearchResult.AlbumRow -> (holder as AlbumViewHolder).bind(item.album, onAlbumClick)
            is SearchResult.PersonRow -> (holder as PersonViewHolder).bind(item.person, onPersonClick)
            is SearchResult.SqlRow -> (holder as SqlViewHolder).bind(item, accent)
        }
    }

    class SqlViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.sqlRowText)

        fun bind(row: SearchResult.SqlRow, accent: Int) {
            text.text = row.text
            text.setTextColor(if (row.isHeader) accent else text.context.getColor(R.color.um_on_background))
            text.setTypeface(null, if (row.isHeader) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.sectionTitle)

        fun bind(@StringRes titleRes: Int, accent: Int) {
            title.setText(titleRes)
            title.setTextColor(accent)
        }
    }

    /** Fila de canción, con su menú de tres puntos. Reutiliza el mismo layout y el mismo helper de
     *  subtítulo que la pestaña «Canciones» (item_song.xml / bindSongSubtitle) para que se vea
     *  exactamente igual aquí que allí, sin mantener una copia aparte que se pueda desincronizar. */
    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitleArtist: TextView = itemView.findViewById(R.id.songSubtitleArtist)
        private val subtitleRest: TextView = itemView.findViewById(R.id.songSubtitleRest)
        private val youtubeIcon: ImageView = itemView.findViewById(R.id.songYoutubeIcon)
        private val youtubeViews: TextView = itemView.findViewById(R.id.songYoutubeViews)
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        fun bind(
            song: Song,
            onSongClick: (Song) -> Unit,
            onAddToQueue: (Song) -> Unit,
            onAddToPlaylist: (Song) -> Unit,
            onEditMetadata: (Song) -> Unit,
            onEditTags: (Song) -> Unit,
            onDeleteSong: (Song) -> Unit,
            onGoToAlbum: (Song) -> Unit,
            onGoToArtist: (Song) -> Unit
        ) {
            val context = itemView.context
            title.text = song.title
            bindSongSubtitle(song, subtitleArtist, subtitleRest, youtubeIcon, youtubeViews)

            cover.load(CoverArt.cover(context, song), CoverLoader.get(context))

            itemView.setOnClickListener { onSongClick(song) }
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
                            R.id.action_share_song -> { com.untar.ultimusic.util.ShareSong.share(anchor.context, song); true }
                            R.id.action_delete_song -> { onDeleteSong(song); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.albumCover)
        private val title: TextView = itemView.findViewById(R.id.albumTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.albumSubtitle)
        private val songCount: TextView = itemView.findViewById(R.id.albumSongCount)

        fun bind(album: AlbumSummary, onAlbumClick: (AlbumSummary) -> Unit) {
            val context = itemView.context
            title.text = album.title

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

    class PersonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ShapeableImageView = itemView.findViewById(R.id.personImage)
        private val name: TextView = itemView.findViewById(R.id.personName)
        private val subtitle: TextView = itemView.findViewById(R.id.personSubtitle)
        private val youtubeIcon: ImageView = itemView.findViewById(R.id.personYoutubeIcon)
        private val youtubeSubscribers: TextView = itemView.findViewById(R.id.personYoutubeSubscribers)

        fun bind(person: PersonSummary, onPersonClick: (PersonSummary) -> Unit) {
            val context = itemView.context
            name.text = person.name

            val albumsText = context.resources.getQuantityString(
                R.plurals.album_count, person.albumCount, person.albumCount
            )
            val songsText = context.resources.getQuantityString(
                R.plurals.song_count, person.songCount, person.songCount
            )
            subtitle.text = context.getString(R.string.song_subtitle_format, albumsText, songsText)
            bindPersonSubscribers(person.popularity, youtubeIcon, youtubeSubscribers)

            image.load(CoverArt.cover(context, person.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onPersonClick(person) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SONG = 1
        const val TYPE_ALBUM = 2
        const val TYPE_PERSON = 3
        const val TYPE_SQL = 4

        val PAYLOAD_REBIND = Any()

        val DIFF = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean = when {
                oldItem is SearchResult.Header && newItem is SearchResult.Header -> oldItem.titleRes == newItem.titleRes
                oldItem is SearchResult.SongRow && newItem is SearchResult.SongRow -> oldItem.song.id == newItem.song.id
                oldItem is SearchResult.AlbumRow && newItem is SearchResult.AlbumRow -> oldItem.album.id == newItem.album.id
                oldItem is SearchResult.PersonRow && newItem is SearchResult.PersonRow -> oldItem.person.id == newItem.person.id
                oldItem is SearchResult.SqlRow && newItem is SearchResult.SqlRow -> oldItem.index == newItem.index
                else -> false
            }

            override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean = oldItem == newItem
        }
    }
}
