package com.untarlamanteca.ultimusic.data.db

import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.ProducerEntity
import com.untarlamanteca.ultimusic.data.db.relations.AlbumSummaryRow
import com.untarlamanteca.ultimusic.data.db.relations.PersonSummaryRow
import com.untarlamanteca.ultimusic.data.db.relations.SongWithRelations
import com.untarlamanteca.ultimusic.model.Album
import com.untarlamanteca.ultimusic.model.AlbumSummary
import com.untarlamanteca.ultimusic.model.Artist
import com.untarlamanteca.ultimusic.model.PersonSummary
import com.untarlamanteca.ultimusic.model.Producer
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverRef

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
    artists = artists.map { it.toDomain() },
    albums = albums.map { it.toDomain() },
    producers = producers.map { it.toDomain() },
    duration = song.duration,
    year = song.year,
    genres = song.genres,
    lyrics = song.lyrics,
    language = song.language,
    imageName = song.imageName,
    comment = song.comment,
    ogTitle = song.ogTitle,
    ogArtist = song.ogArtist,
    ogAlbum = song.ogAlbum,
    ogYear = song.ogYear
)

// --- Resúmenes de las pestañas de Álbumes / Artistas / Productores ---

fun AlbumSummaryRow.toDomain(): AlbumSummary = AlbumSummary(
    id = id,
    title = title,
    artistName = artistName,
    year = year,
    songCount = songCount,
    totalDuration = totalDuration,
    cover = CoverRef(ownImage = imageName, songImage = sampleSongImage, songPath = sampleSongPath)
)

fun PersonSummaryRow.toDomain(): PersonSummary = PersonSummary(
    id = id,
    name = name,
    songCount = songCount,
    albumCount = albumCount,
    totalDuration = totalDuration,
    cover = CoverRef(ownImage = imageName, songImage = sampleSongImage, songPath = sampleSongPath)
)
