package com.untar.ultimusic.data.scan

import android.media.MediaMetadataRetriever
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// TODO: Añadir un FileObserver/ContentObserver para detectar cambios en la fonoteca

/**
 * Parte de "lectura" de la fonoteca. Fiel a la filosofía "libre de MediaStore": escanea
 * directamente el sistema de archivos y devuelve las etiquetas crudas de cada canción
 * ([ScannedSong]), sin IDs ni entidades. La persistencia y el emparejamiento con lo ya
 * guardado los hace la capa de datos.
 */
object MusicScanner {            // OBJECT = SINGLETON

    /** Extensiones de audio reconocidas. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mkv"
    )

    const val UNKNOWN_ARTIST = "Artista desconocido"
    const val UNKNOWN_ALBUM = ""

    /**
     * Resultado de [scan]: [newSongs] son las etiquetas de los archivos que NO estaban ya
     * catalogados (los únicos que hacía falta leer, ver [knownPaths][scan]), y [currentPaths] son
     * las rutas de TODOS los archivos de audio encontrados, estén catalogados o no. Hace falta este
     * segundo conjunto para que [com.untar.ultimusic.data.db.LibraryDao.reconcile] sepa qué
     * canciones ya catalogadas han desaparecido, cosa que [newSongs] por sí solo no puede decir (los
     * archivos ya conocidos ni siquiera están en esa lista).
     */
    data class ScanResult(val newSongs: List<ScannedSong>, val currentPaths: Set<String>)

    /**
     * El listado de carpetas en las que se buscan canciones: `UltiMusic` (la raíz por defecto,
     * siempre presente) más las carpetas raíz adicionales que el usuario haya añadido desde ajustes
     * (ver [com.untar.ultimusic.data.db.entities.LibraryRootEntity]). [MusicScanner] no conoce la
     * base de datos —sigue siendo "solo lectura de filesystem"—, así que [extraRoots] se lo pasa
     * quien sí la tiene ([com.untar.ultimusic.data.LibraryRepository]).
     */
    private fun scanRoots(extraRoots: List<File>): List<File> {
        val ultiMusic = File(Environment.getExternalStorageDirectory(), "UltiMusic")
        return listOf(ultiMusic) + extraRoots
    }

    /**
     * Escanea las carpetas configuradas ([scanRoots]) y devuelve las etiquetas crudas de cada
     * archivo NUEVO (ver [ScanResult]).
     *
     * @param knownPaths Rutas que la capa de datos ya tiene catalogadas (ver
     * [com.untar.ultimusic.data.db.LibraryDao.allSongPaths]). No se releen sus etiquetas: para una
     * ruta que no ha cambiado, [com.untar.ultimusic.data.db.LibraryDao.reconcile] no hace nada con
     * ellas, así que abrir [MediaMetadataRetriever] para cada una en cada reconciliación era trabajo
     * desperdiciado —la parte lenta del escaneo—. Solo se lee lo que no está en [knownPaths]:
     * archivos nuevos o movidos/renombrados desde otra ruta (que [LibraryDao.reconcile] necesita
     * completos para insertarlos o para su emparejamiento por nombre/duración).
     */
    suspend fun scan(
        extraRoots: List<File> = emptyList(),
        knownPaths: Set<String> = emptySet(),
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanResult = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots(extraRoots)) {
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, files)
            }
        }
        val currentPaths = files.mapTo(HashSet(files.size)) { it.absolutePath }
        val newFiles = files.filter { it.absolutePath !in knownPaths }
        val total = newFiles.size
        val newSongs = newFiles.mapIndexed { index, file ->
            onProgress(index + 1, total)
            readSong(file)
        }.filterNotNull()
        ScanResult(newSongs, currentPaths)
    }

    /**
     * ¿Se pueden leer ahora mismo TODAS las carpetas de la fonoteca?
     *
     * Sirve para no sacar conclusiones precipitadas: si una carpeta no es accesible (permiso
     * revocado, almacenamiento no montado…), sus canciones parecerían haber desaparecido. Antes de
     * dar una por perdida —y borrarla de las listas, que son archivos del usuario y no se pueden
     * recuperar— hay que comprobar que el problema es de la canción y no de la carpeta.
     *
     * Con varias carpetas raíz basta con que UNA no sea legible para que no sea seguro fiarse de lo
     * que falte de ahí, así que se exige que TODAS lo sean (`.all`, no `.any`).
     */
    suspend fun libraryFolderReadable(extraRoots: List<File> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        scanRoots(extraRoots).all { it.isDirectory && it.canRead() }
    }

    /**
     * Busca un archivo de audio por su NOMBRE (sin carpetas) dentro de las rutas escaneadas.
     *
     * A diferencia de [scan], solo recorre directorios: no abre ningún archivo ni lee etiquetas,
     * así que es muy rápido. Sirve para relocalizar al vuelo una canción cuyo archivo el usuario ha
     * movido de carpeta y cuya ruta guardada, por tanto, ya no vale.
     */
    suspend fun findByFilename(filename: String, extraRoots: List<File> = emptyList()): File? = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots(extraRoots)) {
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, files)
            }
        }
        files.firstOrNull { it.name == filename }
    }

    /**
     * Lee las etiquetas de UN archivo suelto, fuera del escaneo completo de [scanRoots].
     *
     * La usa `MainActivity` al reproducir un archivo llegado por "Abrir con UltiMusic": puede
     * vivir en cualquier carpeta (Descargas, otra app…), no solo bajo `~/UltiMusic`, así que nunca
     * pasaría por [scan]. Reutiliza el mismo lector de etiquetas para que se vea exactamente igual
     * que si estuviera en la fonoteca.
     */
    suspend fun readTags(file: File): ScannedSong? = withContext(Dispatchers.IO) { readSong(file) }

    /** Recorre recursivamente [dir] añadiendo los archivos de audio a [out]. */
    private fun collectAudioFiles(dir: File, out: MutableSet<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> collectAudioFiles(child, out)
                child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> out.add(child)
            }
        }
    }

    /** Lee las etiquetas de un archivo de audio y las empaqueta en un [ScannedSong]. */
    private fun readSong(file: File): ScannedSong? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

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
                filePath = file.absolutePath,
                title = titleTag ?: file.nameWithoutExtension,
                artist = artistTag ?: albumArtistTag,
                albumArtist = albumArtistTag ?: artistTag,
                album = albumTag,
                genres = genres,
                year = yearTag,
                duration = duration,
                producer = producer,
                trackNumber = trackNumber,
                discNumber = discNumber
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
