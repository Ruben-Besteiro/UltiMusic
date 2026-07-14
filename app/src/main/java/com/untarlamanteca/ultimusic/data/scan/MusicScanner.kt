package com.untarlamanteca.ultimusic.data.scan

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
    const val UNKNOWN_ALBUM = "Álbum desconocido"

    /**
     * El listado de carpetas en las que se buscan canciones.
     * Podríamos hacer que el usuario pueda modificar esta lista.
     */
    private fun scanRoots(): List<File> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val music = File(Environment.getExternalStorageDirectory(), "Music")
        val ultiMusic = File(Environment.getExternalStorageDirectory(), "UltiMusic")
        return listOf(downloads, music, ultiMusic)
    }

    /** Escanea las carpetas configuradas y devuelve las etiquetas crudas de cada archivo. */
    suspend fun scan(): List<ScannedSong> = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots()) {
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, files)
            }
        }
        files.mapNotNull { file -> readSong(file) }
    }

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
