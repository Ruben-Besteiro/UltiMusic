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

    const val UNKNOWN_ARTIST = ""
    const val UNKNOWN_ALBUM = ""

    /**
     * El listado de carpetas en las que se buscan canciones.
     * Solo se reconoce la carpeta UltiMusic, para que el usuario tenga un único
     * sitio claro donde guardar su música (y evitar duplicados con otras apps).
     */
    private fun scanRoots(): List<File> {
        val ultiMusic = File(Environment.getExternalStorageDirectory(), "UltiMusic")
        return listOf(ultiMusic)
    }

    /** Escanea las carpetas configuradas y devuelve las etiquetas crudas de cada archivo. */
    suspend fun scan(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): List<ScannedSong> = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots()) {
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, files)
            }
        }
        val total = files.size
        return@withContext files.mapIndexed { index, file ->
            onProgress(index + 1, total)
            readSong(file)
        }.filterNotNull()
    }

    /**
     * ¿Se puede leer ahora mismo la carpeta de la fonoteca?
     *
     * Sirve para no sacar conclusiones precipitadas: si la carpeta no es accesible (permiso
     * revocado, almacenamiento no montado…), TODAS las canciones parecerían haber desaparecido.
     * Antes de dar una por perdida —y borrarla de las listas, que son archivos del usuario y no
     * se pueden recuperar— hay que comprobar que el problema es de la canción y no de la carpeta.
     */
    suspend fun libraryFolderReadable(): Boolean = withContext(Dispatchers.IO) {
        scanRoots().any { it.isDirectory && it.canRead() }
    }

    /**
     * Busca un archivo de audio por su NOMBRE (sin carpetas) dentro de las rutas escaneadas.
     *
     * A diferencia de [scan], solo recorre directorios: no abre ningún archivo ni lee etiquetas,
     * así que es muy rápido. Sirve para relocalizar al vuelo una canción cuyo archivo el usuario ha
     * movido de carpeta y cuya ruta guardada, por tanto, ya no vale.
     */
    suspend fun findByFilename(filename: String): File? = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots()) {
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
