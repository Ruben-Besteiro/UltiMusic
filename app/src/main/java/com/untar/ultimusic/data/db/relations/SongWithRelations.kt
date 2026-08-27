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

/**
 * Canción con sus artistas, sus álbumes y sus productores resueltos a través de las tablas de cruce.
 *
 * [albums] sale de [SongAlbumCrossRef] (N:M, ver su cabecera): una canción puede estar catalogada en
 * más de un álbum a la vez, a diferencia de la relación N:1 que tuvo entre v13 y v26.
 *
 * [artistLinks]/[albumLinks]/[producerLinks] traen las filas de cruce en crudo (con su
 * [SongArtistCrossRef.position]/[SongAlbumCrossRef.position]/[SongProducerCrossRef.position], y
 * además [SongAlbumCrossRef.trackNumber]/[SongAlbumCrossRef.discNumber]) junto a las entidades ya
 * resueltas en [artists]/[albums]/[producers]: un `@Relation` de Room no garantiza NINGÚN orden en
 * la lista que devuelve (en la práctica sale por el id, no por el orden en que se escribieron), así
 * que [Mappers.toDomain][com.untar.ultimusic.data.db.toDomain] reordena con la posición guardada en
 * estas filas de cruce en vez de fiarse del orden que traiga la consulta.
 */
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
    @Relation(parentColumn = "id", entityColumn = "songId")
    val artistLinks: List<SongArtistCrossRef>,
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
    @Relation(parentColumn = "id", entityColumn = "songId")
    val albumLinks: List<SongAlbumCrossRef>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongProducerCrossRef::class,
            parentColumn = "songId",
            entityColumn = "producerId"
        )
    )
    val producers: List<ProducerEntity>,
    @Relation(parentColumn = "id", entityColumn = "songId")
    val producerLinks: List<SongProducerCrossRef>
)
