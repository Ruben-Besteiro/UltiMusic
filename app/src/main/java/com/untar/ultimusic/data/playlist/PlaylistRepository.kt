package com.untar.ultimusic.data.playlist

import android.os.Environment
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Almacén de listas de reproducción. A diferencia del resto de la biblioteca —que vive en una base
 * de datos Room— las playlists son **archivos de texto** en `~/UltiMusic/Playlists`, uno por lista:
 *
 *   - El **nombre del archivo** (sin la extensión `.txt`) es el nombre de la playlist.
 *   - El **contenido** es un nombre de archivo de canción por línea, en orden de reproducción. Se
 *     guarda solo el nombre del archivo (el "basename": `cancion.mp3`, no la ruta entera), tal como
 *     pidió el diseño del proyecto.
 *
 * Se eligió texto plano en disco (y no Room) porque una playlist es, conceptualmente, un documento
 * del usuario que debe poder verse y editarse desde fuera de la app; además así sobrevive a
 * reinstalaciones sin tocar la base de datos.
 *
 * Como los archivos NO son reactivos (Room reemite solo; un `File`, no), quien observe estas listas
 * debe volver a leer tras cada cambio. De eso se encarga `PlaylistsViewModel`.
 *
 * Todas las operaciones van en el hilo de E/S ([Dispatchers.IO]) porque tocan disco.
 */
class PlaylistRepository private constructor() {

    /** Carpeta `~/UltiMusic/Playlists`. Se crea la primera vez que se necesita. */
    private fun dir(): File =
        File(Environment.getExternalStorageDirectory(), "UltiMusic/Playlists").apply { mkdirs() }

    /** El archivo que respalda una playlist. El nombre visible es el del archivo sin `.txt`. */
    private fun fileOf(name: String): File = File(dir(), "$name$EXT")

    /** Nombres de todas las playlists (archivos `.txt`), en orden alfabético e ignorando mayúsculas. */
    suspend fun listPlaylistNames(): List<String> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.map { it.name.removeSuffix(EXT) }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()
    }

    /** Los nombres de archivo (basenames) que contiene una playlist, en orden. */
    suspend fun readFilenames(name: String): List<String> = withContext(Dispatchers.IO) {
        val file = fileOf(name)
        if (!file.exists()) return@withContext emptyList()
        runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() } }
            .getOrDefault(emptyList())
    }

    /**
     * Reescribe entero el archivo de una playlist con [filenames] (para reordenar o para editar la
     * pertenencia). Una escritura completa es más simple y segura que parchear líneas sueltas.
     */
    suspend fun setFilenames(name: String, filenames: List<String>) = withContext(Dispatchers.IO) {
        runCatching { fileOf(name).writeText(filenames.joinToString("\n")) }
        Unit
    }

    /** Crea una playlist vacía si no existía ya. Devuelve false si el nombre no es válido o chocaba. */
    suspend fun createPlaylist(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val file = fileOf(clean)
        if (file.exists()) return@withContext false
        runCatching { file.createNewFile() }.getOrDefault(false)
    }

    /** Borra el archivo de la playlist (best-effort). */
    suspend fun deletePlaylist(name: String) = withContext(Dispatchers.IO) {
        runCatching { fileOf(name).delete() }
        Unit
    }

    /** Renombra la playlist (renombra su archivo). Devuelve false si el destino ya existe o falla. */
    suspend fun renamePlaylist(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val dest = fileOf(clean)
        if (dest.exists()) return@withContext false
        runCatching { fileOf(oldName).renameTo(dest) }.getOrDefault(false)
    }

    /** Añade una canción al final de la playlist si no estaba ya. */
    suspend fun addSong(name: String, filename: String) = withContext(Dispatchers.IO) {
        val current = readFilenames(name)
        if (filename !in current) setFilenames(name, current + filename)
    }

    /**
     * Quita una canción de la playlist. Si era la última, la playlist entera deja de tener sentido
     * (una lista vacía no aporta nada) y se borra en vez de dejar un archivo a 0 canciones.
     */
    suspend fun removeSong(name: String, filename: String) = withContext(Dispatchers.IO) {
        val current = readFilenames(name)
        if (filename in current) writeRemaining(name, current.filter { it != filename })
    }

    /**
     * Quita [filename] de TODAS las playlists que lo contengan, y devuelve cuáles se han tocado.
     *
     * Se usa cuando el archivo ha desaparecido de verdad de la fonoteca. Dejar la línea no serviría
     * de nada: la canción seguiría apareciendo en la lista y volvería a fallar cada vez que se
     * pulsara, porque `resolveSongs` ya no la encuentra en la biblioteca.
     */
    suspend fun removeSongFromAll(filename: String): List<String> = withContext(Dispatchers.IO) {
        listPlaylistNames().filter { name ->
            val current = readFilenames(name)
            if (filename !in current) return@filter false
            writeRemaining(name, current.filter { it != filename })
            true
        }
    }

    /** Guarda lo que quede tras quitar una canción, o borra la playlist si no queda ninguna. */
    private suspend fun writeRemaining(name: String, remaining: List<String>) {
        if (remaining.isEmpty()) deletePlaylist(name) else setFilenames(name, remaining)
    }

    /** Unión de los nombres de archivo presentes en CUALQUIER playlist (para el resaltado amarillo). */
    suspend fun filenamesInAnyPlaylist(): Set<String> = withContext(Dispatchers.IO) {
        listPlaylistNames().flatMap { readFilenames(it) }.toSet()
    }

    /** Nombres de las playlists que contienen [filename] (para marcar las casillas del diálogo). */
    suspend fun playlistsContaining(filename: String): Set<String> = withContext(Dispatchers.IO) {
        listPlaylistNames().filter { filename in readFilenames(it) }.toSet()
    }

    /**
     * Resuelve los nombres de archivo de una playlist a objetos [Song] reales, en orden. [byFilename]
     * es un índice basename→canción que arma quien llama (a partir de la biblioteca cargada). Las
     * entradas que ya no existen en la biblioteca (archivo borrado) se descartan.
     *
     * Limitación asumida por el diseño: se casa por basename, así que si dos archivos en carpetas
     * distintas comparten nombre, gana el que esté en el índice.
     */
    suspend fun resolveSongs(name: String, byFilename: Map<String, Song>): List<Song> =
        readFilenames(name).mapNotNull { byFilename[it] }

    /** Rechaza nombres con separadores de ruta, que romperían el mapeo nombre↔archivo. */
    private fun isValidName(name: String): Boolean =
        !name.contains('/') && !name.contains('\\')

    companion object {
        private const val EXT = ".txt"

        @Volatile
        private var instance: PlaylistRepository? = null

        fun get(): PlaylistRepository =
            instance ?: synchronized(this) {
                instance ?: PlaylistRepository().also { instance = it }
            }
    }
}
