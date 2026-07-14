package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de canciones. El [filePath] es la clave estable que ancla la canción entre
 * escaneos (aunque el [id] autogenerado cambie de instalación a instalación).
 *
 * Los campos `og*` guardan la info de la canción ORIGINAL cuando esta es un remix (título, artista,
 * álbum y año del tema original). No provienen de la etiqueta del archivo: los rellena el usuario
 * desde el editor y quedan a null mientras la canción no se marque como remix.
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val title: String,
    val duration: Long,
    val year: Int?,
    val genres: List<String>,
    val imageName: String?,
    val comment: String?,
    val producer: String?,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
