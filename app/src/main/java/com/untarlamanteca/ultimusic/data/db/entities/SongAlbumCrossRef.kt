package com.untarlamanteca.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Relación N:N canción ↔ álbum, con número de pista/disco propios de esa aparición. */
@Entity(
    tableName = "song_album",
    primaryKeys = ["songId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId")]
)
data class SongAlbumCrossRef(
    val songId: Long,
    val albumId: Long,
    val trackNumber: Int?,
    val discNumber: Int?
)
