package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de artistas. [tagName] (nombre tal cual venía en la etiqueta del archivo) es la
 * clave de emparejamiento durante el escaneo: así una canción nueva de un artista ya renombrado se
 * enlaza al artista existente en vez de duplicarlo. [name] es el nombre editable que se muestra.
 *
 * [youtubeChannelId]/[youtubeChannelSubscriberCount] son la "popularidad" del artista (ver
 * [com.untar.ultimusic.model.PersonSummary.popularity]): el canal de YouTube que más se repite entre
 * las canciones donde este artista es el PRINCIPAL (`song_artist.position = 0`) y el número de
 * suscriptores de ese canal. Se calculan y guardan aquí mismo —no en una tabla aparte— en
 * [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue], con el mismo límite de una
 * vez al día que [com.untar.ultimusic.data.db.entities.SongEntity.youtubeViewCount]. Null mientras no
 * haya ningún canal válido (sin vídeos, o ninguno de sus canales responde todavía). Siempre van juntos
 * los dos o ninguno: no tiene sentido guardar el id de un canal sin sus suscriptores.
 */
@Entity(
    tableName = "artists",
    indices = [Index(value = ["tagName"], unique = true)]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tagName: String,
    val imageName: String?,
    val youtubeChannelId: String? = null,
    val youtubeChannelSubscriberCount: Long? = null
)
