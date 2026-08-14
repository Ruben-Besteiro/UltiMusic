package com.untar.ultimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.untar.ultimusic.data.db.entities.AlbumArtistCrossRef
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.GreylistFolderEntity
import com.untar.ultimusic.data.db.entities.LibraryRootEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef
import com.untar.ultimusic.data.db.relations.AlbumGroupRow
import com.untar.ultimusic.data.db.relations.AlbumSummaryRow
import com.untar.ultimusic.data.db.relations.ArtistChannelCandidateRow
import com.untar.ultimusic.data.db.relations.CollageCandidateRow
import com.untar.ultimusic.data.db.relations.PersonSummaryRow
import com.untar.ultimusic.data.db.relations.SongPathDurationRow
import com.untar.ultimusic.data.db.relations.SongVideoRow
import com.untar.ultimusic.data.db.relations.SongWithRelations
import com.untar.ultimusic.data.scan.ScannedSong
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
    @Query("SELECT * FROM songs WHERE hiddenByGreylist = 0 ORDER BY LOWER(title)")
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
            -- Si el usuario no ha puesto año, cae al más tardío de sus canciones (ver el comentario
            -- de más abajo, en observeAlbumsOfArtist): un fallback SOLO para pintar, nunca se
            -- escribe en la fila del álbum.
            COALESCE(a.year, (SELECT MAX(s.year) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0)) AS year,
            -- Si el álbum tiene artistas propios enlazados (filas en album_artist) se listan TODOS,
            -- en el orden en que se enlazaron, no solo el primero (GROUP_CONCAT sobre la subconsulta
            -- ya ordenada por posición; ver también [LibraryRepository.albumArtistNames], que trae
            -- la misma lista para la ficha de detalle). Si el álbum no tiene ninguno enlazado, el
            -- fallback es el artista de la etiqueta original ([AlbumEntity.tagAlbumArtist], que no se
            -- toca al reenlazar artistas y por eso sobrevive aunque el usuario vacíe el álbum); si
            -- tampoco hay eso, el artista que más se repite entre las canciones del álbum, no el
            -- primero que salga: GROUP BY + ORDER BY COUNT(*) DESC halla la moda. Empate a nombre más
            -- corto (si una canción es una colaboración "A, B" y otra es solo "A", gana "A", que es
            -- el artista real del álbum); si hasta la longitud empata, a menor id de artista, para
            -- que el resultado sea determinista.
            COALESCE(
                (SELECT GROUP_CONCAT(name, ', ') FROM (
                    SELECT ar.name AS name FROM album_artist aa
                        JOIN artists ar ON ar.id = aa.artistId
                        WHERE aa.albumId = a.id ORDER BY aa.position
                )),
                NULLIF(a.tagAlbumArtist, ''),
                (SELECT ar.name FROM song_artist x
                    JOIN songs s ON s.id = x.songId
                    JOIN artists ar ON ar.id = x.artistId
                    WHERE s.albumId = a.id AND s.hiddenByGreylist = 0
                    GROUP BY x.artistId
                    ORDER BY COUNT(*) DESC, LENGTH(ar.name), x.artistId
                    LIMIT 1)
            ) AS artistName,
            (SELECT COUNT(*) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS songCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS totalDuration
        FROM albums a
        WHERE (:id IS NULL OR a.id = :id)
          AND EXISTS (
              SELECT 1 FROM songs s WHERE s.albumId = a.id AND s.hiddenByGreylist = 0
          )
        ORDER BY LOWER(a.title)
        """
    )
    abstract fun observeAlbumSummaries(id: Long?): Flow<List<AlbumSummaryRow>>

    /**
     * La fila cruda del álbum (no el resumen calculado de [observeAlbumSummaries]): la usa el editor
     * de metadatos de álbum, que necesita el título editable, el año, los géneros y la portada tal
     * cual están guardados, no cifras agregadas de sus canciones.
     */
    @Query("SELECT * FROM albums WHERE id = :id")
    abstract fun observeAlbumEntity(id: Long): Flow<AlbumEntity?>

    /**
     * Nombres de los artistas enlazados a un álbum, para rellenar su editor de metadatos. Van en el
     * orden en que se enlazaron ([AlbumArtistCrossRef.position]), no alfabético: es el mismo orden
     * en el que el usuario los escribió.
     */
    @Query(
        """
        SELECT ar.name FROM album_artist aa JOIN artists ar ON ar.id = aa.artistId
        WHERE aa.albumId = :albumId ORDER BY aa.position
        """
    )
    abstract fun observeAlbumArtistNames(albumId: Long): Flow<List<String>>

    @Query(
        """
        SELECT
            ar.id AS id,
            ar.name AS name,
            ar.imageName AS imageName,
            (SELECT COUNT(*) FROM song_artist x
                JOIN songs s ON s.id = x.songId
                WHERE x.artistId = ar.id AND s.hiddenByGreylist = 0) AS songCount,
            (SELECT COUNT(DISTINCT s.albumId) FROM song_artist x
                JOIN songs s ON s.id = x.songId
                WHERE x.artistId = ar.id AND s.hiddenByGreylist = 0) AS albumCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM song_artist x
                JOIN songs s ON s.id = x.songId
                WHERE x.artistId = ar.id AND s.hiddenByGreylist = 0) AS totalDuration,
            -- Popularidad: número de suscriptores del canal de YouTube que más se repite entre las
            -- canciones donde este artista es el PRINCIPAL, ya resueltos y guardados en la propia
            -- fila del artista (ver ArtistEntity.youtubeChannelSubscriberCount y
            -- LibraryRepository.refreshYouTubeStatsIfDue, que es quien calcula la moda de canal y
            -- descarta los que no responden). Null si todavía no se ha resuelto ninguno.
            ar.youtubeChannelSubscriberCount AS popularity
        FROM artists ar
        WHERE (:id IS NULL OR ar.id = :id)
          AND EXISTS (
              SELECT 1 FROM song_artist x JOIN songs s ON s.id = x.songId
              WHERE x.artistId = ar.id AND s.hiddenByGreylist = 0
          )
        ORDER BY LOWER(ar.name)
        """
    )
    abstract fun observeArtistSummaries(id: Long?): Flow<List<PersonSummaryRow>>

    /**
     * Gemela de [observeArtistSummaries]: los productores se tratan exactamente igual, CON UNA
     * EXCEPCIÓN explícita: la popularidad (visitas de canal) solo tiene sentido para artistas —así lo
     * pide el diseño del diálogo de ordenar, ver [com.untar.ultimusic.util.LibraryTab.PRODUCERS]—,
     * así que aquí se deja siempre a NULL en vez de repetir el cálculo de la moda de canal.
     */
    @Query(
        """
        SELECT
            p.id AS id,
            p.name AS name,
            p.imageName AS imageName,
            (SELECT COUNT(*) FROM song_producer x
                JOIN songs s ON s.id = x.songId
                WHERE x.producerId = p.id AND s.hiddenByGreylist = 0) AS songCount,
            (SELECT COUNT(DISTINCT s.albumId) FROM song_producer x
                JOIN songs s ON s.id = x.songId
                WHERE x.producerId = p.id AND s.hiddenByGreylist = 0) AS albumCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM song_producer x
                JOIN songs s ON s.id = x.songId
                WHERE x.producerId = p.id AND s.hiddenByGreylist = 0) AS totalDuration,
            NULL AS popularity
        FROM producers p
        WHERE (:id IS NULL OR p.id = :id)
          AND EXISTS (
              SELECT 1 FROM song_producer x JOIN songs s ON s.id = x.songId
              WHERE x.producerId = p.id AND s.hiddenByGreylist = 0
          )
        ORDER BY LOWER(p.name)
        """
    )
    abstract fun observeProducerSummaries(id: Long?): Flow<List<PersonSummaryRow>>

    // --- Canciones de una ficha de detalle ---
    //
    // El número de disco manda sobre el de pista (un CD2 pista 1 va después de todo el CD1, aunque
    // su número de pista sea menor). Sin disco asignado se trata como disco 1 con COALESCE, para que
    // esas canciones se mezclen con las del disco 1 en vez de irse al final.
    // `s.trackNumber IS NULL` como criterio de orden aparte es el truco para mandar al final las
    // canciones sin número de pista: en SQLite un booleano vale 0 (falso) o 1 (verdadero), así que
    // las que sí lo tienen (0) van antes que las que no (1).

    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        WHERE s.albumId = :albumId AND s.hiddenByGreylist = 0
        ORDER BY COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber, LOWER(s.title)
        """
    )
    abstract fun observeSongsOfAlbum(albumId: Long): Flow<List<SongWithRelations>>

    /**
     * Canciones de un artista/productor: primero por el año del álbum al que pertenecen, del más
     * reciente al más antiguo (sin año al final), luego por el título de ese álbum, luego por el
     * número de disco (sin disco asignado cuenta como disco 1), luego por su número de pista (sin
     * pista al final de ese álbum) y por último por el título de la canción.
     */
    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN song_artist x ON x.songId = s.id
        LEFT JOIN albums al ON al.id = s.albumId
        WHERE x.artistId = :artistId AND s.hiddenByGreylist = 0
        ORDER BY
            al.year IS NULL, al.year DESC, LOWER(al.title),
            COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber,
            LOWER(s.title)
        """
    )
    abstract fun observeSongsOfArtist(artistId: Long): Flow<List<SongWithRelations>>

    @Transaction
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN song_producer x ON x.songId = s.id
        LEFT JOIN albums al ON al.id = s.albumId
        WHERE x.producerId = :producerId AND s.hiddenByGreylist = 0
        ORDER BY
            al.year IS NULL, al.year DESC, LOWER(al.title),
            COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber,
            LOWER(s.title)
        """
    )
    abstract fun observeSongsOfProducer(producerId: Long): Flow<List<SongWithRelations>>

    // --- Álbumes de un artista/productor (carrusel horizontal de su ficha) ---
    //
    // Un álbum "es" de este artista/productor si alguna de sus canciones lo tiene entre sus
    // artistas/productores (mismo criterio que `albumCount` en observeArtistSummaries/
    // observeProducerSummaries de arriba, para que ambas cifras cuadren). Más reciente primero (sin
    // año al final), como en la captura de referencia.

    @Query(
        """
        SELECT
            a.id AS id,
            a.title AS title,
            a.imageName AS imageName,
            -- Fallback si el usuario no ha puesto año: el más tardío de sus canciones (nunca se
            -- escribe en la fila del álbum, solo se calcula para pintar y para ordenar el carrusel).
            COALESCE(a.year, (SELECT MAX(s.year) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0)) AS year,
            -- Si el álbum tiene artistas propios enlazados (filas en album_artist) se listan TODOS,
            -- en el orden en que se enlazaron, no solo el primero (GROUP_CONCAT sobre la subconsulta
            -- ya ordenada por posición; ver también [LibraryRepository.albumArtistNames], que trae
            -- la misma lista para la ficha de detalle). Si el álbum no tiene ninguno enlazado, el
            -- fallback es el artista de la etiqueta original ([AlbumEntity.tagAlbumArtist], que no se
            -- toca al reenlazar artistas y por eso sobrevive aunque el usuario vacíe el álbum); si
            -- tampoco hay eso, el artista que más se repite entre las canciones del álbum, no el
            -- primero que salga: GROUP BY + ORDER BY COUNT(*) DESC halla la moda. Empate a nombre más
            -- corto (si una canción es una colaboración "A, B" y otra es solo "A", gana "A", que es
            -- el artista real del álbum); si hasta la longitud empata, a menor id de artista, para
            -- que el resultado sea determinista.
            COALESCE(
                (SELECT GROUP_CONCAT(name, ', ') FROM (
                    SELECT ar.name AS name FROM album_artist aa
                        JOIN artists ar ON ar.id = aa.artistId
                        WHERE aa.albumId = a.id ORDER BY aa.position
                )),
                NULLIF(a.tagAlbumArtist, ''),
                (SELECT ar.name FROM song_artist x
                    JOIN songs s ON s.id = x.songId
                    JOIN artists ar ON ar.id = x.artistId
                    WHERE s.albumId = a.id AND s.hiddenByGreylist = 0
                    GROUP BY x.artistId
                    ORDER BY COUNT(*) DESC, LENGTH(ar.name), x.artistId
                    LIMIT 1)
            ) AS artistName,
            (SELECT COUNT(*) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS songCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS totalDuration
        FROM albums a
        WHERE EXISTS (
            SELECT 1 FROM songs s
            JOIN song_artist x ON x.songId = s.id
            WHERE s.albumId = a.id AND x.artistId = :artistId AND s.hiddenByGreylist = 0
        )
        ORDER BY year IS NULL, year DESC, LOWER(a.title)
        """
    )
    abstract fun observeAlbumsOfArtist(artistId: Long): Flow<List<AlbumSummaryRow>>

    /** Gemela de [observeAlbumsOfArtist]: los productores se tratan exactamente igual. */
    @Query(
        """
        SELECT
            a.id AS id,
            a.title AS title,
            a.imageName AS imageName,
            -- Fallback si el usuario no ha puesto año: el más tardío de sus canciones (nunca se
            -- escribe en la fila del álbum, solo se calcula para pintar y para ordenar el carrusel).
            COALESCE(a.year, (SELECT MAX(s.year) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0)) AS year,
            -- Si el álbum tiene artistas propios enlazados (filas en album_artist) se listan TODOS,
            -- en el orden en que se enlazaron, no solo el primero (GROUP_CONCAT sobre la subconsulta
            -- ya ordenada por posición; ver también [LibraryRepository.albumArtistNames], que trae
            -- la misma lista para la ficha de detalle). Si el álbum no tiene ninguno enlazado, el
            -- fallback es el artista de la etiqueta original ([AlbumEntity.tagAlbumArtist], que no se
            -- toca al reenlazar artistas y por eso sobrevive aunque el usuario vacíe el álbum); si
            -- tampoco hay eso, el artista que más se repite entre las canciones del álbum, no el
            -- primero que salga: GROUP BY + ORDER BY COUNT(*) DESC halla la moda. Empate a nombre más
            -- corto (si una canción es una colaboración "A, B" y otra es solo "A", gana "A", que es
            -- el artista real del álbum); si hasta la longitud empata, a menor id de artista, para
            -- que el resultado sea determinista.
            COALESCE(
                (SELECT GROUP_CONCAT(name, ', ') FROM (
                    SELECT ar.name AS name FROM album_artist aa
                        JOIN artists ar ON ar.id = aa.artistId
                        WHERE aa.albumId = a.id ORDER BY aa.position
                )),
                NULLIF(a.tagAlbumArtist, ''),
                (SELECT ar.name FROM song_artist x
                    JOIN songs s ON s.id = x.songId
                    JOIN artists ar ON ar.id = x.artistId
                    WHERE s.albumId = a.id AND s.hiddenByGreylist = 0
                    GROUP BY x.artistId
                    ORDER BY COUNT(*) DESC, LENGTH(ar.name), x.artistId
                    LIMIT 1)
            ) AS artistName,
            (SELECT COUNT(*) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS songCount,
            (SELECT COALESCE(SUM(s.duration), 0) FROM songs s
                WHERE s.albumId = a.id AND s.hiddenByGreylist = 0) AS totalDuration
        FROM albums a
        WHERE EXISTS (
            SELECT 1 FROM songs s
            JOIN song_producer x ON x.songId = s.id
            WHERE s.albumId = a.id AND x.producerId = :producerId AND s.hiddenByGreylist = 0
        )
        ORDER BY year IS NULL, year DESC, LOWER(a.title)
        """
    )
    abstract fun observeAlbumsOfProducer(producerId: Long): Flow<List<AlbumSummaryRow>>

    // --- Candidatas al collage de carátulas (álbum/artista/productor sin imagen propia) ---
    //
    // Lectura puntual (suspend, no Flow): las pide bajo demanda `GroupCoverFetcher` (ver
    // CoverArt.kt) al resolver la carátula, no la observa ninguna pantalla. Solo trae las tres
    // columnas de las que sale la carátula de cada canción (ver CoverArt.cover(context, song)),
    // en el mismo orden que vería el usuario al entrar en la ficha (mismo ORDER BY que
    // observeSongsOfAlbum/observeSongsOfArtist/observeSongsOfProducer), para que el collage se
    // forme con las primeras canciones que se listan. Sin LIMIT a propósito: el collage no tiene
    // tope de tamaño, así que hace falta poder mirar todas las candidatas si hiciera falta.

    @Query(
        """
        SELECT s.imageName AS imageName, s.filePath AS filePath,
            s.videoThumbnailName AS videoThumbnailName
        FROM songs s
        WHERE s.albumId = :albumId AND s.hiddenByGreylist = 0
        ORDER BY COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber, LOWER(s.title)
        """
    )
    abstract suspend fun collageCandidatesForAlbum(albumId: Long): List<CollageCandidateRow>

    @Query(
        """
        SELECT s.imageName AS imageName, s.filePath AS filePath,
            s.videoThumbnailName AS videoThumbnailName
        FROM songs s
        JOIN song_artist x ON x.songId = s.id
        LEFT JOIN albums al ON al.id = s.albumId
        WHERE x.artistId = :artistId AND s.hiddenByGreylist = 0
        ORDER BY
            al.year IS NULL, al.year DESC, LOWER(al.title),
            COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber,
            LOWER(s.title)
        """
    )
    abstract suspend fun collageCandidatesForArtist(artistId: Long): List<CollageCandidateRow>

    @Query(
        """
        SELECT s.imageName AS imageName, s.filePath AS filePath,
            s.videoThumbnailName AS videoThumbnailName
        FROM songs s
        JOIN song_producer x ON x.songId = s.id
        LEFT JOIN albums al ON al.id = s.albumId
        WHERE x.producerId = :producerId AND s.hiddenByGreylist = 0
        ORDER BY
            al.year IS NULL, al.year DESC, LOWER(al.title),
            COALESCE(s.discNumber, 1), s.trackNumber IS NULL, s.trackNumber,
            LOWER(s.title)
        """
    )
    abstract suspend fun collageCandidatesForProducer(producerId: Long): List<CollageCandidateRow>

    /**
     * Canciones sueltas por id, sin orden garantizado (quien llame debe reordenarlas). La usa
     * [com.untar.ultimusic.ui.PlayerViewModel] para reconstruir las colas de reproducción
     * al restaurar el estado guardado, ya que solo se persisten los ids, no las canciones enteras.
     */
    @Transaction
    @Query("SELECT * FROM songs WHERE id IN (:ids) AND hiddenByGreylist = 0")
    abstract suspend fun findSongsByIds(ids: List<Long>): List<SongWithRelations>

    /**
     * Busca una canción por su ruta exacta. La usa `MainActivity` al recibir un archivo por
     * "Abrir con UltiMusic": si ya está en la fonoteca, se reproduce esa fila (con su portada,
     * artistas, letra…) en vez de tratarlo como un archivo suelto sin catalogar.
     */
    @Transaction
    @Query("SELECT * FROM songs WHERE filePath = :path LIMIT 1")
    abstract suspend fun findSongByPath(path: String): SongWithRelations?

    // --- Consultas de apoyo para la reconciliación ---

    @Query("SELECT filePath FROM songs")
    abstract suspend fun allSongPaths(): List<String>

    /** Como [allSongPaths] pero con la duración, para el emparejamiento por contenido de [reconcile]. */
    @Query("SELECT filePath, duration FROM songs")
    abstract suspend fun allSongPathsAndDurations(): List<SongPathDurationRow>

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

    // --- Sugerencias para el autocompletado del editor ---

    @Query("SELECT name FROM artists ORDER BY LOWER(name)")
    abstract fun observeArtistNames(): Flow<List<String>>

    @Query("SELECT title FROM albums ORDER BY LOWER(title)")
    abstract fun observeAlbumTitles(): Flow<List<String>>

    @Query("SELECT name FROM producers ORDER BY LOWER(name)")
    abstract fun observeProducerNames(): Flow<List<String>>

    // --- Lista gris (ajustes > Lista gris) ---

    @Query("SELECT * FROM greylist_folders ORDER BY path")
    abstract fun observeGreylistFolders(): Flow<List<GreylistFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertGreylistFolder(folder: GreylistFolderEntity)

    @Query("DELETE FROM greylist_folders WHERE path = :path")
    abstract suspend fun deleteGreylistFolder(path: String)

    @Query("UPDATE greylist_folders SET excluded = :excluded WHERE path = :path")
    abstract suspend fun updateGreylistFolderExcluded(path: String, excluded: Boolean)

    /** Carpetas actualmente fuera de la lista (switch encendido), para [reconcile]. */
    @Query("SELECT path FROM greylist_folders WHERE excluded = 1")
    abstract suspend fun excludedGreylistFolderPaths(): List<String>

    @Query("UPDATE songs SET hiddenByGreylist = :hidden WHERE filePath = :path")
    abstract suspend fun setSongHidden(path: String, hidden: Boolean)

    /**
     * Oculta o muestra en bloque todas las canciones que cuelgan de [folderPath], sin tocar
     * ninguna otra columna. La usan [setGreylistFolderExcluded] y [removeGreylistFolder]: activar o
     * desactivar una subcarpeta es instantáneo (un UPDATE), nunca un reescaneo.
     */
    @Transaction
    open suspend fun setSongsHiddenUnderFolder(folderPath: String, hidden: Boolean) {
        val prefix = "$folderPath/"
        for (path in allSongPaths()) {
            if (path.startsWith(prefix)) setSongHidden(path, hidden)
        }
    }

    /**
     * Añade una subcarpeta a la lista gris ya excluida (switch encendido), ocultando de paso sus
     * canciones: quien la añade lo hace para dejarla fuera, no para tener que dar un segundo paso.
     */
    @Transaction
    open suspend fun addGreylistFolder(path: String) {
        insertGreylistFolder(GreylistFolderEntity(path = path, excluded = true))
        setSongsHiddenUnderFolder(path, hidden = true)
    }

    /** Switch de una subcarpeta: activarlo oculta sus canciones, desactivarlo las hace contar de nuevo. */
    @Transaction
    open suspend fun setGreylistFolderExcluded(path: String, excluded: Boolean) {
        updateGreylistFolderExcluded(path, excluded)
        setSongsHiddenUnderFolder(path, hidden = excluded)
    }

    /** Quita la papelera: la fila de la lista gris desaparece y sus canciones vuelven a verse. */
    @Transaction
    open suspend fun removeGreylistFolder(path: String) {
        deleteGreylistFolder(path)
        setSongsHiddenUnderFolder(path, hidden = false)
    }

    // --- Carpetas raíz de la fonoteca (ajustes > Carpetas de la fonoteca) ---
    //
    // A diferencia de la lista gris, estas SÍ cambian qué cuenta como parte de la biblioteca (no solo
    // su visibilidad), así que añadir/quitar una dispara un reescaneo (ver
    // LibraryRepository.addLibraryRoot/removeLibraryRoot). `UltiMusic` sigue siendo la raíz por
    // defecto y no tiene fila aquí: esta tabla solo guarda las raíces ADICIONALES.

    @Query("SELECT * FROM library_roots ORDER BY path")
    abstract fun observeLibraryRoots(): Flow<List<LibraryRootEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLibraryRoot(root: LibraryRootEntity)

    @Query("DELETE FROM library_roots WHERE path = :path")
    abstract suspend fun deleteLibraryRoot(path: String)

    /** Rutas guardadas, para pasárselas a [com.untar.ultimusic.data.scan.MusicScanner] en cada escaneo/búsqueda. */
    @Query("SELECT path FROM library_roots")
    abstract suspend fun libraryRootPaths(): List<String>

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

    // --- Desenlazado (el editor rehace los enlaces de una canción o de un álbum desde cero) ---

    @Query("DELETE FROM song_artist WHERE songId = :songId")
    abstract suspend fun clearSongArtists(songId: Long)

    @Query("DELETE FROM song_producer WHERE songId = :songId")
    abstract suspend fun clearSongProducers(songId: Long)

    @Query("DELETE FROM album_artist WHERE albumId = :albumId")
    abstract suspend fun clearAlbumArtists(albumId: Long)

    // --- Borrado y poda de huérfanos ---

    @Query("DELETE FROM songs WHERE filePath IN (:paths)")
    abstract suspend fun deleteSongsByPath(paths: List<String>)

    @Query("DELETE FROM songs WHERE id = :songId")
    abstract suspend fun deleteSongById(songId: Long)

    /**
     * Borra una canción por elección del usuario (menú de 3 puntos) y poda lo que se quede
     * huérfano, igual que hace la fase de bajas de [reconcile].
     */
    @Transaction
    open suspend fun deleteSong(songId: Long) {
        deleteSongById(songId)
        pruneOrphanArtists()
        pruneOrphanAlbums()
        pruneOrphanProducers()
    }

    /**
     * Guarda el enlace del videoclip de una canción. Es un UPDATE de una sola columna (en vez de
     * reescribir la fila entera con [updateSong]) porque lo llama el iPod justo después de que el
     * usuario elija un vídeo en el buscador: allí no hay formulario abierto, así que reescribir la
     * fila completa obligaría a leerla antes y arriesgaría pisar una edición hecha mientras tanto.
     */
    @Query(
        """
        UPDATE songs SET videoUrl = :videoUrl, videoThumbnailName = :videoThumbnailName
        WHERE id = :songId
        """
    )
    abstract suspend fun setVideoUrl(
        songId: Long,
        videoUrl: String?,
        videoThumbnailName: String?
    )

    /**
     * Guarda la letra elegida en el buscador de lrclib.net, mismo motivo que [setVideoUrl]: lo
     * llama el iPod cuando se toca el recuadro de letra vacío, sin ningún formulario abierto de
     * por medio.
     */
    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :songId")
    abstract suspend fun setLyrics(songId: Long, lyrics: String?)

    /**
     * Guarda el desplazamiento vídeo/audio, mismo motivo que [setVideoUrl]: lo llama el iPod al
     * soltar la regla de desplazamiento del modo vídeo (ver `ValueRuler.onReleased`), sin ningún
     * formulario abierto de por medio.
     */
    @Query("UPDATE songs SET videoOffsetMs = :offsetMs WHERE id = :songId")
    abstract suspend fun setVideoOffsetMs(songId: Long, offsetMs: Long)

    /** Todas las canciones con vídeo asignado, visibles (no ocultas por la lista gris): de ahí sale
     *  la lista de ids de vídeo que pedir a [com.untar.ultimusic.data.remote.YouTubeStatsApi] en el
     *  refresco diario de visitas (ver `LibraryRepository.refreshYouTubeStatsIfDue`). Una canción
     *  oculta no se enseña en ningún sitio, así que pedir sus visitas sería cuota tirada. */
    @Query("SELECT id, videoUrl FROM songs WHERE videoUrl IS NOT NULL AND hiddenByGreylist = 0")
    abstract suspend fun songsWithVideoUrl(): List<SongVideoRow>

    /** Guarda las visitas y el canal ya consultados a YouTube, mismo motivo que [setVideoUrl] para
     *  ser un UPDATE de dos columnas nada más: es un refresco en segundo plano sin formulario abierto
     *  de por medio, y reescribir la fila entera arriesgaría pisar una edición hecha mientras tanto. */
    @Query("UPDATE songs SET youtubeViewCount = :viewCount, youtubeChannelId = :channelId WHERE id = :songId")
    abstract suspend fun setYoutubeViewCount(songId: Long, viewCount: Long, channelId: String?)

    /** [setYoutubeViewCount] fila a fila, pero las [stats] de todo el refresco en una sola
     *  transacción: así no hay ventana intermedia en la que solo la mitad de las canciones tengan
     *  su dato actualizado si el proceso se interrumpe a medias. */
    @Transaction
    open suspend fun setYoutubeViewCounts(stats: Map<Long, Pair<Long, String?>>) {
        for ((songId, stat) in stats) setYoutubeViewCount(songId, stat.first, stat.second)
    }

    /**
     * Un canal candidato por cada combinación (artista, canal) que aparezca entre las canciones
     * donde ese artista es el PRINCIPAL (`x.position = 0`), con cuántas veces se repite. La usa
     * [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue] para hallar, en Kotlin,
     * la moda de cada artista (agrupando por `artistId` y quedándose con el `cnt` más alto) y
     * descartar los canales que no respondan al pedir sus estadísticas — "el más repetido que sea
     * válido" cae así al siguiente candidato de la lista, no se resuelve aquí en SQL.
     */
    @Query(
        """
        SELECT x.artistId AS artistId, s.youtubeChannelId AS channelId, COUNT(*) AS cnt
        FROM song_artist x
        JOIN songs s ON s.id = x.songId
        WHERE x.position = 0 AND s.hiddenByGreylist = 0 AND s.youtubeChannelId IS NOT NULL
        GROUP BY x.artistId, s.youtubeChannelId
        """
    )
    abstract suspend fun artistChannelCandidates(): List<ArtistChannelCandidateRow>

    /** El/los artista(s) PRINCIPAL(es) de una canción (`position = 0`); en la práctica como mucho
     *  uno, pero se deja como lista para no asumir que la posición 0 nunca se repite. La usa
     *  [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsForSong] para saber a qué
     *  artista(s) recalcular la popularidad tras cambiar el vídeo de una canción suelta, sin esperar
     *  al refresco diario completo. */
    @Query("SELECT artistId FROM song_artist WHERE songId = :songId AND position = 0")
    abstract suspend fun principalArtistIds(songId: Long): List<Long>

    /** Guarda el canal ya resuelto (y sus suscriptores) de UN artista; [channelId]/[subscriberCount]
     *  van null cuando ninguno de sus candidatos ha resultado válido, para no dejar un dato
     *  desactualizado. */
    @Query(
        "UPDATE artists SET youtubeChannelId = :channelId, youtubeChannelSubscriberCount = :subscriberCount WHERE id = :artistId"
    )
    abstract suspend fun setArtistYoutubeChannel(artistId: Long, channelId: String?, subscriberCount: Long?)

    /** [setArtistYoutubeChannel] artista a artista, pero todo el refresco en una sola transacción,
     *  mismo motivo que [setYoutubeViewCounts]. */
    @Transaction
    open suspend fun setArtistYoutubeChannels(resolved: Map<Long, Pair<String, Long>?>) {
        for ((artistId, channel) in resolved) {
            setArtistYoutubeChannel(artistId, channel?.first, channel?.second)
        }
    }

    /** Reapunta una canción a su nueva ruta cuando el usuario ha movido el archivo (ver [reconcile]). */
    @Query("UPDATE songs SET filePath = :newPath WHERE filePath = :oldPath")
    abstract suspend fun updateSongPath(oldPath: String, newPath: String)

    // Un artista puede quedarse sin ninguna canción propia (song_artist) y aun así seguir siendo
    // el artista de un álbum entero (album_artist) — el caso típico es precisamente el álbum
    // recién editado en AlbumEditorViewModel.save: se enlaza el artista al álbum y, si esta poda
    // solo mirara song_artist, lo borraría ahí mismo (misma transacción) llevándose por delante,
    // por el CASCADE de AlbumArtistCrossRef, el enlace álbum↔artista que se acababa de crear.
    @Query(
        """
        DELETE FROM artists
        WHERE id NOT IN (SELECT artistId FROM song_artist)
          AND id NOT IN (SELECT artistId FROM album_artist)
        """
    )
    abstract suspend fun pruneOrphanArtists()

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT albumId FROM songs WHERE albumId IS NOT NULL)")
    abstract suspend fun pruneOrphanAlbums()

    @Query("DELETE FROM producers WHERE id NOT IN (SELECT producerId FROM song_producer)")
    abstract suspend fun pruneOrphanProducers()

    // --- Fusión de álbumes duplicados (ver mergeDuplicateAlbums) ---

    /** [AlbumGroupRow.firstArtistId] sale de `album_artist` en la posición 0, el mismo criterio con
     * el que [reconcile] enlaza el primer artista al crear el álbum. */
    @Query(
        """
        SELECT a.id AS id, a.tagTitle AS tagTitle,
            (SELECT aa.artistId FROM album_artist aa
                WHERE aa.albumId = a.id ORDER BY aa.position LIMIT 1) AS firstArtistId
        FROM albums a
        """
    )
    abstract suspend fun albumGroupKeys(): List<AlbumGroupRow>

    /** Antes de fundir, el álbum que sobrevive ([keepId]) hereda el año/portada del duplicado
     * ([dupId]) SOLO si no tenía ya uno propio: nunca se pisa un valor que el usuario haya puesto. */
    @Query(
        """
        UPDATE albums
        SET year = COALESCE(year, (SELECT year FROM albums WHERE id = :dupId)),
            imageName = COALESCE(imageName, (SELECT imageName FROM albums WHERE id = :dupId))
        WHERE id = :keepId
        """
    )
    abstract suspend fun fillAlbumGaps(keepId: Long, dupId: Long)

    /** Reapunta de un plumazo todas las canciones del duplicado al álbum que sobrevive: al no haber
     * tabla de cruce (ver [SongEntity.albumId][com.untar.ultimusic.data.db.entities.SongEntity.albumId]),
     * no hay clave primaria compuesta que pueda entrar en conflicto, así que basta un UPDATE simple
     * (a diferencia de [moveAlbumArtistLinksTo], que sí necesita el `OR IGNORE` + borrado). */
    @Query("UPDATE songs SET albumId = :keepId WHERE albumId = :dupId")
    abstract suspend fun moveSongAlbumLinksTo(keepId: Long, dupId: Long)

    @Query("UPDATE OR IGNORE album_artist SET albumId = :keepId WHERE albumId = :dupId")
    abstract suspend fun moveAlbumArtistLinksTo(keepId: Long, dupId: Long)

    @Query("DELETE FROM album_artist WHERE albumId = :dupId")
    abstract suspend fun deleteAlbumArtistLinksOf(dupId: Long)

    @Query("DELETE FROM albums WHERE id = :id")
    abstract suspend fun deleteAlbumById(id: Long)

    // --- Reconciliación ---

    /**
     * Concilia un escaneo con lo persistido. Debe recibir el resultado del escaneo ya hecho
     * (el escaneo es lento y va fuera de la transacción); aquí solo se toca la base de datos.
     *
     * Se hace en fases, y el orden importa:
     *
     *  1. Se separan las rutas que han DESAPARECIDO del disco de las que han APARECIDO.
     *  2. Se emparejan por nombre de archivo: una ruta que desaparece y otra que aparece con el
     *     mismo nombre es, casi con total seguridad, el mismo archivo MOVIDO de carpeta.
     *  3. Lo que sigue sin emparejar se prueba por DURACIÓN exacta (ms): si el archivo se ha movido
     *     Y renombrado a la vez, ni la ruta ni el nombre sirven de ancla, pero la duración del audio
     *     no cambia por eso (a diferencia del título, que el editor puede haber sobrescrito). Solo se
     *     empareja si esa duración es única a ambos lados (una baja y un alta, ni más ni menos): con
     *     ambigüedad no se adivina, se deja caer al paso siguiente.
     *  4. Lo que queda sin emparejar por ninguna vía sí son altas y bajas de verdad: se insertan y
     *     se borran.
     *
     * En cualquiera de los emparejamientos (2 o 3) solo se reapunta el `filePath` de la fila
     * ([updateSongPath]), y así la canción conserva su id, sus ediciones del editor de metadatos, su
     * carátula importada y sus enlaces con artistas, álbumes y productores. Si en vez de esto se
     * borrara y se volviera a insertar, mover o renombrar un archivo equivaldría a perder todo lo
     * que el usuario hubiera puesto.
     *
     * El emparejamiento por nombre (fase 2) asume que no hay dos archivos con el mismo nombre en
     * carpetas distintas, que es la misma suposición que ya hacen las listas (ver
     * `PlaylistRepository`). El de duración (fase 3) no depende de esa suposición, pero exige
     * unicidad para no reapuntar dos canciones distintas entre sí por casualidad.
     */
    @Transaction
    open suspend fun reconcile(scanned: List<ScannedSong>) {
        val existingInfo = allSongPathsAndDurations()
        val existing = existingInfo.mapTo(HashSet(existingInfo.size)) { it.filePath }
        val scannedPaths = HashSet<String>(scanned.size)
        for (s in scanned) scannedPaths.add(s.filePath)

        // Carpetas actualmente fuera de la lista gris: lo que caiga bajo alguna de ellas entra o se
        // reapunta ya oculto, para no colarse visible hasta que se desactive el switch.
        val excludedPrefixes = excludedGreylistFolderPaths().map { "$it/" }
        fun isHidden(path: String) = excludedPrefixes.any { path.startsWith(it) }

        // Fase 1: bajas y altas candidatas.
        val gone = existingInfo.filter { it.filePath !in scannedPaths }
        val appeared = scanned.filter { it.filePath !in existing }

        // Fase 2: las que casan por nombre de archivo son movimientos, no altas/bajas.
        // El valor del índice es una lista porque, aunque no debería, puede haber nombres repetidos:
        // se van consumiendo de uno en uno para no reapuntar dos canciones a la misma ruta.
        val goneByName = gone.groupByTo(HashMap()) { it.filePath.substringAfterLast('/') }
        val moved = HashSet<String>(gone.size)
        val afterNamePass = ArrayList<ScannedSong>(appeared.size)

        for (s in appeared) {
            val match = goneByName[s.filePath.substringAfterLast('/')]?.removeFirstOrNull()
            if (match != null) {
                updateSongPath(match.filePath, s.filePath)
                // Si el archivo se ha movido dentro o fuera de una carpeta desactivada, su
                // visibilidad se recalcula con la ruta nueva.
                setSongHidden(s.filePath, isHidden(s.filePath))
                moved.add(match.filePath)
            } else {
                afterNamePass.add(s)
            }
        }

        // Fase 3: lo que no casó por nombre se prueba por duración exacta, pero solo si es única a
        // ambos lados entre lo que sigue sin emparejar (no confundir con "gone"/"appeared" enteros,
        // que ya han perdido lo que la fase 2 haya casado).
        val goneRemaining = gone.filter { it.filePath !in moved }
        val goneDurationCounts = goneRemaining.groupingBy { it.duration }.eachCount()
        val appearedDurationCounts = afterNamePass.groupingBy { it.duration }.eachCount()
        val goneByDuration = goneRemaining.associateBy { it.duration }
        val inserted = ArrayList<ScannedSong>(afterNamePass.size)

        for (s in afterNamePass) {
            val isUnique = goneDurationCounts[s.duration] == 1 && appearedDurationCounts[s.duration] == 1
            val match = if (isUnique) goneByDuration[s.duration] else null
            if (match != null) {
                updateSongPath(match.filePath, s.filePath)
                // Si el archivo se ha movido dentro o fuera de una carpeta desactivada, su
                // visibilidad se recalcula con la ruta nueva.
                setSongHidden(s.filePath, isHidden(s.filePath))
                moved.add(match.filePath)
            } else {
                inserted.add(s)
            }
        }

        // Fase 4: altas reales.
        for (s in inserted) {
            // El álbum se resuelve ANTES de insertar la canción: ahora vive como columna suya
            // (SongEntity.albumId), no en una tabla de cruce aparte, así que hace falta el id antes
            // de poder construir la fila. Sin etiqueta de álbum no hay fila de álbum, en vez de
            // agrupar todo lo suelto bajo un "Álbum desconocido" común.
            val albumTitle = s.album
            val albumId = if (albumTitle != null) {
                val albumArtistNames = splitTagNames(s.albumArtist ?: s.artist)
                // La clave de emparejamiento es el PRIMER artista, no la etiqueta completa: así una
                // colaboración ("Ado" en una pista, "Ado, XYZ" en otra del mismo álbum) cae en el
                // mismo álbum en vez de crear uno nuevo por cada combinación de colaboradores. Mismo
                // criterio que ya usaba resolveAlbum para los álbumes creados desde el editor.
                val albumArtistTag = albumArtistNames.firstOrNull() ?: ""
                val albumArtistIds = albumArtistNames.map { getOrCreateArtist(it) }
                getOrCreateAlbum(albumTitle, albumArtistTag, s.year, s.genres, albumArtistIds)
            } else {
                null
            }

            val songId = insertSong(s.toEntity(hidden = isHidden(s.filePath), albumId = albumId))

            // La etiqueta puede traer varios artistas separados por comas ("A, B"): se tratan como
            // varias personas, igual que hace el editor de metadatos manual (ver
            // EditText.splitValues en MetadataEditorDialogFragment), no como una sola con nombre
            // compuesto. Igual que el productor (ver más abajo), si la etiqueta no trae artista no
            // se inventa uno: la canción se queda sin artista.
            val artistIds = splitTagNames(s.artist).map { getOrCreateArtist(it) }
            for ((position, artistId) in artistIds.withIndex()) {
                insertSongArtist(SongArtistCrossRef(songId = songId, artistId = artistId, position = position))
            }

            // El productor solo se enlaza si la etiqueta traía uno: no inventamos un "Productor
            // desconocido" que llenaría la pestaña de ruido. Mismo tratamiento de comas que el
            // artista.
            for ((position, producerName) in splitTagNames(s.producer).withIndex()) {
                val producerId = getOrCreateProducer(producerName)
                insertSongProducer(SongProducerCrossRef(songId = songId, producerId = producerId, position = position))
            }
        }

        // Fase 4 (bis): bajas reales, es decir, las que han desaparecido sin reaparecer con otro
        // nombre, otra carpeta, o ambos.
        val removed = gone.mapNotNull { it.filePath.takeIf { path -> path !in moved } }
        if (removed.isNotEmpty()) {
            deleteSongsByPath(removed)
            pruneOrphanArtists()
            pruneOrphanAlbums()
            pruneOrphanProducers()
        }

        // Fase 4: fusiona álbumes duplicados. Se ejecuta siempre, no solo cuando ha habido altas o
        // bajas, porque también limpia duplicados que ya existieran de antes de este cambio.
        mergeDuplicateAlbums()
    }

    /**
     * Fusiona álbumes duplicados: misma [tagTitle][AlbumEntity.tagTitle] y mismo PRIMER artista
     * enlazado (posición 0 en `album_artist`). Puede haber más de una fila para lo que en realidad
     * es un solo álbum si distintas pistas traían una etiqueta de artista distinta (una
     * colaboración: "Ado" en una pista, "Ado, XYZ" en otra) — [reconcile] ya evita crear duplicados
     * nuevos por este motivo, pero esto limpia los que hubiera de antes.
     *
     * Sobrevive el de menor id (el más antiguo); el resto se funde en él: sus canciones y artistas
     * se repuntan, su año/portada rellenan los huecos del que sobrevive si este no los tenía, y la
     * fila duplicada se borra.
     */
    private suspend fun mergeDuplicateAlbums() {
        val groups = albumGroupKeys()
            .filter { it.firstArtistId != null }
            .groupBy { it.tagTitle to it.firstArtistId }
        for (rows in groups.values) {
            if (rows.size < 2) continue
            val ids = rows.map { it.id }.sorted()
            val keepId = ids.first()
            for (dupId in ids.drop(1)) {
                fillAlbumGaps(keepId, dupId)
                moveSongAlbumLinksTo(keepId, dupId)
                moveAlbumArtistLinks(keepId, dupId)
                deleteAlbumById(dupId)
            }
        }
    }

    /** `UPDATE OR IGNORE` + limpieza: si un artista ya estaba enlazado a [keepId] Y a [dupId] a la
     * vez (no debería pasar, pero por si acaso), el repunte se ignora para no romper la clave
     * primaria, y el resto se descarta al borrar lo que quede en [dupId]. */
    private suspend fun moveAlbumArtistLinks(keepId: Long, dupId: Long) {
        moveAlbumArtistLinksTo(keepId, dupId)
        deleteAlbumArtistLinksOf(dupId)
    }

    // --- Edición desde el editor de metadatos ---

    /**
     * Guarda de una vez todo lo que el editor puede tocar de una canción. Va en una sola
     * [Transaction] para que no exista un instante intermedio en el que la canción se haya quedado
     * sin artistas o sin álbumes (la UI observa la base de datos y lo pintaría).
     *
     * Los nombres de [artistNames]/[albumTitle]/[producerNames] REENLAZAN la canción: se busca la
     * entidad con ese nombre visible y, si no existe, se crea. Nunca se renombra un
     * artista/álbum/productor existente, porque eso afectaría a todas las demás canciones que
     * cuelgan de él.
     *
     * [albumTitle] null desengancha la canción de cualquier álbum (columna [SongEntity.albumId] a
     * null). El [song] que se recibe trae ya escritos [SongEntity.trackNumber]/[SongEntity.discNumber]
     * (son campos suyos, como el título o el año): aquí solo se resuelve a qué álbum apuntan.
     */
    @Transaction
    open suspend fun saveSongEdits(
        song: SongEntity,
        artistNames: List<String>,
        albumTitle: String?,
        producerNames: List<String>
    ) {
        clearSongArtists(song.id)
        val artistIds = artistNames.map { resolveArtist(it) }
        for ((position, artistId) in artistIds.withIndex()) {
            insertSongArtist(SongArtistCrossRef(songId = song.id, artistId = artistId, position = position))
        }

        // El "artista del álbum" con el que se crearía un álbum nuevo es el primero de la canción;
        // si la canción se ha quedado sin artista, el álbum se crea sin artista también.
        val albumArtistName = artistNames.firstOrNull() ?: ""
        val albumArtistId = artistIds.firstOrNull()
        val albumId = albumTitle?.let { resolveAlbum(it, albumArtistName, albumArtistId) }
        updateSong(song.copy(albumId = albumId))

        // Ni artista, ni álbum ni productor tienen valor por defecto: si el usuario vacía el campo,
        // la canción se queda sin ninguno (y el que se quede huérfano desaparece de su pestaña).
        clearSongProducers(song.id)
        for ((position, name) in producerNames.withIndex()) {
            insertSongProducer(
                SongProducerCrossRef(songId = song.id, producerId = resolveProducer(name), position = position)
            )
        }

        // Un artista, álbum o productor del que ya no cuelga ninguna canción deja de tener sentido.
        pruneOrphanArtists()
        pruneOrphanAlbums()
        pruneOrphanProducers()
    }

    /**
     * Gemela de [saveSongEdits] pero para el editor de metadatos de ÁLBUM (ver
     * [com.untar.ultimusic.ui.editor.AlbumEditorDialogFragment]): guarda la fila del álbum entera
     * (título, año, géneros, portada) y reenlaza sus artistas desde cero, igual que se reenlazan los
     * de una canción. No toca ninguna canción: solo la ficha del álbum en sí.
     */
    @Transaction
    open suspend fun saveAlbumEdits(album: AlbumEntity, artistNames: List<String>) {
        updateAlbum(album)

        clearAlbumArtists(album.id)
        for ((position, name) in artistNames.withIndex()) {
            insertAlbumArtist(
                AlbumArtistCrossRef(albumId = album.id, artistId = resolveArtist(name), position = position)
            )
        }

        // Un artista del que ya no cuelga ninguna canción deja de tener sentido (igual que tras
        // editar una canción); quitarlo de un álbum no borra sus canciones, así que en la práctica
        // esto rara vez borra nada, pero es la misma red de seguridad que usa saveSongEdits.
        pruneOrphanArtists()
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

    /**
     * Ídem para el álbum. Al crearlo se enlaza con el artista principal de la canción, si tiene
     * ([artistId] es null cuando la canción se ha quedado sin artista: el álbum se crea sin
     * ninguno, igual que un productor sin nombre no se enlaza a nada).
     */
    private suspend fun resolveAlbum(title: String, albumArtist: String, artistId: Long?): Long {
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
        if (artistId != null) {
            insertAlbumArtist(AlbumArtistCrossRef(albumId = albumId, artistId = artistId))
        }
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
        artistIds: List<Long>
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
        for ((position, artistId) in artistIds.withIndex()) {
            insertAlbumArtist(AlbumArtistCrossRef(albumId = albumId, artistId = artistId, position = position))
        }
        return albumId
    }
}

/**
 * Parte una etiqueta multivalor (nombres separados por comas) igual que hace el editor de
 * metadatos manual (ver `EditText.splitValues` en `MetadataEditorDialogFragment`): así un artista
 * o productor con varios colaboradores en la etiqueta original ("A, B") se enlaza como dos
 * personas, no como una sola con nombre compuesto.
 */
private fun splitTagNames(raw: String?): List<String> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/**
 * Convierte el resultado crudo del escaneo en su fila de la tabla de canciones. Los campos `og*`
 * (info de la canción original de un remix) quedan a null: no son datos de la etiqueta, los rellena
 * el usuario desde el editor cuando la canción sea efectivamente un remix. El productor tampoco
 * aparece aquí: se enlaza aparte en su tabla de cruce, igual que los artistas. [albumId] sí llega ya
 * resuelto (por [LibraryDao.reconcile], antes de llamar aquí), porque a diferencia del productor
 * vive como columna propia de esta fila, no en una tabla de cruce.
 */
private fun ScannedSong.toEntity(hidden: Boolean, albumId: Long?): SongEntity = SongEntity(
    filePath = filePath,
    title = title,
    duration = duration,
    year = year,
    genres = genres,
    lyrics = null,
    language = null,
    imageName = null,
    comment = null,
    videoUrl = null,
    videoThumbnailName = null,
    hiddenByGreylist = hidden,
    albumId = albumId,
    trackNumber = trackNumber,
    discNumber = discNumber,
    ogTitle = null,
    ogArtist = null,
    ogAlbum = null,
    ogYear = null
)
