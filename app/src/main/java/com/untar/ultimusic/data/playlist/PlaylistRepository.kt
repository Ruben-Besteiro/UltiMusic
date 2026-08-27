package com.untar.ultimusic.data.playlist

import android.os.Environment
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Almacén de listas de reproducción. A diferencia del resto de la biblioteca —que vive en una base
 * de datos Room— las listas son **archivos de texto** en `~/UltiMusic/Playlists`, uno por lista:
 *
 *   - El **nombre del archivo** (sin la extensión `.txt`) es el nombre de la lista.
 *   - El **contenido** es un nombre de archivo de canción por línea, en orden de reproducción. Se
 *     guarda solo el nombre del archivo (el "basename": `cancion.mp3`, no la ruta entera), tal como
 *     pidió el diseño del proyecto.
 *
 * Se eligió texto plano en disco (y no Room) porque una lista es, conceptualmente, un documento
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

    /** El archivo que respalda una lista. El nombre visible es el del archivo sin `.txt`. */
    private fun fileOf(name: String): File = File(dir(), "$name$EXT")

    /** Nombres de todas las listas (archivos `.txt`), en orden alfabético e ignorando mayúsculas. */
    suspend fun listPlaylistNames(): List<String> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.map { it.name.removeSuffix(EXT) }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()
    }

    /** Los nombres de archivo (basenames) que contiene una lista, en orden. */
    suspend fun readFilenames(name: String): List<String> = withContext(Dispatchers.IO) {
        val file = fileOf(name)
        if (!file.exists()) return@withContext emptyList()
        runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() } }
            .getOrDefault(emptyList())
    }

    /**
     * Reescribe entero el archivo de una lista con [filenames] (para reordenar o para editar la
     * pertenencia). Una escritura completa es más simple y segura que parchear líneas sueltas.
     */
    suspend fun setFilenames(name: String, filenames: List<String>) = withContext(Dispatchers.IO) {
        runCatching { fileOf(name).writeText(filenames.joinToString("\n")) }
        Unit
    }

    /** Crea una lista vacía si no existía ya. Devuelve false si el nombre no es válido o chocaba. */
    suspend fun createPlaylist(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val file = fileOf(clean)
        if (file.exists()) return@withContext false
        runCatching { file.createNewFile() }.getOrDefault(false)
    }

    /** Borra el archivo de la lista (best-effort). */
    suspend fun deletePlaylist(name: String) = withContext(Dispatchers.IO) {
        runCatching { fileOf(name).delete() }
        Unit
    }

    /** Renombra la lista (renombra su archivo). Devuelve false si el destino ya existe o falla. */
    suspend fun renamePlaylist(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val dest = fileOf(clean)
        if (dest.exists()) return@withContext false
        runCatching { fileOf(oldName).renameTo(dest) }.getOrDefault(false)
    }

    /**
     * Añade una o varias canciones al final de la lista, saltándose las que ya estuvieran.
     * Sirve tanto para una canción suelta (lista de un elemento) como para un álbum entero de una
     * vez (ver [com.untar.ultimusic.ui.library.DetailDialogFragment]): una sola escritura en vez
     * de una por canción evita reescribir el archivo N veces para lo mismo.
     */
    suspend fun addSongs(name: String, filenames: List<String>) = withContext(Dispatchers.IO) {
        val current = readFilenames(name)
        val missing = filenames.filter { it !in current }
        if (missing.isNotEmpty()) setFilenames(name, current + missing)
    }

    /**
     * Quita una o varias canciones de la lista. Si no queda ninguna, la lista entera deja de
     * tener sentido (una lista vacía no aporta nada) y se borra en vez de dejar un archivo a 0
     * canciones (ver [writeRemaining]).
     */
    suspend fun removeSongs(name: String, filenames: List<String>) = withContext(Dispatchers.IO) {
        val current = readFilenames(name)
        val remaining = current.filterNot { it in filenames }
        if (remaining.size != current.size) writeRemaining(name, remaining)
    }

    /**
     * Quita [filename] de TODAS las listas que lo contengan, y devuelve cuáles se han tocado.
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

    /** Guarda lo que quede tras quitar una canción, o borra la lista si no queda ninguna. */
    private suspend fun writeRemaining(name: String, remaining: List<String>) {
        if (remaining.isEmpty()) deletePlaylist(name) else setFilenames(name, remaining)
    }

    /**
     * Nombres de las listas que contienen TODAS las canciones de [filenames] (para marcar las
     * casillas del diálogo de "Añadir a lista"). Con una sola canción, "todas" es justo esa una;
     * así sirve igual para una canción suelta que para un álbum entero.
     */
    suspend fun playlistsContainingAll(filenames: List<String>): Set<String> = withContext(Dispatchers.IO) {
        if (filenames.isEmpty()) return@withContext emptySet()
        listPlaylistNames().filter { name -> filenames.all { it in readFilenames(name) } }.toSet()
    }

    /**
     * Unión de TODOS los nombres de archivo que aparecen en CUALQUIER lista, para la etiqueta
     * predefinida "En ninguna lista" (ver [com.untar.ultimusic.data.LibraryRepository.resolveSongsOfTag]).
     */
    suspend fun allFilenamesInAnyPlaylist(): Set<String> = withContext(Dispatchers.IO) {
        listPlaylistNames().flatMapTo(mutableSetOf()) { readFilenames(it) }
    }

    /**
     * Resuelve los nombres de archivo de una lista a objetos [Song] reales, en orden. [byFilename]
     * es un índice basename→canción que arma quien llama (a partir de la biblioteca cargada). Las
     * entradas que ya no existen en la biblioteca (archivo borrado) se descartan.
     *
     * Limitación asumida por el diseño: se casa por basename, así que si dos archivos en carpetas
     * distintas comparten nombre, gana el que esté en el índice.
     */
    suspend fun resolveSongs(name: String, byFilename: Map<String, Song>): List<Song> =
        readFilenames(name).mapNotNull { byFilename[it] }

    /**
     * Rechaza nombres con caracteres que el sistema de archivos no admite: separadores de ruta y el
     * resto de los que rechaza FAT32/exFAT, el sistema típico de la tarjeta donde vive
     * `~/UltiMusic/Playlists`. Si se dejaran pasar, `File.createNewFile()`/`renameTo()` fallarían en
     * silencio más abajo (ver [createPlaylist]/[renamePlaylist]).
     *
     * Público (no `private`, a diferencia de antes) para que la UI valide ANTES de tocar disco y
     * pueda avisar con un toast en vez de que la creación falle sin más (ver
     * `PlaylistsFragment.showNameDialog`/`AddToPlaylistDialogFragment.showCreateAndAdd`).
     */
    fun isValidName(name: String): Boolean = INVALID_NAME_CHARS.none { it in name }

    companion object {
        private const val EXT = ".txt"
        private val INVALID_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        @Volatile
        private var instance: PlaylistRepository? = null

        fun get(): PlaylistRepository =
            instance ?: synchronized(this) {
                instance ?: PlaylistRepository().also { instance = it }
            }
    }
}
