package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de artistas. [tagName] (nombre tal cual venía en la etiqueta del archivo) es la
 * clave de emparejamiento durante el escaneo: así una canción nueva de un artista ya renombrado se
 * enlaza al artista existente en vez de duplicarlo. [name] es el nombre editable que se muestra.
 */
@Entity(
    tableName = "artists",
    indices = [Index(value = ["tagName"], unique = true)]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tagName: String,
    val imageName: String?
)
