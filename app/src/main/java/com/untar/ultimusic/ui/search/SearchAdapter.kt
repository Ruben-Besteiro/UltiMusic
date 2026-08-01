package com.untar.ultimusic.ui.search

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.library.PersonKind
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor

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
    private val onDeleteSong: (Song) -> Unit,
    private val onAlbumClick: (AlbumSummary) -> Unit,
    private val onPersonClick: (PersonKind, PersonSummary) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<SearchResult> = emptyList()

    /** Color con el que se pintan los títulos de sección; sigue al de la canción que suena. */
    private var accent: Int = DynamicColor.DEFAULT

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchResult>) {
        items = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchResult.Header -> TYPE_HEADER
        is SearchResult.SongRow -> TYPE_SONG
        is SearchResult.AlbumRow -> TYPE_ALBUM
        is SearchResult.PersonRow -> TYPE_PERSON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_search_header, parent, false))
            TYPE_SONG -> SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album_row, parent, false))
            else -> PersonViewHolder(inflater.inflate(R.layout.item_person, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchResult.Header -> (holder as HeaderViewHolder).bind(item.titleRes, accent)
            is SearchResult.SongRow -> (holder as SongViewHolder).bind(
                item.song, onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onDeleteSong
            )
            is SearchResult.AlbumRow -> (holder as AlbumViewHolder).bind(item.album, onAlbumClick)
            is SearchResult.PersonRow -> (holder as PersonViewHolder).bind(
                item.kind, item.person, onPersonClick
            )
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.sectionTitle)

        fun bind(@StringRes titleRes: Int, accent: Int) {
            title.setText(titleRes)
            title.setTextColor(accent)
        }
    }

    /**
     * Fila de canción. Es la misma que la de la pestaña «Canciones», con su menú de tres puntos, pero
     * sin el resaltado en amarillo de "esta canción no está en ninguna playlist": aquí el usuario ha
     * venido a buscar algo concreto, no a repasar su biblioteca, así que ese aviso sobraría.
     */
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
            onDeleteSong: (Song) -> Unit
        ) {
            val context = itemView.context
            title.text = song.title

            val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
            val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
            subtitle.text = context.getString(R.string.song_subtitle_format, artist, album)

            cover.load(CoverArt.cover(context, song), CoverLoader.get(context))

            itemView.setOnClickListener { onSongClick(song) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_queue -> { onAddToQueue(song); true }
                            R.id.action_add_to_playlist -> { onAddToPlaylist(song); true }
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

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.albumCover)
        private val title: TextView = itemView.findViewById(R.id.albumTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.albumSubtitle)

        fun bind(album: AlbumSummary, onAlbumClick: (AlbumSummary) -> Unit) {
            val context = itemView.context
            title.text = album.title

            val songsText = context.resources.getQuantityString(
                R.plurals.song_count, album.songCount, album.songCount
            )
            subtitle.text = context.getString(
                R.string.song_subtitle_format,
                album.artistName ?: MusicScanner.UNKNOWN_ARTIST,
                songsText
            )

            cover.load(CoverArt.cover(context, album.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onAlbumClick(album) }
        }
    }

    /** Sirve igual para un artista y para un productor: son la misma fila (ver `item_person`). */
    class PersonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ShapeableImageView = itemView.findViewById(R.id.personImage)
        private val name: TextView = itemView.findViewById(R.id.personName)
        private val subtitle: TextView = itemView.findViewById(R.id.personSubtitle)

        fun bind(
            kind: PersonKind,
            person: PersonSummary,
            onPersonClick: (PersonKind, PersonSummary) -> Unit
        ) {
            val context = itemView.context
            name.text = person.name

            val albumsText = context.resources.getQuantityString(
                R.plurals.album_count, person.albumCount, person.albumCount
            )
            val songsText = context.resources.getQuantityString(
                R.plurals.song_count, person.songCount, person.songCount
            )
            subtitle.text = context.getString(R.string.song_subtitle_format, albumsText, songsText)

            image.load(CoverArt.cover(context, person.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onPersonClick(kind, person) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SONG = 1
        const val TYPE_ALBUM = 2
        const val TYPE_PERSON = 3
    }
}
