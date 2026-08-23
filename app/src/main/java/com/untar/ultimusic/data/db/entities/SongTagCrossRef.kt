package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Relación N:N canción ↔ etiqueta. A diferencia de [SongArtistCrossRef]/[SongProducerCrossRef] no
 * lleva `position`: el orden de las etiquetas de una canción no se pinta en ningún sitio.
 *
 * Hoy solo la etiqueta "Favoritos" llega a tener filas aquí (las otras 3 predefinidas se calculan al
 * vuelo, ver [TagEntity]), y ni siquiera esa: todavía no hay UI para añadir/quitar canciones de
 * ninguna etiqueta (llega en un mensaje futuro). La tabla se crea ya como andamiaje.
 */
@Entity(
    tableName = "song_tag",
    primaryKeys = ["songId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class SongTagCrossRef(
    val songId: Long,
    val tagId: Long
)
