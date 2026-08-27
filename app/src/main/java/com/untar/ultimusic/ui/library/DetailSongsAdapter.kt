package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.albumDisplay
import com.untar.ultimusic.util.formatCompactCount
import com.untar.ultimusic.util.joinNonBlank

/**
 * Lista de canciones de una ficha de detalle: número de pista, carátula, título/duración y el
 * menú de tres puntos a la derecha.
 *
 * En un álbum la carátula será casi siempre la misma imagen repetida (la que ya preside la
 * cabecera), pero en la de un artista cada canción puede traer la suya.
 *
 * [currentKind] es de qué ficha se trata: decide si se ve el número de pista (solo en álbum, ver
 * [TrackViewHolder.bind]) y qué opción "Ir a..." del menú se oculta por inútil (la de esta misma
 * ficha, ver [onGoToAlbum]/[onGoToArtist]).
 *
 * [albumId] es el álbum que se está mirando (solo tiene valor con [currentKind] == [DetailKind.ALBUM]):
 * hace falta para saber QUÉ número de pista/disco enseñar de cada canción, ya que con la relación
 * N:M (ver [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]) una misma canción puede tener
 * una posición distinta en cada álbum al que pertenece — no basta con
 * [com.untar.ultimusic.model.Song.trackNumber] (el del álbum PRINCIPAL), que podría ser el de un
 * álbum distinto de este si la canción está en más de uno.
 */
class DetailSongsAdapter(
    private val currentKind: DetailKind,
    private val albumId: Long?,
    private val onSongClick: (Int) -> Unit,
    private val onAddToQueue: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onEditMetadata: (Song) -> Unit,
    private val onEditTags: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onGoToAlbum: (Song) -> Unit,
    private val onGoToArtist: (Song) -> Unit
) : RecyclerView.Adapter<DetailSongsAdapter.TrackViewHolder>() {

    private var tracks: List<Song> = emptyList()

    fun submit(list: List<Song>) {
        tracks = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return TrackViewHolder(inflater.inflate(R.layout.item_detail_song, parent, false))
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(
            tracks[position], position, currentKind, albumId,
            onSongClick, onAddToQueue, onAddToPlaylist, onEditMetadata, onEditTags, onDeleteSong,
            onGoToAlbum, onGoToArtist
        )
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.trackNumber)
        private val cover: ShapeableImageView = itemView.findViewById(R.id.trackCover)
        private val title: TextView = itemView.findViewById(R.id.trackTitle)
        private val duration: TextView = itemView.findViewById(R.id.trackDuration)
        private val youtubeIcon: ImageView = itemView.findViewById(R.id.trackYoutubeIcon)
        private val youtubeViews: TextView = itemView.findViewById(R.id.trackYoutubeViews)
        private val more: View = itemView.findViewById(R.id.btnTrackMore)

        fun bind(
            song: Song,
            position: Int,
            currentKind: DetailKind,
            albumId: Long?,
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
            // En la ficha de álbum el álbum ya lo dice la cabecera: repetirlo en cada canción sobra.
            // En la de un artista sí hace falta, porque cada canción puede venir de uno distinto.
            duration.text = if (currentKind == DetailKind.ALBUM) {
                TimeFormat.mmss(song.duration)
            } else {
                joinNonBlank(song.albumDisplay(), TimeFormat.mmss(song.duration))
            }
            cover.load(CoverArt.cover(itemView.context, song), CoverLoader.get(itemView.context))

            // Solo se enseñan visitas de una canción CON vídeo: youtubeViewCount podría quedar como un
            // valor viejo si el usuario quita el enlace sin que haya pasado por medio otro refresco diario.
            val views = song.youtubeViewCount?.takeIf { song.videoUrl != null }
            youtubeIcon.isVisible = views != null
            youtubeViews.isVisible = views != null
            if (views != null) {
                youtubeViews.text = formatCompactCount(views)
            }

            if (currentKind == DetailKind.ALBUM) {
                number.visibility = View.VISIBLE
                // El número de pista de ESTE álbum, no el del principal (Song.trackNumber): con la
                // relación N:M (ver SongAlbumCrossRef) pueden ser distintos si la canción está en más
                // de uno. Un álbum sin número de pista tampoco tiene dato que mostrar: un guión en
                // vez de dejar la columna vacía, que descuadraría los títulos.
                val trackNumber = song.albums.firstOrNull { it.album.id == albumId }?.trackNumber
                number.text = trackNumber?.toString() ?: "-"
            } else {
                // En la de un artista el número de pista es un dato del álbum, no de la persona:
                // fuera del todo en vez de un guión que no significaría nada aquí.
                number.visibility = View.GONE
            }

            // La posición en la lista, no el id: el reproductor necesita saber por dónde empezar
            // dentro de la colección para poder seguir con las siguientes.
            itemView.setOnClickListener { onSongClick(position) }

            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.menu_song_item, menu)
                    // La opción de "ir a" la ficha en la que ya estamos no pinta nada aquí; las demás
                    // solo si la canción de verdad tiene ese dato.
                    menu.findItem(R.id.action_go_to_album)?.isVisible =
                        currentKind != DetailKind.ALBUM && song.album != null
                    menu.findItem(R.id.action_go_to_artist)?.isVisible =
                        currentKind != DetailKind.ARTIST && song.artists.isNotEmpty()
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
