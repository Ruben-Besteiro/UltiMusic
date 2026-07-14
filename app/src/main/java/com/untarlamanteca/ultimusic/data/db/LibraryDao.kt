package com.untarlamanteca.ultimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.untarlamanteca.ultimusic.data.db.entities.AlbumArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.data.db.relations.SongWithRelations
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.data.scan.ScannedSong
import kotlinx.coroutines.flow.Flow

/**
 * Único DAO de la biblioteca. Además de las operaciones sueltas, expone [reconcile], que en una
 * sola transacción concilia el resultado de un escaneo con lo ya guardado: inserta canciones
 * nuevas (creando/enlazando sus artistas y álbumes) y borra las que ya no existen en disco,
 * SIN tocar nunca lo que el usuario haya editado.
 */
@Dao
abstract class LibraryDao {

    // --- Lectura reactiva (lo que observa la UI) ---

    @Transaction
    @Query("SELECT * FROM songs ORDER BY LOWER(title)")
    abstract fun observeSongs(): Flow<List<SongWithRelations>>

    // --- Consultas de apoyo para la reconciliación ---

    @Query("SELECT filePath FROM songs")
    abstract suspend fun allSongPaths(): List<String>

    @Query("SELECT * FROM artists WHERE tagName = :tagName LIMIT 1")
    abstract suspend fun findArtistByTag(tagName: String): ArtistEntity?

    @Query("SELECT * FROM albums WHERE tagTitle = :tagTitle AND tagAlbumArtist = :tagAlbumArtist LIMIT 1")
    abstract suspend fun findAlbumByTag(tagTitle: String, tagAlbumArtist: String): AlbumEntity?

    // --- Inserciones ---

    @Insert
    abstract suspend fun insertSong(song: SongEntity): Long

    @Insert
    abstract suspend fun insertArtist(artist: ArtistEntity): Long

    @Insert
    abstract suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongArtist(ref: SongArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongAlbum(ref: SongAlbumCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlbumArtist(ref: AlbumArtistCrossRef)

    // --- Ediciones del usuario ---

    @Update
    abstract suspend fun updateSong(song: SongEntity)

    @Update
    abstract suspend fun updateArtist(artist: ArtistEntity)

    @Update
    abstract suspend fun updateAlbum(album: AlbumEntity)

    // --- Borrado y poda de huérfanos ---

    @Query("DELETE FROM songs WHERE filePath IN (:paths)")
    abstract suspend fun deleteSongsByPath(paths: List<String>)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT artistId FROM song_artist)")
    abstract suspend fun pruneOrphanArtists()

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT albumId FROM song_album)")
    abstract suspend fun pruneOrphanAlbums()

    // --- Reconciliación ---

    /**
     * Concilia un escaneo con lo persistido. Debe recibir el resultado del escaneo ya hecho
     * (el escaneo es lento y va fuera de la transacción); aquí solo se toca la base de datos.
     */
    @Transaction
    open suspend fun reconcile(scanned: List<ScannedSong>) {
        val existing = allSongPaths().toHashSet()
        val scannedPaths = HashSet<String>(scanned.size)

        for (s in scanned) {
            scannedPaths.add(s.filePath)
            // Ya existe -> no se toca: las ediciones del usuario mandan.
            if (s.filePath in existing) continue

            val songId = insertSong(s.toEntity())

            val artistName = s.artist ?: MusicScanner.UNKNOWN_ARTIST
            val artistId = getOrCreateArtist(artistName)
            insertSongArtist(SongArtistCrossRef(songId = songId, artistId = artistId))

            val albumArtist = s.albumArtist ?: s.artist ?: MusicScanner.UNKNOWN_ARTIST
            val albumTitle = s.album ?: MusicScanner.UNKNOWN_ALBUM
            val albumId = getOrCreateAlbum(albumTitle, albumArtist, s.year, s.genres, artistId)
            insertSongAlbum(
                SongAlbumCrossRef(
                    songId = songId,
                    albumId = albumId,
                    trackNumber = s.trackNumber,
                    discNumber = s.discNumber
                )
            )
        }

        val removed = existing.filter { it !in scannedPaths }
        if (removed.isNotEmpty()) {
            deleteSongsByPath(removed)
            pruneOrphanArtists()
            pruneOrphanAlbums()
        }
    }

    private suspend fun getOrCreateArtist(tagName: String): Long {
        findArtistByTag(tagName)?.let { return it.id }
        return insertArtist(ArtistEntity(name = tagName, tagName = tagName, imageName = null))
    }

    private suspend fun getOrCreateAlbum(
        tagTitle: String,
        tagAlbumArtist: String,
        year: Int?,
        genres: List<String>,
        artistId: Long
    ): Long {
        findAlbumByTag(tagTitle, tagAlbumArtist)?.let { return it.id }
        val albumId = insertAlbum(
            AlbumEntity(
                title = tagTitle,
                tagTitle = tagTitle,
                tagAlbumArtist = tagAlbumArtist,
                year = year,
                genres = genres,
                imageName = null
            )
        )
        insertAlbumArtist(AlbumArtistCrossRef(albumId = albumId, artistId = artistId))
        return albumId
    }
}

/**
 * Convierte el resultado crudo del escaneo en su fila de la tabla de canciones. Los campos `og*`
 * (info de la canción original de un remix) quedan a null: no son datos de la etiqueta, los rellena
 * el usuario desde el editor cuando la canción sea efectivamente un remix.
 */
private fun ScannedSong.toEntity(): SongEntity = SongEntity(
    filePath = filePath,
    title = title,
    duration = duration,
    year = year,
    genres = genres,
    imageName = null,
    comment = null,
    producer = producer,
    ogTitle = null,
    ogArtist = null,
    ogAlbum = null,
    ogYear = null
)
