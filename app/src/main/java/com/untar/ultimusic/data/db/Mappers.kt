package com.untar.ultimusic.data.db

import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.GreylistFolderEntity
import com.untar.ultimusic.data.db.entities.LibraryRootEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef
import com.untar.ultimusic.data.db.entities.TagEntity
import com.untar.ultimusic.data.db.relations.AlbumSummaryRow
import com.untar.ultimusic.data.db.relations.PersonSummaryRow
import com.untar.ultimusic.data.db.relations.SongWithRelations
import com.untar.ultimusic.model.Album
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.Artist
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.model.LibraryRoot
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Producer
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.Tag
import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.GroupCoverSource
import com.untar.ultimusic.util.GroupKind

/**
 * Mapeo de las entidades/relaciones de Room a los modelos de DOMINIO que consume la UI.
 * Así la capa de presentación sigue hablando de [Song]/[Album]/[Artist] sin saber de Room.
 */

fun ArtistEntity.toDomain(): Artist = Artist(
    id = id,
    name = name,
    imageName = imageName
)

fun ProducerEntity.toDomain(): Producer = Producer(
    id = id,
    name = name,
    imageName = imageName
)

fun GreylistFolderEntity.toDomain(): GreylistFolder = GreylistFolder(
    path = path,
    excluded = excluded
)

fun LibraryRootEntity.toDomain(): LibraryRoot = LibraryRoot(path = path)

fun TagEntity.toDomain(): Tag = Tag(
    id = id,
    name = name,
    colorArgb = colorArgb,
    systemKey = systemKey,
    sortOrder = sortOrder
)

/**
 * Los artistas del álbum se dejan vacíos en la vista de canciones (no se necesitan ahí; la lista
 * de canciones solo muestra el título del álbum). La pestaña de álbumes usa [AlbumSummary], que ya
 * trae el nombre del artista calculado por su propia consulta.
 */
fun AlbumEntity.toDomain(): Album = Album(
    id = id,
    title = title,
    artists = emptyList(),
    year = year,
    genres = genres,
    imageName = imageName
)

fun SongWithRelations.toDomain(): Song = Song(
    id = song.id,
    filePath = song.filePath,
    title = song.title,
    artists = artists.sortedByArtistLink(artistLinks).map { it.toDomain() },
    album = album?.toDomain(),
    producers = producers.sortedByProducerLink(producerLinks).map { it.toDomain() },
    duration = song.duration,
    year = song.year,
    genres = song.genres,
    lyrics = song.lyrics,
    language = song.language,
    imageName = song.imageName,
    comment = song.comment,
    videoUrl = song.videoUrl,
    videoThumbnailName = song.videoThumbnailName,
    videoOffsetMs = song.videoOffsetMs,
    lyricsOffsetMs = song.lyricsOffsetMs,
    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    ogTitle = song.ogTitle,
    ogArtist = song.ogArtist,
    ogAlbum = song.ogAlbum,
    ogYear = song.ogYear,
    youtubeViewCount = song.youtubeViewCount,
    dateAdded = song.dateAdded
)

// --- Resúmenes de las pestañas de Álbumes / Artistas / Productores ---

fun AlbumSummaryRow.toDomain(): AlbumSummary = AlbumSummary(
    id = id,
    title = title,
    artistName = artistName,
    year = year,
    songCount = songCount,
    totalDuration = totalDuration,
    cover = CoverRef(
        ownImage = imageName,
        group = GroupCoverSource(GroupKind.ALBUM, id)
    )
)

fun PersonSummaryRow.toDomain(): PersonSummary = PersonSummary(
    id = id,
    name = name,
    songCount = songCount,
    albumCount = albumCount,
    totalDuration = totalDuration,
    cover = CoverRef(
        ownImage = imageName,
        group = GroupCoverSource(GroupKind.ARTIST, id)
    ),
    popularity = popularity
)

// --- Orden de artistas/productores dentro de una canción ---
//
// Un `@Relation` de Room (ver [SongWithRelations]) no garantiza NINGÚN orden en la lista que
// devuelve: en la práctica sale por el id del artista/productor, no por el orden en que el usuario
// los escribió. Estas dos funciones reordenan con la posición guardada en la fila de cruce
// ([SongArtistCrossRef.position]/[SongProducerCrossRef.position]), que sí se escribe respetando ese
// orden (ver `LibraryDao.saveSongEdits`/`LibraryDao.reconcile`).

private fun List<ArtistEntity>.sortedByArtistLink(links: List<SongArtistCrossRef>): List<ArtistEntity> {
    val order = links.associate { it.artistId to it.position }
    return sortedBy { order[it.id] ?: Int.MAX_VALUE }
}

private fun List<ProducerEntity>.sortedByProducerLink(links: List<SongProducerCrossRef>): List<ProducerEntity> {
    val order = links.associate { it.producerId to it.position }
    return sortedBy { order[it.id] ?: Int.MAX_VALUE }
}
