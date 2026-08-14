package com.untar.ultimusic.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef

/**
 * Canción con sus artistas, su álbum y sus productores resueltos a través de las tablas de cruce.
 *
 * [album] sale de [SongEntity.albumId], una clave foránea normal (una canción pertenece como mucho a
 * UN álbum: a diferencia de los artistas/productores, aquí no hay tabla de cruce). Room resuelve un
 * `@Relation` a un campo no-lista como relación N:1, trayendo como mucho una fila.
 *
 * [artistLinks] y [producerLinks] traen las filas de cruce en crudo (con su [SongArtistCrossRef.position]
 * / [SongProducerCrossRef.position]) además de las entidades ya resueltas en [artists]/[producers]:
 * un `@Relation` de Room no garantiza NINGÚN orden en la lista que devuelve (en la práctica sale por
 * el id del artista/productor, no por el orden en que se escribieron), así que [Mappers.toDomain]
 * reordena [artists]/[producers] con la posición guardada en estas filas de cruce en vez de fiarse
 * del orden que traiga la consulta.
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
    @Relation(parentColumn = "albumId", entityColumn = "id")
    val album: AlbumEntity?,
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
