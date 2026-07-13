package com.untarlamanteca.ultimusic.data

import android.media.MediaMetadataRetriever
import android.os.Environment
import com.untarlamanteca.ultimusic.model.Album
import com.untarlamanteca.ultimusic.model.Artist
import com.untarlamanteca.ultimusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// TODO: Añadir un FileObserver/ContentObserver para detectar cambios en la fonoteca

/**
 * Fuente de canciones de UltiMusic. Fiel a la filosofía "libre de MediaStore": escanea
 * directamente el sistema de archivos, sin consultar el índice del sistema.
 *
 * En esencia es la parte de "lectura" de la fonoteca.
 */
object MusicRepository {            // OBJECT = SINGLETON

    /** Extensiones de audio reconocidas. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mkv"
    )

    /**
     * El listado de carpetas en las que se buscan canciones
     * Podríamos hacer que el usuario pueda modificar esta lista
     **/
    private fun scanRoots(): List<File> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val music = File(Environment.getExternalStorageDirectory(), "Music")
        val ultiMusic = File(Environment.getExternalStorageDirectory(), "UltiMusic")
        return listOf(downloads, music, ultiMusic)
    }

    /**
     * Escanea las carpetas configuradas y creamos sus modelos llamando a readSong
     **/
    suspend fun scanSongs(): List<Song> = withContext(Dispatchers.IO) {
        val files = LinkedHashSet<File>()
        for (root in scanRoots()) {
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, files)
            }
        }

        var nextId = 1L
        files
            .mapNotNull { file -> readSong(file, nextId++) }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Recorre recursivamente [dir] añadiendo los archivos de audio a [out].
     **/
    private fun collectAudioFiles(dir: File, out: MutableSet<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> collectAudioFiles(child, out)
                child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> out.add(child)
            }
        }
    }

    /**
     * Esto se llama cuando la aplicación obtiene permisos de almacenamiento
     * Populamos los datos con MediaStore pero solo en el instante inicial (no contradice la filosofía)
     * Y por último creamos el modelo
    **/
    private fun readSong(file: File, id: Long): Song? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            fun meta(key: Int): String? =
                retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val titleTag = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artistTag = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val albumTag = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val genreTag = meta(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val yearTag = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val genres = genreTag?.let { listOf(it) } ?: emptyList()
            val producer = meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER)

            /** Esto de aquí es lo interesante puesto que aquí nacen los modelos **/
            val artists = listOf(
                Artist(
                    name = artistTag ?: UNKNOWN_ARTIST,
                    imageName = null)
            )
            val albums = listOf(
                Album(
                    title = albumTag ?: UNKNOWN_ALBUM,
                    artists = artists,
                    year = yearTag,
                    genres = genres,
                    imageName = null
                )
            )

            return Song(
                id = id,
                filePath = file.absolutePath,
                title = titleTag ?: file.nameWithoutExtension,
                artists = artists,
                albums = albums,
                duration = duration,
                year = yearTag,
                genres = genres,
                imageName = null,
                comment = null,
                producer = producer,
                ogTitle = titleTag,
                ogArtist = artistTag,
                ogAlbum = albumTag,
                ogYear = yearTag
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    const val UNKNOWN_ARTIST = "Artista desconocido"
    const val UNKNOWN_ALBUM = "Álbum desconocido"
}
