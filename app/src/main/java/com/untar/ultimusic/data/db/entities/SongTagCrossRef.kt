package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Relación N:N canción ↔ etiqueta. A diferencia de [SongArtistCrossRef]/[SongProducerCrossRef] no
 * lleva `position`: el orden de las etiquetas de una canción no se pinta en ningún sitio.
 *
 * Tienen filas aquí las predefinidas de membresía real (Vídeo sincronizado, Remix / Cover, ver
 * [TagEntity]) y las etiquetas personalizadas; las otras 3 predefinidas se calculan al vuelo, sin
 * ninguna fila en esta tabla.
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
