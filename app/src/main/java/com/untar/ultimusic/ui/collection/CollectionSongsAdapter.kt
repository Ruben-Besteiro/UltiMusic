package com.untar.ultimusic.ui.collection

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.bindSongSubtitle
import java.util.Collections

/**
 * Lista de canciones de la ficha de una lista o un género (ver [CollectionDetailDialogFragment]):
 * misma fila que la pestaña Canciones (portada + "Título" + subtítulo de dos líneas con visitas de
 * YouTube, ver [bindSongSubtitle]), pero con [onSongClick] avisando de la POSICIÓN en vez de la
 * canción —hace falta para arrancar la
 * reproducción de la colección justo por ahí, ver [com.untar.ultimusic.ui.PlayerViewModel.playCollection]—
 * y, en una lista, un manejador de arrastre para reordenar (ver [reorderable]).
 */
class CollectionSongsAdapter(
    private val reorderable: Boolean,
    private val onSongClick: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onReordered: (List<Song>) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onEditTags: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onGoToAlbum: (Song) -> Unit,
    private val onGoToArtist: (Song) -> Unit
) : RecyclerView.Adapter<CollectionSongsAdapter.SongViewHolder>() {

    private val songs: MutableList<Song> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Song>) {
        songs.clear()
        songs.addAll(list)
        notifyDataSetChanged()
    }

    fun currentSongs(): List<Song> = songs.toList()

    /** Reordena en memoria mientras se arrastra. La persistencia se hace al soltar ([onReordered]). */
    fun moveItem(from: Int, to: Int) {
        Collections.swap(songs, from, to)
        notifyItemMoved(from, to)
    }

    /** Al soltar la fila arrastrada, avisa del nuevo orden para persistirlo. */
    fun commitReorder() = onReordered(songs.toList())

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_collection_song, parent, false), onStartDrag)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(
            songs[position], reorderable,
            onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onEditTags, onDeleteSong,
            onGoToAlbum, onGoToArtist
        )
    }

    class SongViewHolder(
        itemView: View,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val handle: ImageView = itemView.findViewById(R.id.dragHandle)
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitleArtist: TextView = itemView.findViewById(R.id.songSubtitleArtist)
        private val subtitleRest: TextView = itemView.findViewById(R.id.songSubtitleRest)
        private val youtubeIcon: ImageView = itemView.findViewById(R.id.songYoutubeIcon)
        private val youtubeViews: TextView = itemView.findViewById(R.id.songYoutubeViews)
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        @SuppressLint("ClickableViewAccessibility")
        fun bind(
            song: Song,
            reorderable: Boolean,
            onSongClick: (Int) -> Unit,
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

            cover.load(CoverArt.cover(itemView.context, song), CoverLoader.get(itemView.context))

            // Sin orden que guardar (género), no hay manejador que mostrar ni arrastre que arrancar.
            handle.isVisible = reorderable
            handle.setOnTouchListener { _, event ->
                if (reorderable && event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }

            itemView.setOnClickListener { onSongClick(bindingAdapterPosition) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
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
