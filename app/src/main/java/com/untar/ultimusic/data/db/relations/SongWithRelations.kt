package com.untar.ultimusic.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef

/** Canción con sus artistas, álbumes y productores resueltos a través de las tablas de cruce. */
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
    val albums: List<AlbumEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongProducerCrossRef::class,
            parentColumn = "songId",
            entityColumn = "producerId"
        )
    )
    val producers: List<ProducerEntity>
)
