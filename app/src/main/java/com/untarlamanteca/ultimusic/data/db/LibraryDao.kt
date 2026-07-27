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
import com.untarlamanteca.ultimusic.data.db.entities.ProducerEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongProducerCrossRef
import com.untarlamanteca.ultimusic.data.db.relations.AlbumSummaryRow
import com.untarlamanteca.ultimusic.data.db.relations.PersonSummaryRow
import com.untarlamanteca.ultimusic.data.db.relations.SongWithRelations
import com.untarlamanteca.ultimusic.data.db.relations.TrackPosition
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.data.scan.ScannedSong
import kotlinx.coroutines.flow.Flow

/**
 * Único DAO de la biblioteca. Además de las operaciones sueltas, expone [reconcile], que en una
 * sola transacción concilia el resultado de un escaneo con lo ya guardado: inserta canciones
 * nuevas (creando/enlazando sus artistas y álbumes), reapunta las que se hayan movido de carpeta y
 * borra las que ya no existen en disco, SIN tocar nunca lo que el usuario haya editado.
 */
@Dao
abstract class LibraryDao {

    // --- Lectura reactiva (lo que observa la UI) ---

    @Transaction
    @Query("SELECT * FROM songs ORDER BY LOWER(title)")
    abstract fun observeSongs(): Flow<List<SongWithRelations>>

    // --- Resúmenes de las pestañas Álbumes / Artistas / Productores ---
    //
    // Las cuentas y sumas se calculan EN SQLite con subconsultas, no en Kotlin: traerse todas las
    // canciones de cada álbum solo para contarlas sería mucho más lento y gastaría memoria de más.
    //
    // El parámetro `:id` sirve para dos cosas con una sola consulta: pasando null se obtiene la
    // lista entera (la rejilla), y pasando un id concreto, la ficha de detalle de ese elemento.
    // Se hace así para no duplicar estos SELECT tan largos.

    @Query(
        """
        SELECT
            a.id AS id,
            a.title AS title,
            a.imageName AS imageName,
            a.year AS year,
            (SELECT ar.name FROM album_artist aa
                JOIN artists ar ON ar.id = aa.artistId
                WHERE aa.albumId = a.id ORDER BY LOWER(ar.name) LIMIT 1) AS artistName,
            (SELECT COUNT(*) FROM song_album sa WHERE sa.albumId = a.id) AS songCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM song_album sa
                JOIN songs s ON s.id = sa.songId WHERE sa.albumId = a.id) AS totalDuration,
            (SELECT s.imageName FROM song_album sa
                JOIN songs s ON s.id = sa.songId
                WHERE sa.albumId = a.id AND s.imageName IS NOT NULL LIMIT 1) AS sampleSongImage,
            (SELECT s.filePath FROM song_album sa
                JOIN songs s ON s.id = sa.songId
                WHERE sa.albumId = a.id
                ORDER BY sa.trackNumber IS NULL, sa.trackNumber LIMIT 1) AS sampleSongPath
        FROM albums a
        WHERE :id IS NULL OR a.id = :id
        ORDER BY LOWER(a.title)
        """
    )
    abstract fun observeAlbumSummaries(id: Long?): Flow<List<AlbumSummaryRow>>

    @Query(
        """
        SELECT
            ar.id AS id,
            ar.name AS name,
            ar.imageName AS imageName,
            (SELECT COUNT(*) FROM song_artist x WHERE x.artistId = ar.id) AS songCount,
            (SELECT COUNT(DISTINCT sa.albumId) FROM song_artist x
                JOIN song_album sa ON sa.songId = x.songId WHERE x.artistId = ar.id) AS albumCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM song_artist x
                JOIN songs s ON s.id = x.songId WHERE x.artistId = ar.id) AS totalDuration,
            (SELECT s.imageName FROM song_artist x
                JOIN songs s ON s.id = x.songId
                WHERE x.artistId = ar.id AND s.imageName IS NOT NULL LIMIT 1) AS sampleSongImage,
            (SELECT s.filePath FROM song_artist x
                JOIN songs s ON s.id = x.songId
                WHERE x.artistId = ar.id ORDER BY LOWER(s.title) LIMIT 1) AS sampleSongPath
        FROM artists ar
        WHERE :id IS NULL OR ar.id = :id
        ORDER BY LOWER(ar.name)
        """
    )
    abstract fun observeArtistSummaries(id: Long?): Flow<List<PersonSummaryRow>>

    /** Gemela de [observeArtistSummaries]: los productores se tratan exactamente igual. */
    @Query(
        """
        SELECT
            p.id AS id,
            p.name AS name,
            p.imageName AS imageName,
            (SELECT COUNT(*) FROM song_producer x WHERE x.producerId = p.id) AS songCount,
            (SELECT COUNT(DISTINCT sa.albumId) FROM song_producer x
                JOIN song_album sa ON sa.songId = x.songId WHERE x.producerId = p.id) AS albumCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM song_producer x
                JOIN songs s ON s.id = x.songId WHERE x.producerId = p.id) AS totalDuration,
            (SELECT s.imageName FROM song_producer x
                JOIN songs s ON s.id = x.songId
                WHERE x.producerId = p.id AND s.imageName IS NOT NULL LIMIT 1) AS sampleSongImage,
            (SELECT s.filePath FROM song_producer x
                JOIN songs s ON s.id = x.songId
                WHERE x.producerId = p.id ORDER BY LOWER(s.title) LIMIT 1) AS sampleSongPath
        FROM producers p
        WHERE :id IS NULL OR p.id = :id
        ORDER BY LOWER(p.name)
        """
    )
    abstract fun observeProducerSummaries(id: Long?): Flow<List<PersonSummaryRow>>

    // --- Canciones de una ficha de detalle ---
    //
    // `sa.trackNumber IS NULL` como primer criterio de orden es el truco para mandar al final las
    // canciones sin número de pista: en SQLite un booleano vale 0 (falso) o 1 (verdadero), así que
    // las que sí lo tienen (0) van antes que las que no (1).

    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN song_album sa ON sa.songId = s.id
        WHERE sa.albumId = :albumId
        ORDER BY sa.trackNumber IS NULL, sa.trackNumber, LOWER(s.title)
        """
    )
    abstract fun observeSongsOfAlbum(albumId: Long): Flow<List<SongWithRelations>>

    /**
     * Números de pista de un álbum. Van en una consulta aparte porque viven en la tabla de cruce y
     * [SongWithRelations] no los arrastra; la ficha del álbum los une con las canciones por id.
     */
    @Query("SELECT songId, trackNumber FROM song_album WHERE albumId = :albumId")
    abstract fun observeTrackPositions(albumId: Long): Flow<List<TrackPosition>>

    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN song_artist x ON x.songId = s.id
        WHERE x.artistId = :artistId
        ORDER BY LOWER(s.title)
        """
    )
    abstract fun observeSongsOfArtist(artistId: Long): Flow<List<SongWithRelations>>

    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN song_producer x ON x.songId = s.id
        WHERE x.producerId = :producerId
        ORDER BY LOWER(s.title)
        """
    )
    abstract fun observeSongsOfProducer(producerId: Long): Flow<List<SongWithRelations>>

    // --- Consultas de apoyo para la reconciliación ---

    @Query("SELECT filePath FROM songs")
    abstract suspend fun allSongPaths(): List<String>

    @Query("SELECT * FROM artists WHERE tagName = :tagName LIMIT 1")
    abstract suspend fun findArtistByTag(tagName: String): ArtistEntity?

    @Query("SELECT * FROM albums WHERE tagTitle = :tagTitle AND tagAlbumArtist = :tagAlbumArtist LIMIT 1")
    abstract suspend fun findAlbumByTag(tagTitle: String, tagAlbumArtist: String): AlbumEntity?

    @Query("SELECT * FROM producers WHERE tagName = :tagName LIMIT 1")
    abstract suspend fun findProducerByTag(tagName: String): ProducerEntity?

    /**
     * Búsquedas por el nombre VISIBLE (el editable), no por el de la etiqueta. Las usa el editor de
     * metadatos: si el usuario escribe un artista que ya existe pero fue renombrado, hay que
     * enlazarlo en vez de crear un duplicado (su `tagName` ya no coincide con lo que se ve).
     */
    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    abstract suspend fun findArtistByName(name: String): ArtistEntity?

    @Query("SELECT * FROM albums WHERE title = :title LIMIT 1")
    abstract suspend fun findAlbumByTitle(title: String): AlbumEntity?

    @Query("SELECT * FROM producers WHERE name = :name LIMIT 1")
    abstract suspend fun findProducerByName(name: String): ProducerEntity?

    /**
     * Número de pista y de disco de una canción. Viven en la tabla de cruce con el álbum (no en la
     * canción), y el modelo de dominio no los arrastra, así que el editor los pide aparte para
     * rellenar sus campos. Si la canción está en varios álbumes se coge el primero.
     */
    @Query("SELECT trackNumber FROM song_album WHERE songId = :songId LIMIT 1")
    abstract suspend fun trackNumberOf(songId: Long): Int?

    @Query("SELECT discNumber FROM song_album WHERE songId = :songId LIMIT 1")
    abstract suspend fun discNumberOf(songId: Long): Int?

    // --- Sugerencias para el autocompletado del editor ---

    @Query("SELECT name FROM artists ORDER BY LOWER(name)")
    abstract fun observeArtistNames(): Flow<List<String>>

    @Query("SELECT title FROM albums ORDER BY LOWER(title)")
    abstract fun observeAlbumTitles(): Flow<List<String>>

    @Query("SELECT name FROM producers ORDER BY LOWER(name)")
    abstract fun observeProducerNames(): Flow<List<String>>

    // --- Inserciones ---

    @Insert
    abstract suspend fun insertSong(song: SongEntity): Long

    @Insert
    abstract suspend fun insertArtist(artist: ArtistEntity): Long

    @Insert
    abstract suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    abstract suspend fun insertProducer(producer: ProducerEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongArtist(ref: SongArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongProducer(ref: SongProducerCrossRef)

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

    @Update
    abstract suspend fun updateProducer(producer: ProducerEntity)

    // --- Desenlazado (el editor rehace los enlaces de una canción desde cero) ---

    @Query("DELETE FROM song_artist WHERE songId = :songId")
    abstract suspend fun clearSongArtists(songId: Long)

    @Query("DELETE FROM song_album WHERE songId = :songId")
    abstract suspend fun clearSongAlbums(songId: Long)

    @Query("DELETE FROM song_producer WHERE songId = :songId")
    abstract suspend fun clearSongProducers(songId: Long)

    // --- Borrado y poda de huérfanos ---

    @Query("DELETE FROM songs WHERE filePath IN (:paths)")
    abstract suspend fun deleteSongsByPath(paths: List<String>)

    /** Reapunta una canción a su nueva ruta cuando el usuario ha movido el archivo (ver [reconcile]). */
    @Query("UPDATE songs SET filePath = :newPath WHERE filePath = :oldPath")
    abstract suspend fun updateSongPath(oldPath: String, newPath: String)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT artistId FROM song_artist)")
    abstract suspend fun pruneOrphanArtists()

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT albumId FROM song_album)")
    abstract suspend fun pruneOrphanAlbums()

    @Query("DELETE FROM producers WHERE id NOT IN (SELECT producerId FROM song_producer)")
    abstract suspend fun pruneOrphanProducers()

    // --- Reconciliación ---

    /**
     * Concilia un escaneo con lo persistido. Debe recibir el resultado del escaneo ya hecho
     * (el escaneo es lento y va fuera de la transacción); aquí solo se toca la base de datos.
     *
     * Se hace en tres fases, y el orden importa:
     *
     *  1. Se separan las rutas que han DESAPARECIDO del disco de las que han APARECIDO.
     *  2. Se emparejan por nombre de archivo: una ruta que desaparece y otra que aparece con el
     *     mismo nombre es, casi con total seguridad, el mismo archivo MOVIDO de carpeta. En ese
     *     caso solo se reapunta el `filePath` de la fila ([updateSongPath]), y así la canción
     *     conserva su id, sus ediciones del editor de metadatos, su carátula importada y sus
     *     enlaces con artistas, álbumes y productores. Si en vez de esto se borrara y se volviera
     *     a insertar, mover un archivo equivaldría a perder todo lo que el usuario hubiera puesto.
     *  3. Lo que queda sin emparejar sí son altas y bajas de verdad: se insertan y se borran.
     *
     * El emparejamiento por nombre asume que no hay dos archivos con el mismo nombre en carpetas
     * distintas, que es la misma suposición que ya hacen las playlists (ver `PlaylistRepository`).
     */
    @Transaction
    open suspend fun reconcile(scanned: List<ScannedSong>) {
        val existing = allSongPaths().toHashSet()
        val scannedPaths = HashSet<String>(scanned.size)
        for (s in scanned) scannedPaths.add(s.filePath)

        // Fase 1: bajas y altas candidatas.
        val gone = existing.filter { it !in scannedPaths }
        val appeared = scanned.filter { it.filePath !in existing }

        // Fase 2: las que casan por nombre de archivo son movimientos, no altas/bajas.
        // El valor del índice es una lista porque, aunque no debería, puede haber nombres repetidos:
        // se van consumiendo de uno en uno para no reapuntar dos canciones a la misma ruta.
        val goneByName = gone.groupByTo(HashMap()) { it.substringAfterLast('/') }
        val moved = HashSet<String>(gone.size)
        val inserted = ArrayList<ScannedSong>(appeared.size)

        for (s in appeared) {
            val oldPath = goneByName[s.filePath.substringAfterLast('/')]?.removeFirstOrNull()
            if (oldPath != null) {
                updateSongPath(oldPath, s.filePath)
                moved.add(oldPath)
            } else {
                inserted.add(s)
            }
        }

        // Fase 3: altas reales.
        for (s in inserted) {
            val songId = insertSong(s.toEntity())

            val artistName = s.artist ?: MusicScanner.UNKNOWN_ARTIST
            val artistId = getOrCreateArtist(artistName)
            insertSongArtist(SongArtistCrossRef(songId = songId, artistId = artistId))

            // El productor solo se enlaza si la etiqueta traía uno: al contrario que artista y
            // álbum, no inventamos un "Productor desconocido" que llenaría la pestaña de ruido.
            s.producer?.let { producerName ->
                val producerId = getOrCreateProducer(producerName)
                insertSongProducer(SongProducerCrossRef(songId = songId, producerId = producerId))
            }

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

        // Fase 3 (bis): bajas reales, es decir, las que han desaparecido sin reaparecer en otra carpeta.
        val removed = gone.filter { it !in moved }
        if (removed.isNotEmpty()) {
            deleteSongsByPath(removed)
            pruneOrphanArtists()
            pruneOrphanAlbums()
            pruneOrphanProducers()
        }
    }

    // --- Edición desde el editor de metadatos ---

    /**
     * Guarda de una vez todo lo que el editor puede tocar de una canción. Va en una sola
     * [Transaction] para que no exista un instante intermedio en el que la canción se haya quedado
     * sin artistas o sin álbumes (la UI observa la base de datos y lo pintaría).
     *
     * Los nombres de [artistNames]/[albumTitles]/[producerNames] REENLAZAN la canción: se busca la
     * entidad con ese nombre visible y, si no existe, se crea. Nunca se renombra un
     * artista/álbum/productor existente, porque eso afectaría a todas las demás canciones que
     * cuelgan de él.
     *
     * [trackNumber] y [discNumber] viven en la tabla de cruce canción↔álbum, así que hay que volver
     * a escribirlos al recrear los enlaces o se perderían.
     */
    @Transaction
    open suspend fun saveSongEdits(
        song: SongEntity,
        artistNames: List<String>,
        albumTitles: List<String>,
        producerNames: List<String>,
        trackNumber: Int?,
        discNumber: Int?
    ) {
        updateSong(song)

        val artists = artistNames.ifEmpty { listOf(MusicScanner.UNKNOWN_ARTIST) }
        val albums = albumTitles.ifEmpty { listOf(MusicScanner.UNKNOWN_ALBUM) }

        clearSongArtists(song.id)
        val artistIds = artists.map { resolveArtist(it) }
        for (artistId in artistIds) {
            insertSongArtist(SongArtistCrossRef(songId = song.id, artistId = artistId))
        }

        clearSongAlbums(song.id)
        // El "artista del álbum" con el que se crearía un álbum nuevo es el primero de la canción.
        val albumArtistName = artists.first()
        for (title in albums) {
            val albumId = resolveAlbum(title, albumArtistName, artistIds.first())
            insertSongAlbum(
                SongAlbumCrossRef(
                    songId = song.id,
                    albumId = albumId,
                    trackNumber = trackNumber,
                    discNumber = discNumber
                )
            )
        }

        // Los productores no tienen valor por defecto: si el usuario vacía el campo, la canción se
        // queda sin ninguno (y el productor huérfano desaparece de su pestaña).
        clearSongProducers(song.id)
        for (name in producerNames) {
            insertSongProducer(
                SongProducerCrossRef(songId = song.id, producerId = resolveProducer(name))
            )
        }

        // Un artista, álbum o productor del que ya no cuelga ninguna canción deja de tener sentido.
        pruneOrphanArtists()
        pruneOrphanAlbums()
        pruneOrphanProducers()
    }

    /** Busca el artista por su nombre visible, luego por el de etiqueta; si no existe, lo crea. */
    private suspend fun resolveArtist(name: String): Long {
        findArtistByName(name)?.let { return it.id }
        findArtistByTag(name)?.let { return it.id }
        return insertArtist(ArtistEntity(name = name, tagName = name, imageName = null))
    }

    /** Ídem para el productor. */
    private suspend fun resolveProducer(name: String): Long {
        findProducerByName(name)?.let { return it.id }
        findProducerByTag(name)?.let { return it.id }
        return insertProducer(ProducerEntity(name = name, tagName = name, imageName = null))
    }

    /** Ídem para el álbum. Al crearlo se enlaza con el artista principal de la canción. */
    private suspend fun resolveAlbum(title: String, albumArtist: String, artistId: Long): Long {
        findAlbumByTitle(title)?.let { return it.id }
        findAlbumByTag(title, albumArtist)?.let { return it.id }
        val albumId = insertAlbum(
            AlbumEntity(
                title = title,
                tagTitle = title,
                tagAlbumArtist = albumArtist,
                year = null,
                genres = emptyList(),
                imageName = null
            )
        )
        insertAlbumArtist(AlbumArtistCrossRef(albumId = albumId, artistId = artistId))
        return albumId
    }

    private suspend fun getOrCreateArtist(tagName: String): Long {
        findArtistByTag(tagName)?.let { return it.id }
        return insertArtist(ArtistEntity(name = tagName, tagName = tagName, imageName = null))
    }

    private suspend fun getOrCreateProducer(tagName: String): Long {
        findProducerByTag(tagName)?.let { return it.id }
        return insertProducer(ProducerEntity(name = tagName, tagName = tagName, imageName = null))
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
 * el usuario desde el editor cuando la canción sea efectivamente un remix. El productor tampoco
 * aparece aquí: se enlaza aparte en su tabla de cruce, igual que los artistas y los álbumes.
 */
private fun ScannedSong.toEntity(): SongEntity = SongEntity(
    filePath = filePath,
    title = title,
    duration = duration,
    year = year,
    genres = genres,
    lyrics = null,
    language = null,
    imageName = null,
    comment = null,
    ogTitle = null,
    ogArtist = null,
    ogAlbum = null,
    ogYear = null
)
