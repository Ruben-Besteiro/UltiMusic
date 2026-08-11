package com.untar.ultimusic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import coil.request.ImageRequest
import com.untar.ultimusic.data.db.LibraryDao
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.toDomain
import com.untar.ultimusic.data.scan.MusicLibraryObserver
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.AlbumTrack
import com.untar.ultimusic.model.GenreSummary
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.YouTubeUrl
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

    /** Subcarpetas de la lista gris (ajustes > Lista gris), reactivo: un cambio en cualquiera repinta la lista. */
    val greylistFolders: Flow<List<GreylistFolder>> =
        dao.observeGreylistFolders().map { list -> list.map { it.toDomain() } }

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

    /**
     * Pestaña de Géneros. A diferencia de las tres de arriba, no sale de una consulta SQL: el
     * género no vive en su propia tabla (ver [com.untar.ultimusic.data.db.Converters]), solo como
     * lista de texto dentro de cada canción, así que se deriva aquí, en Kotlin, a partir de
     * [songs]. Se agrupa por el texto EXACTO —igual que el resto de nombres de la aplicación, donde
     * dos grafías distintas cuentan como cosas distintas— y se ordena igual que las demás pestañas.
     */
    val genres: Flow<List<GenreSummary>> = songs.map { list ->
        list.asSequence()
            .flatMap { it.genres.asSequence() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> GenreSummary(name, count) }
            .sortedBy { it.name.lowercase() }
    }

    /** Canciones que llevan el género [genre] (comparación exacta, como [genres]). */
    fun songsOfGenre(genre: String): Flow<List<Song>> =
        songs.map { list -> list.filter { genre in it.genres } }

    // --- Fichas de detalle ---

    fun album(id: Long): Flow<AlbumSummary?> =
        dao.observeAlbumSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

    /**
     * El álbum tal cual está guardado (fila cruda + sus artistas), para el editor de metadatos de
     * álbum. A diferencia de [album] —que trae cifras agregadas de sus canciones, calculadas para la
     * ficha— aquí hace falta justo lo que el editor puede tocar: título editable, año, géneros,
     * portada propia y la lista completa de artistas enlazados.
     */
    fun albumEntityForEdit(id: Long): Flow<Pair<AlbumEntity, List<String>>?> =
        combine(dao.observeAlbumEntity(id), dao.observeAlbumArtistNames(id)) { entity, names ->
            entity?.let { it to names }
        }

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
     * Canciones sueltas por id, en el mismo orden que [ids] (la consulta no lo garantiza). Las
     * canciones que ya no existan se omiten. La usa [PlayerViewModel] al restaurar las colas de
     * reproducción tras un reinicio del proceso, cuando solo se guardaron los ids.
     */
    suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = dao.findSongsByIds(ids).associateBy { it.song.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    /** Canción de la fonoteca con esa ruta exacta, o null si no está catalogada. Ver [LibraryDao.findSongByPath]. */
    suspend fun songByPath(path: String): Song? = withContext(Dispatchers.IO) {
        dao.findSongByPath(path)?.toDomain()
    }

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

    // --- Lista gris ---
    //
    // Ninguno de estos tres llama a [reconcile]: activar/desactivar/quitar una subcarpeta es un
    // UPDATE en bloque sobre lo ya guardado (ver LibraryDao), instantáneo y sin reescanear nada.

    suspend fun addGreylistFolder(path: String) = withContext(Dispatchers.IO) {
        dao.addGreylistFolder(path)
    }

    suspend fun removeGreylistFolder(path: String) = withContext(Dispatchers.IO) {
        dao.removeGreylistFolder(path)
    }

    suspend fun setGreylistFolderExcluded(path: String, excluded: Boolean) = withContext(Dispatchers.IO) {
        dao.setGreylistFolderExcluded(path, excluded)
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

    /** Guardado completo desde el editor de metadatos de ÁLBUM (fila + reenlazado de artistas). */
    suspend fun saveAlbumEdits(album: AlbumEntity, artistNames: List<String>) =
        dao.saveAlbumEdits(album, artistNames)
    suspend fun updateProducer(producer: ProducerEntity) = dao.updateProducer(producer)

    /**
     * Guarda el enlace del videoclip elegido en el buscador del iPod. Al escribir en Room, el flujo
     * [songs] reemite solo y la canción que suena vuelve a llegar al reproductor ya con su `videoUrl`.
     *
     * Gestiona también la miniatura de reserva (best-effort), igual que el editor de metadatos: si
     * el enlace cambia de verdad, borra la miniatura anterior (si tenía) y descarga la del vídeo
     * nuevo. Así da igual por cuál de los dos caminos llegue el enlace: los dos dejan la miniatura
     * lista para cuando haga falta como carátula de reserva.
     */
    suspend fun setVideoUrl(song: Song, videoUrl: String?): String? = withContext(Dispatchers.IO) {
        if (videoUrl == song.videoUrl) return@withContext song.videoThumbnailName
        song.videoThumbnailName?.let { deleteVideoThumbnail(it) }
        val thumbnailName = videoUrl?.let { url ->
            YouTubeUrl.videoId(url)?.let { downloadVideoThumbnail(it, song.title) }
        }
        dao.setVideoUrl(songId = song.id, videoUrl = videoUrl, videoThumbnailName = thumbnailName)
        thumbnailName
    }

    /**
     * Guarda la letra elegida en el buscador de lrclib.net que abre el iPod al tocar el recuadro
     * de letra estando vacío (ver [IPodDialogFragment][com.untar.ultimusic.ui.player.IPodDialogFragment]).
     * Al escribir en Room, el flujo [songs] reemite solo y la canción que suena vuelve a llegar al
     * reproductor ya con su `lyrics`, igual que [setVideoUrl] con el enlace del videoclip.
     */
    suspend fun setLyrics(song: Song, lyrics: String?) = withContext(Dispatchers.IO) {
        if (lyrics == song.lyrics) return@withContext
        dao.setLyrics(songId = song.id, lyrics = lyrics)
    }

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
     * Copia la imagen elegida por el usuario a `~/UltiMusic/images` y devuelve el nombre de
     * archivo resultante (lo que se guarda en `imageName`), nombrado como [title].
     *
     * Se copia en vez de guardar la URI del sistema porque una URI del selector de fotos es un
     * permiso temporal: en cuanto el usuario borra o mueve la foto —o simplemente al reiniciar—
     * dejaría de poder leerse y la portada se rompería. Con la copia, la carátula es nuestra.
     *
     * Si la canción ya tenía una carátula propia, quien llame debe borrarla ANTES de llamar aquí
     * (ver [deleteCoverImage]): así, cuando el título no ha cambiado, el nombre vuelve a estar
     * libre y esta imagen ocupa el mismo hueco en vez de acabar en "Título (2).img".
     */
    suspend fun importCoverImage(uri: Uri, title: String): String = withContext(Dispatchers.IO) {
        val dir = CoverArt.imagesDir(appContext)
        val name = CoverArt.reserveFileName(dir, CoverArt.sanitizeFileName(title), "img", null)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            File(dir, name).outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se ha podido leer la imagen seleccionada")
        name
    }

    /**
     * Renombra una imagen ya guardada (carátula propia o miniatura de YouTube) para que coincida
     * con un título nuevo, sin volver a copiarla ni descargarla. Best-effort: si el archivo ya no
     * existe, no hace nada y devuelve el nombre tal cual estaba.
     *
     * @param suffix lo que va detrás del título saneado, p. ej. `" (video)"` para una miniatura o
     *   `""` para la carátula propia.
     */
    suspend fun renameImage(oldName: String, newTitle: String, suffix: String, ext: String): String =
        withContext(Dispatchers.IO) {
            val dir = CoverArt.imagesDir(appContext)
            val old = File(dir, oldName)
            if (!old.exists()) return@withContext oldName
            val baseName = CoverArt.sanitizeFileName(newTitle) + suffix
            val newName = CoverArt.reserveFileName(dir, baseName, ext, null)
            if (newName == oldName || old.renameTo(File(dir, newName))) newName else oldName
        }

    /**
     * Descarga la miniatura del vídeo [videoId], la recorta al cuadrado central y la guarda en
     * `~/UltiMusic/images` nombrada como [title]. Best-effort: si algo falla (sin red, vídeo
     * borrado...) devuelve null y la canción se queda sin miniatura, cayendo al recuadro negro.
     */
    suspend fun downloadVideoThumbnail(videoId: String, title: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(appContext)
                    .data("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                    .allowHardware(false)
                    .build()
                val bitmap = (CoverLoader.get(appContext).execute(request).drawable as? BitmapDrawable)
                    ?.bitmap ?: return@runCatching null

                val side = minOf(bitmap.width, bitmap.height)
                val cropped = Bitmap.createBitmap(
                    bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side
                )

                val dir = CoverArt.imagesDir(appContext)
                val baseName = "${CoverArt.sanitizeFileName(title)} (video)"
                val name = CoverArt.reserveFileName(dir, baseName, "jpg", null)
                File(dir, name).outputStream().use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                name
            }.getOrNull()
        }

    /**
     * Borra una canción de verdad: su archivo en disco, su fila en la base de datos (con los
     * artistas/álbumes/productores que se queden huérfanos), su carátula importada y su miniatura
     * de YouTube cacheada, si tenía. No toca las playlists; quien llame debe sacarla también de
     * ahí con `PlaylistRepository.removeSongFromAll`, igual que cuando un archivo desaparece solo.
     */
    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        File(song.filePath).delete()
        song.imageName?.let { deleteCoverImage(it) }
        song.videoThumbnailName?.let { deleteVideoThumbnail(it) }
        dao.deleteSong(song.id)
    }

    /** Borra una carátula importada que ya no usa nadie (best-effort). */
    suspend fun deleteCoverImage(imageName: String) = withContext(Dispatchers.IO) {
        runCatching { File(CoverArt.imagesDir(appContext), imageName).delete() }
        Unit
    }

    /** Gemela de [deleteCoverImage], para la miniatura de YouTube cacheada. */
    suspend fun deleteVideoThumbnail(name: String) = withContext(Dispatchers.IO) {
        runCatching { File(CoverArt.imagesDir(appContext), name).delete() }
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
