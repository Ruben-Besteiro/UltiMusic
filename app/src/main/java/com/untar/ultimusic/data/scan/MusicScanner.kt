package com.untar.ultimusic.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parte de "lectura" de la fonoteca. Fiel a la filosofía "libre de MediaStore": escanea
 * directamente las carpetas concedidas por Storage Access Framework (ver
 * [com.untar.ultimusic.util.SafStorage]) y devuelve las etiquetas crudas de cada canción
 * ([ScannedSong]), sin IDs ni entidades. La persistencia y el emparejamiento con lo ya
 * guardado los hace la capa de datos.
 */
object MusicScanner {            // OBJECT = SINGLETON

    /** Extensiones de audio reconocidas. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mkv"
    )

    /** Archivos cuyas etiquetas se leen a la vez en [scan]. */
    private const val READ_PARALLELISM = 6 // TEMPORAL-BENCH

    const val UNKNOWN_ARTIST ="Artista desconocido"
    const val UNKNOWN_ALBUM = ""

    /**
     * Resultado de [scan]: [newSongs] son las etiquetas de los archivos que NO estaban ya
     * catalogados (los únicos que hacía falta leer, ver [knownPaths][scan]), y [currentPaths] son
     * los docPath de TODOS los archivos de audio encontrados, estén catalogados o no. Hace falta este
     * segundo conjunto para que [com.untar.ultimusic.data.db.LibraryDao.reconcile] sepa qué
     * canciones ya catalogadas han desaparecido, cosa que [newSongs] por sí solo no puede decir (los
     * archivos ya conocidos ni siquiera están en esa lista).
     */
    data class ScanResult(val newSongs: List<ScannedSong>, val currentPaths: Set<String>)

    /**
     * Escanea las carpetas concedidas ahora mismo (ver [SafStorage.grantedRoots]: `UltiMusic` más las
     * raíces adicionales que el usuario haya añadido desde ajustes) y devuelve las etiquetas crudas de
     * cada archivo NUEVO (ver [ScanResult]).
     *
     * @param knownPaths docPath ya catalogados (ver [com.untar.ultimusic.data.db.LibraryDao.allSongPaths]).
     * No se releen sus etiquetas: para uno que no ha cambiado, [com.untar.ultimusic.data.db.LibraryDao.reconcile]
     * no hace nada con él, así que reabrir [MediaMetadataRetriever] para cada uno en cada
     * reconciliación era trabajo desperdiciado —la parte lenta del escaneo—. Solo se lee lo que no
     * está en [knownPaths]: archivos nuevos o movidos/renombrados desde otra ruta (que
     * [LibraryDao.reconcile] necesita completos para insertarlos o para su emparejamiento por
     * nombre/duración).
     */
    suspend fun scan(
        context: Context,
        knownPaths: Set<String> = emptySet(),
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanResult = withContext(Dispatchers.IO) {
        val tWalk = System.nanoTime() // TEMPORAL-BENCH
        val entries = LinkedHashMap<String, SafStorage.SafEntry>()
        for ((rootDocPath, treeUri) in SafStorage.grantedRoots()) {
            collectAudioFiles(context, treeUri, rootDocPath, entries)
        }
        android.util.Log.d("UMBench", "walk ${entries.size} files in ${(System.nanoTime() - tWalk) / 1_000_000} ms") // TEMPORAL-BENCH
        val currentPaths = entries.keys.toHashSet()
        val newEntries = entries.values.filter { it.docPath !in knownPaths }
        val total = newEntries.size
        // Lectura de etiquetas en paralelo: cada archivo es casi todo espera de E/S (SAF + servicio
        // de medios), así que varias lecturas a la vez acortan mucho el escaneo inicial. El
        // semáforo acota la concurrencia; awaitAll conserva el orden original de [newEntries].
        val t0 = System.nanoTime() // TEMPORAL-BENCH
        val done = AtomicInteger(0)
        val gate = Semaphore(READ_PARALLELISM)
        val newSongs = coroutineScope {
            newEntries.map { entry ->
                async {
                    val song = gate.withPermit { readSong(context, entry) }
                    onProgress(done.incrementAndGet(), total)
                    song
                }
            }.awaitAll().filterNotNull()
        }
        android.util.Log.d("UMBench", "read ${newEntries.size} files par=$READ_PARALLELISM in ${(System.nanoTime() - t0) / 1_000_000} ms") // TEMPORAL-BENCH
        ScanResult(newSongs, currentPaths)
    }

    /**
     * ¿Se pueden leer ahora mismo TODAS las carpetas concedidas?
     *
     * Sirve para no sacar conclusiones precipitadas: si una carpeta no es accesible (permiso
     * revocado por el usuario desde Ajustes del sistema...), sus canciones parecerían haber
     * desaparecido. Antes de dar una por perdida —y borrarla de las listas, que son archivos del
     * usuario y no se pueden recuperar— hay que comprobar que el problema es de la carpeta y no de
     * la canción.
     *
     * Sin ninguna carpeta concedida (instalación recién migrada, pendiente del flujo de
     * re-concesión) tampoco se considera "legible": no hay nada de qué fiarse todavía.
     */
    suspend fun libraryFolderReadable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val roots = SafStorage.grantedRoots()
        roots.isNotEmpty() && roots.all { (_, treeUri) -> SafStorage.isRootReadable(context, treeUri) }
    }

    /**
     * Busca un archivo de audio por su NOMBRE (sin carpetas) dentro de las carpetas concedidas.
     *
     * A diferencia de [scan], solo recorre directorios: no abre ningún archivo ni lee etiquetas,
     * así que es muy rápido. Sirve para relocalizar al vuelo una canción cuyo archivo el usuario ha
     * movido de carpeta y cuyo docPath guardado, por tanto, ya no vale.
     */
    suspend fun findByFilename(context: Context, filename: String): String? = withContext(Dispatchers.IO) {
        val entries = LinkedHashMap<String, SafStorage.SafEntry>()
        for ((rootDocPath, treeUri) in SafStorage.grantedRoots()) {
            collectAudioFiles(context, treeUri, rootDocPath, entries)
        }
        entries.values.firstOrNull { it.name == filename }?.docPath
    }

    /**
     * Lee las etiquetas de UN archivo suelto por su [uri], fuera de las carpetas concedidas.
     *
     * La usa `MainActivity` al reproducir un archivo llegado por "Abrir con UltiMusic": puede venir
     * de cualquier otra app (Descargas, un gestor de archivos...), no solo de una carpeta concedida,
     * así que nunca pasaría por [scan]. Reutiliza el mismo lector de etiquetas para que se vea
     * exactamente igual que si estuviera en la fonoteca; el [ScannedSong.filePath] resultante es el
     * propio [uri] en texto (ver [SafStorage.uriForDomainPath] sobre las formas que puede tomar).
     */
    suspend fun readTagsFromUri(context: Context, uri: Uri): ScannedSong? =
        withContext(Dispatchers.IO) { readSongFromUri(context, uri, uri.toString(), dateAdded = 0L) }

    /**
     * Todos los archivos de audio de [rootDocPath] dentro de [treeUri], sin leer ninguna etiqueta
     * (barato, como [findByFilename]). La usa
     * [com.untar.ultimusic.data.LibraryRepository.relinkAfterGrant] para recorrer una carpeta recién
     * concedida y reemparejar canciones catalogadas antes de la migración a SAF.
     */
    suspend fun collectAudioEntries(context: Context, treeUri: Uri, rootDocPath: String): List<SafStorage.SafEntry> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, SafStorage.SafEntry>()
            collectAudioFiles(context, treeUri, rootDocPath, out)
            out.values.toList()
        }

    /** Recorre recursivamente [rootDocPath] dentro de [treeUri], añadiendo los archivos de audio a [out]
     *  (indexados por su docPath, para no repetir si dos raíces se solaparan por error). */
    private fun collectAudioFiles(
        context: Context,
        treeUri: Uri,
        rootDocPath: String,
        out: MutableMap<String, SafStorage.SafEntry>
    ) {
        val pending = ArrayDeque<String>()
        pending.addLast(rootDocPath)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            for (entry in SafStorage.listChildren(context, treeUri, dir)) {
                when {
                    entry.isDirectory -> pending.addLast(entry.docPath)
                    entry.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS ->
                        out[entry.docPath] = entry
                }
            }
        }
    }

    /** Lee las etiquetas de la canción encontrada en [entry] (ver [collectAudioFiles]). */
    private fun readSong(context: Context, entry: SafStorage.SafEntry): ScannedSong? {
        val uri = SafStorage.resolveUri(entry.docPath) ?: return null
        return readSongFromUri(context, uri, entry.docPath, entry.lastModified)
    }

    /** Lee las etiquetas de un archivo de audio abrible por [uri] y las empaqueta en un
     *  [ScannedSong], con [filePath] como su clave de dominio (docPath o URI completo, según quien
     *  llame). */
    private fun readSongFromUri(context: Context, uri: Uri, filePath: String, dateAdded: Long): ScannedSong? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            fun meta(key: Int): String? =
                retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val titleTag = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artistTag = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val albumArtistTag = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val albumTag = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val genreTag = meta(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val yearTag = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val producer = meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            val trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull()
            val discNumber = meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull()

            val genres = genreTag?.let { listOf(it) } ?: emptyList()

            return ScannedSong(
                filePath = filePath,
                title = titleTag ?: filePath.substringAfterLast('/').substringBeforeLast('.'),
                artist = artistTag ?: albumArtistTag,
                albumArtist = albumArtistTag ?: artistTag,
                album = albumTag,
                genres = genres,
                year = yearTag,
                duration = duration,
                producer = producer,
                trackNumber = trackNumber,
                discNumber = discNumber,
                dateAdded = dateAdded
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
