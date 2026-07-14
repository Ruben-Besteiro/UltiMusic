package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Relación N:N álbum ↔ artista. */
@Entity(
    tableName = "album_artist",
    primaryKeys = ["albumId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
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
data class AlbumArtistCrossRef(
    val albumId: Long,
    val artistId: Long
)
