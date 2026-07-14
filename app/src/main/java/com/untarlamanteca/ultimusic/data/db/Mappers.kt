package com.untarlamanteca.ultimusic.data.db

import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.relations.SongWithRelations
import com.untarlamanteca.ultimusic.model.Album
import com.untarlamanteca.ultimusic.model.Artist
import com.untarlamanteca.ultimusic.model.Song

/**
 * Mapeo de las entidades/relaciones de Room a los modelos de DOMINIO que consume la UI.
 * Así la capa de presentación sigue hablando de [Song]/[Album]/[Artist] sin saber de Room.
 */

fun ArtistEntity.toDomain(): Artist = Artist(
    id = id,
    name = name,
    imageName = imageName
)

/**
 * Los artistas del álbum se dejan vacíos en la vista de canciones (no se necesitan ahí; la lista
 * de canciones solo muestra el título del álbum). El flujo dedicado de álbumes los poblará.
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
    duration = song.duration,
    year = song.year,
    genres = song.genres,
    imageName = song.imageName,
    comment = song.comment,
    producer = song.producer,
    ogTitle = song.ogTitle,
    ogArtist = song.ogArtist,
    ogAlbum = song.ogAlbum,
    ogYear = song.ogYear
)
