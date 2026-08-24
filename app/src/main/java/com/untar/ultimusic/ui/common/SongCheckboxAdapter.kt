package com.untar.ultimusic.ui.common

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.joinNonBlank

/**
 * Lista de canciones con casilla, compartida por
 * [com.untar.ultimusic.ui.library.AddSongsToTagDialogFragment] y
 * [com.untar.ultimusic.ui.playlists.AddSongsToPlaylistDialogFragment] (ver `dialog_add_songs.xml`,
 * el layout que también comparten esas dos pantallas): una fila más pequeña que
 * [com.untar.ultimusic.ui.songs.SongsAdapter] (ver item_song_checkbox.xml), sin menú de 3 puntos —
 * aquí solo hace falta marcar/desmarcar, no hay nada más que tocar sobre estas canciones desde esta
 * pantalla.
 *
 * [checkedIds] vive en el fragmento, no aquí: este adapter solo pinta el estado que le llega
 * ([submit]/[setChecked]) y avisa de cada toque ([onToggle]), igual que
 * [com.untar.ultimusic.ui.songs.SongsAdapter.setSelection] con la selección múltiple de la pestaña
 * Canciones.
 */
class SongCheckboxAdapter(
    private val onToggle: (Song) -> Unit
) : RecyclerView.Adapter<SongCheckboxAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var checkedIds: Set<Long> = emptySet()

    /** Ver [setAccentColor]: el amarillo dinámico de cada casilla (ver CLAUDE.md), igual que
     *  [com.untar.ultimusic.ui.songs.SongsAdapter.setAccentColor]. */
    private var accentColor: Int = 0xFFFFD000.toInt()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setChecked(ids: Set<Long>) {
        if (checkedIds == ids) return
        checkedIds = ids
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_song_checkbox, parent, false))
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song, checked = song.id in checkedIds, accentColor = accentColor, onToggle = onToggle)
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.cover)
        private val check: MaterialCheckBox = itemView.findViewById(R.id.songCheckboxCheck)
        private val title: TextView = itemView.findViewById(R.id.songCheckboxTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.songCheckboxSubtitle)

        fun bind(song: Song, checked: Boolean, accentColor: Int, onToggle: (Song) -> Unit) {
            title.text = song.title
            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
            subtitle.text = joinNonBlank(artist, song.album?.title)

            cover.load(CoverArt.cover(itemView.context, song), CoverLoader.get(itemView.context))

            check.isChecked = checked
            check.buttonTintList = ColorStateList.valueOf(accentColor)
            // La casilla es solo pintura (clickable=false en el XML): toda la fila alterna el
            // estado, así no hace falta acertar justo en el cuadradito para marcar una canción.
            itemView.setOnClickListener { onToggle(song) }
        }
    }
}
