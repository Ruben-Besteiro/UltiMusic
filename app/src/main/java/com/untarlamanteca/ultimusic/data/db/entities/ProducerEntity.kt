package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de productores. Es idéntica a [ArtistEntity] a propósito: los productores se
 * tratan exactamente igual que los artistas (tabla propia, tabla de cruce N:N con las canciones,
 * pestaña propia y poda de huérfanos).
 *
 * [tagName] es el nombre tal cual venía en la etiqueta del archivo (el campo "compositor" del
 * fichero, que es de donde el escáner saca al productor) y sirve de clave de emparejamiento entre
 * escaneos; [name] es el nombre editable que se muestra en pantalla.
 */
@Entity(
    tableName = "producers",
    indices = [Index(value = ["tagName"], unique = true)]
)
data class ProducerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tagName: String,
    val imageName: String?
)
