package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de álbumes. La clave de emparejamiento en el escaneo es la pareja de valores de
 * etiqueta ([tagTitle], [tagAlbumArtist]), que desambigua álbumes homónimos de distinto artista.
 * [title] es el título editable que se muestra.
 */
@Entity(
    tableName = "albums",
    indices = [Index(value = ["tagTitle", "tagAlbumArtist"], unique = true)]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val tagTitle: String,
    val tagAlbumArtist: String,
    val year: Int?,
    val genres: List<String>,
    val imageName: String?
)
