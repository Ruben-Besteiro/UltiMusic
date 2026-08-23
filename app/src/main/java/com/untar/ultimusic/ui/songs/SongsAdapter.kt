package com.untar.ultimusic.ui.songs

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
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
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()

    /** Ids de las canciones marcadas; no vacío = selección múltiple activa (ver [SongsFragment]). */
    private var selectedIds: Set<Long> = emptySet()

    private var accentColor: Int = 0xFFFFD000.toInt()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Song>) {
        songs = list
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

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(
            song,
            selected = song.id in selectedIds,
            accentColor = accentColor,
            onSongClick, onSongLongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onEditTags,
            onDeleteSong, onGoToAlbum, onGoToArtist
        )
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
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        fun bind(
            song: Song,
            selected: Boolean,
            accentColor: Int,
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
    }
}
