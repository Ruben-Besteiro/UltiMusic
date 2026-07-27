package com.untarlamanteca.ultimusic.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.untarlamanteca.ultimusic.data.db.LibraryDao
import com.untarlamanteca.ultimusic.data.db.UltiMusicDatabase
import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.ProducerEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.data.db.toDomain
import com.untarlamanteca.ultimusic.data.scan.MusicLibraryObserver
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.AlbumSummary
import com.untarlamanteca.ultimusic.model.AlbumTrack
import com.untarlamanteca.ultimusic.model.PersonSummary
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fachada de la biblioteca: la ÚNICA fuente de verdad de los modelos. Orquesta el escaneo del
 * filesystem ([MusicScanner]) y la base de datos ([UltiMusicDatabase]), expone flujos de dominio
 * que observa la UI y ofrece las operaciones de edición (que se reflejan al instante vía Room).
 */
class LibraryRepository private constructor(
    private val dao: LibraryDao,
    private val appContext: Context
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var libraryObserver: MusicLibraryObserver? = null

    /** Canciones persistidas, reactivas: cualquier edición reemite la lista. */
    val songs: Flow<List<Song>> =
        dao.observeSongs().map { list -> list.map { it.toDomain() } }

    /** Nombres ya existentes, para el autocompletado del editor de metadatos. */
    val artistNames: Flow<List<String>> = dao.observeArtistNames()
    val albumTitles: Flow<List<String>> = dao.observeAlbumTitles()
    val producerNames: Flow<List<String>> = dao.observeProducerNames()

    // --- Pestañas de Álbumes / Artistas / Productores ---
    //
    // El `null` de estas llamadas significa "sin filtrar por id": devuelve la lista entera. Con un
    // id concreto, la misma consulta sirve para la ficha de detalle (ver los métodos de abajo).

    val albums: Flow<List<AlbumSummary>> =
        dao.observeAlbumSummaries(null).map { rows -> rows.map { it.toDomain() } }

    val artists: Flow<List<PersonSummary>> =
        dao.observeArtistSummaries(null).map { rows -> rows.map { it.toDomain() } }

    val producers: Flow<List<PersonSummary>> =
        dao.observeProducerSummaries(null).map { rows -> rows.map { it.toDomain() } }

    // --- Fichas de detalle ---

    fun album(id: Long): Flow<AlbumSummary?> =
        dao.observeAlbumSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

    fun artist(id: Long): Flow<PersonSummary?> =
        dao.observeArtistSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

    fun producer(id: Long): Flow<PersonSummary?> =
        dao.observeProducerSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

    /**
     * Canciones de un álbum, ya ordenadas por número de pista y con ese número al lado.
     *
     * El número vive en la tabla de cruce y la relación de la canción no lo arrastra, así que se
     * pide en una segunda consulta y se unen aquí con [combine]: cada vez que cualquiera de los dos
     * flujos emite, se recalcula la lista combinada. El orden lo marca la consulta de canciones.
     */
    fun albumTracks(albumId: Long): Flow<List<AlbumTrack>> =
        combine(
            dao.observeSongsOfAlbum(albumId),
            dao.observeTrackPositions(albumId)
        ) { songs, positions ->
            val numberBySongId = positions.associate { it.songId to it.trackNumber }
            songs.map { AlbumTrack(it.toDomain(), numberBySongId[it.song.id]) }
        }

    fun artistSongs(id: Long): Flow<List<Song>> =
        dao.observeSongsOfArtist(id).map { list -> list.map { it.toDomain() } }

    fun producerSongs(id: Long): Flow<List<Song>> =
        dao.observeSongsOfProducer(id).map { list -> list.map { it.toDomain() } }

    /**
     * Reconcilia lo que hay en disco con lo guardado: escanea (lento, fuera de transacción) y
     * delega en el DAO la inserción de novedades y el borrado de lo que ya no existe. Las
     * ediciones del usuario nunca se pisan.
     */
    suspend fun reconcile(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val scanned = MusicScanner.scan(onProgress)
        dao.reconcile(scanned)
    }

    fun startWatchingLibraryChanges() {
        if (libraryObserver != null) return

        val ultiMusic = File(Environment.getExternalStorageDirectory(), "UltiMusic")
        if (!ultiMusic.exists() || !ultiMusic.isDirectory) return

        libraryObserver = MusicLibraryObserver(ultiMusic, observerScope) {
            observerScope.launch {
                reconcile()
            }
        }.apply {
            startWatching()
        }
    }

    fun stopWatchingLibraryChanges() {
        libraryObserver?.stopWatching()
        libraryObserver = null
    }

    /**
     * Devuelve una ruta que se puede reproducir para [song], o null si el archivo ya no está en
     * ninguna parte de la fonoteca.
     *
     * Existe porque la ruta guardada puede quedarse obsoleta: [reconcile] escanea leyendo las
     * etiquetas de todos los archivos, tarda segundos y no escribe en la base de datos hasta el
     * final, así que entre que el usuario mueve un archivo y termina la siguiente reconciliación hay
     * una ventana en la que la biblioteca sigue apuntando a la carpeta antigua. Reproducir esa ruta
     * hacía que ExoPlayer fallara en silencio y se quedara clavado en 0:00.
     *
     * Si el archivo no está donde decía la base de datos, se busca por nombre con
     * [MusicScanner.findByFilename] (un recorrido de carpetas, sin leer etiquetas: barato) y, si
     * aparece, se corrige la fila de paso para no tener que volver a buscarlo nunca más.
     */
    suspend fun resolvePlayablePath(song: Song): String? = withContext(Dispatchers.IO) {
        val stored = File(song.filePath)
        if (stored.exists()) return@withContext song.filePath

        val relocated = MusicScanner.findByFilename(stored.name) ?: return@withContext null
        // Best-effort: si la reconciliación se nos ha adelantado, la fila antigua ya no existe y el
        // UPDATE no afecta a nadie. La ruta encontrada sigue siendo válida para reproducir.
        runCatching { dao.updateSongPath(song.filePath, relocated.absolutePath) }
        relocated.absolutePath
    }

    /**
     * ¿Se puede leer la carpeta de la fonoteca? Lo consulta quien vaya a dar una canción por
     * perdida (ver [MusicScanner.libraryFolderReadable]).
     */
    suspend fun libraryFolderReadable(): Boolean = MusicScanner.libraryFolderReadable()

    // --- Ediciones (se reflejan al instante en el Flow [songs]) ---

    suspend fun updateSong(song: SongEntity) = dao.updateSong(song)
    suspend fun updateArtist(artist: ArtistEntity) = dao.updateArtist(artist)
    suspend fun updateAlbum(album: AlbumEntity) = dao.updateAlbum(album)
    suspend fun updateProducer(producer: ProducerEntity) = dao.updateProducer(producer)

    /** Pista y disco de una canción (viven en la tabla de cruce, no en la fila de la canción). */
    suspend fun trackAndDisc(songId: Long): Pair<Int?, Int?> =
        dao.trackNumberOf(songId) to dao.discNumberOf(songId)

    /** Guardado completo desde el editor de metadatos (fila + reenlazado de artistas y álbumes). */
    suspend fun saveSongEdits(
        song: SongEntity,
        artistNames: List<String>,
        albumTitles: List<String>,
        producerNames: List<String>,
        trackNumber: Int?,
        discNumber: Int?
    ) = dao.saveSongEdits(song, artistNames, albumTitles, producerNames, trackNumber, discNumber)

    /**
     * Copia la imagen elegida por el usuario a la carpeta privada de la app y devuelve el nombre de
     * archivo resultante (lo que se guarda en `imageName`).
     *
     * Se copia en vez de guardar la URI del sistema porque una URI del selector de fotos es un
     * permiso temporal: en cuanto el usuario borra o mueve la foto —o simplemente al reiniciar—
     * dejaría de poder leerse y la portada se rompería. Con la copia, la carátula es nuestra.
     */
    suspend fun importCoverImage(uri: Uri, songId: Long): String = withContext(Dispatchers.IO) {
        val dir = CoverArt.coversDir(appContext)
        val name = "song_${songId}_${System.currentTimeMillis()}.img"
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            File(dir, name).outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se ha podido leer la imagen seleccionada")
        name
    }

    /** Borra una carátula importada que ya no usa nadie (best-effort). */
    suspend fun deleteCoverImage(imageName: String) = withContext(Dispatchers.IO) {
        runCatching { File(CoverArt.coversDir(appContext), imageName).delete() }
        Unit
    }

    /**
     * Copia (unidireccional, solo para inspección) la base de datos interna a
     * `~/UltiMusic/databases/`, porque en algunos móviles no se puede entrar en /data/data.
     * Best-effort: cualquier fallo se ignora. Antes hace checkpoint del WAL para que la copia
     * sea consistente.
     */
    suspend fun exportDatabaseCopy() = withContext(Dispatchers.IO) {
        runCatching {
            val db = UltiMusicDatabase.get(appContext)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            val dbFile = appContext.getDatabasePath(UltiMusicDatabase.DB_NAME)
            val destDir = File(Environment.getExternalStorageDirectory(), "UltiMusic/databases")
            destDir.mkdirs()

            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(dbFile.path + suffix)
                if (src.exists()) {
                    src.copyTo(File(destDir, dbFile.name + suffix), overwrite = true)
                }
            }
        }
        Unit
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(
                    dao = UltiMusicDatabase.get(context).libraryDao(),
                    appContext = context.applicationContext
                ).also { instance = it }
            }
    }
}
