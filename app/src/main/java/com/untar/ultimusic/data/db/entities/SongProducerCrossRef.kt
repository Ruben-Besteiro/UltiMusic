package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Relación N:N canción ↔ productor (gemela de [SongArtistCrossRef]). */
@Entity(
    tableName = "song_producer",
    primaryKeys = ["songId", "producerId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProducerEntity::class,
            parentColumns = ["id"],
            childColumns = ["producerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("producerId")]
)
data class SongProducerCrossRef(
    val songId: Long,
    val producerId: Long,
    /** Posición del productor en la lista de la canción. Gemelo de [SongArtistCrossRef.position]. */
    val position: Int = 0
)
