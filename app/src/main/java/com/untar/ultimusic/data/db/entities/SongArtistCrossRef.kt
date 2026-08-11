package com.untar.ultimusic.data.db.entities

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
    val artistId: Long,
    /**
     * Posición del artista dentro de la lista de la canción (0 = el primero que escribió/trajo la
     * etiqueta). Sin esto, [com.untar.ultimusic.data.db.relations.SongWithRelations.artists] saldría
     * en el orden que le diera la gana a SQLite (normalmente por id de artista), no en el que puso
     * el usuario: ver [com.untar.ultimusic.data.db.Mappers.toDomain].
     */
    val position: Int = 0
)
