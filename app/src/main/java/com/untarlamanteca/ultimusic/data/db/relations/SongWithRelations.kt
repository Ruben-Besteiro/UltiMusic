package com.untarlamanteca.ultimusic.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity

/** Canción con sus artistas y álbumes resueltos a través de las tablas de cruce. */
data class SongWithRelations(
    @Embedded val song: SongEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongArtistCrossRef::class,
            parentColumn = "songId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongAlbumCrossRef::class,
            parentColumn = "songId",
            entityColumn = "albumId"
        )
    )
    val albums: List<AlbumEntity>
)
