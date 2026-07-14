package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Relación N:N canción ↔ artista. */
@Entity(
    tableName = "song_artist",
    primaryKeys = ["songId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artistId")]
)
data class SongArtistCrossRef(
    val songId: Long,
    val artistId: Long
)
