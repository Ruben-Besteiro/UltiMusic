package com.untarlamanteca.ultimusic.ui.songs

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverLoader
import com.untarlamanteca.ultimusic.util.DynamicColor
import java.io.File

/**
 * Lista de canciones simple.
 *
 * El menú de 3 puntos ofrece encolar, **añadir a playlist** y editar metadatos. Cuando una canción
 * no pertenece a NINGUNA playlist, tanto el botón de 3 puntos como la opción "Añadir a playlist" se
 * pintan con el color de acento (el amarillo dinámico), para señalar que es la acción recomendada.
 * Para saberlo, el adaptador recibe el conjunto de nombres de archivo que sí están en alguna playlist
 * ([setMembership]) y el acento actual ([setAccent]).
 */
class SongsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()

    /** Nombres de archivo (basenames) presentes en alguna playlist. */
    private var inAnyPlaylist: Set<String> = emptySet()

    /** Color de acento actual; por defecto el amarillo de la app. */
    private var accent: Int = DynamicColor.DEFAULT

    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setMembership(filenames: Set<String>) {
        inAnyPlaylist = filenames
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        val inPlaylist = File(song.filePath).name in inAnyPlaylist
        holder.bind(song, inPlaylist, accent, onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata)
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.songSubtitle)
        private val more: ImageButton = itemView.findViewById(R.id.btnSongMore)

        fun bind(
            song: Song,
            inAnyPlaylist: Boolean,
            accent: Int,
            onSongClick: (Song) -> Unit,
            onAddToQueue: (Song) -> Unit,
            onAddToPlaylist: (Song) -> Unit,
            onEditMetadata: (Song) -> Unit
        ) {
            title.text = song.title

            val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
            val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
            subtitle.text = itemView.context.getString(R.string.song_subtitle_format, artist, album)

            val loader = CoverLoader.get(itemView.context)
            cover.load(CoverArt.cover(itemView.context, song), loader)

            // Si la canción no está en ninguna playlist, resaltamos los 3 puntos en amarillo dinámico.
            ImageViewCompat.setImageTintList(
                more,
                if (inAnyPlaylist) null else ColorStateList.valueOf(accent)
            )

            itemView.setOnClickListener { onSongClick(song) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    // Mismo resalte para la opción "Añadir a playlist" cuando es la recomendada.
                    if (!inAnyPlaylist) {
                        menu.findItem(R.id.action_add_to_playlist)?.let { item ->
                            item.title = SpannableString(item.title).apply {
                                setSpan(ForegroundColorSpan(accent), 0, length, 0)
                            }
                        }
                    }
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_queue -> { onAddToQueue(song); true }
                            R.id.action_add_to_playlist -> { onAddToPlaylist(song); true }
                            R.id.action_edit_metadata -> { onEditMetadata(song); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }
}
