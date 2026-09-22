package com.untar.ultimusic.data.playlist

import android.content.Context
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Almacén de listas de reproducción. A diferencia del resto de la biblioteca —que vive en una base
 * de datos Room— las listas son **archivos de texto** en `UltiMusic/Playlists` (carpeta concedida
 * por Storage Access Framework, ver [SafStorage]), uno por lista:
 *
 *   - El **nombre del archivo** (sin la extensión `.txt`) es el nombre de la lista.
 *   - El **contenido** es un nombre de archivo de canción por línea, en orden de reproducción. Se
 *     guarda solo el nombre del archivo (el "basename": `cancion.mp3`, no la ruta entera), tal como
 *     pidió el diseño del proyecto.
 *
 * Se eligió texto plano en disco (y no Room) porque una lista es, conceptualmente, un documento
 * del usuario que debe poder verse y editarse desde fuera de la app; además así sobrevive a
 * reinstalaciones sin tocar la base de datos (siempre que el usuario vuelva a conceder `UltiMusic`,
 * ver la cabecera de [SafStorage]).
 *
 * Como los archivos NO son reactivos (Room reemite solo; un documento SAF, no), quien observe estas
 * listas debe volver a leer tras cada cambio. De eso se encarga `PlaylistsViewModel`.
 *
 * Todas las operaciones van en el hilo de E/S ([Dispatchers.IO]) porque tocan disco.
 */
class PlaylistRepository private constructor(private val appContext: Context) {

    /**
     * docPath de `UltiMusic/Playlists`. Con [createIfMissing] la crea si hace falta (para escribir);
     * si es `false` (para leer) y todavía no existe, devuelve null en vez de crearla solo para
     * comprobar que está vacía. Null también si `UltiMusic` no se ha concedido todavía.
     */
    private fun dir(createIfMissing: Boolean): String? {
        SafStorage.ensureUltiMusicRegistered(appContext)
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return null
        val root = SafStorage.ultiMusicDocPath(appContext) ?: return null
        return if (createIfMissing) {
            SafStorage.getOrCreateSubfolder(appContext, treeUri, root, "Playlists")
        } else {
            "$root/Playlists".takeIf { SafStorage.exists(appContext, it) }
        }
    }

    /** Nombres de todas las listas (archivos `.txt`), en orden alfabético e ignorando mayúsculas. */
    suspend fun listPlaylistNames(): List<String> = withContext(Dispatchers.IO) {
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@withContext emptyList()
        val dir = dir(createIfMissing = false) ?: return@withContext emptyList()
        SafStorage.listChildren(appContext, treeUri, dir)
            .filter { !it.isDirectory && it.name.endsWith(EXT) }
            .map { it.name.removeSuffix(EXT) }
            .sortedBy { it.lowercase() }
    }

    /** Los nombres de archivo (basenames) que contiene una lista, en orden. */
    suspend fun readFilenames(name: String): List<String> = withContext(Dispatchers.IO) {
        val dir = dir(createIfMissing = false) ?: return@withContext emptyList()
        val docPath = "$dir/$name$EXT"
        if (!SafStorage.exists(appContext, docPath)) return@withContext emptyList()
        runCatching {
            SafStorage.openInputStream(appContext, docPath)?.bufferedReader()?.use { it.readLines() }
        }.getOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /**
     * Reescribe entero el archivo de una lista con [filenames] (para reordenar o para editar la
     * pertenencia). Una escritura completa es más simple y segura que parchear líneas sueltas.
     */
    suspend fun setFilenames(name: String, filenames: List<String>) = withContext(Dispatchers.IO) {
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@withContext
        val dir = dir(createIfMissing = true) ?: return@withContext
        val docPath = SafStorage.getOrCreateFile(appContext, treeUri, dir, "$name$EXT", "text/plain")
            ?: return@withContext
        runCatching {
            SafStorage.openOutputStream(appContext, docPath)?.bufferedWriter()
                ?.use { it.write(filenames.joinToString("\n")) }
        }
        Unit
    }

    /** Crea una lista vacía si no existía ya. Devuelve false si el nombre no es válido o chocaba. */
    suspend fun createPlaylist(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@withContext false
        val dir = dir(createIfMissing = true) ?: return@withContext false
        if (SafStorage.exists(appContext, "$dir/$clean$EXT")) return@withContext false
        SafStorage.createFile(appContext, treeUri, dir, "$clean$EXT", "text/plain") != null
    }

    /** Borra el archivo de la lista (best-effort). */
    suspend fun deletePlaylist(name: String) = withContext(Dispatchers.IO) {
        val dir = dir(createIfMissing = false) ?: return@withContext
        runCatching { SafStorage.deleteRecursively(appContext, "$dir/$name$EXT") }
        Unit
    }

    /** Renombra la lista (renombra su archivo). Devuelve false si el destino ya existe o falla. */
    suspend fun renamePlaylist(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty() || !isValidName(clean)) return@withContext false
        val dir = dir(createIfMissing = false) ?: return@withContext false
        if (SafStorage.exists(appContext, "$dir/$clean$EXT")) return@withContext false
        SafStorage.renameTo(appContext, "$dir/$oldName$EXT", "$clean$EXT") != null
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
     * Rechaza nombres con caracteres que ningún sistema de archivos admite: separadores de ruta y el
     * resto de los que rechaza FAT32/exFAT, el sistema típico de la tarjeta donde vive
     * `UltiMusic/Playlists`. Si se dejaran pasar, la creación del documento SAF fallaría en
     * silencio más abajo (ver [createPlaylist]/[renamePlaylist]).
     *
     * Público (no `private`) para que la UI valide ANTES de tocar disco y pueda avisar con un toast
     * en vez de que la creación falle sin más (ver
     * `PlaylistsFragment.showNameDialog`/`AddToPlaylistDialogFragment.showCreateAndAdd`).
     */
    fun isValidName(name: String): Boolean = INVALID_NAME_CHARS.none { it in name }

    companion object {
        private const val EXT = ".txt"
        private val INVALID_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        @Volatile
        private var instance: PlaylistRepository? = null

        /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de *Store: hace falta un
         *  [Context] para leer la carpeta `UltiMusic` concedida (ver [SafStorage]), y así el resto de
         *  la app puede seguir pidiendo la instancia con [get] sin tener que pasarlo cada vez. */
        fun init(context: Context) {
            if (instance != null) return
            synchronized(this) {
                if (instance == null) instance = PlaylistRepository(context.applicationContext)
            }
        }

        fun get(): PlaylistRepository =
            instance ?: error("PlaylistRepository.init no se ha llamado todavía")
    }
}
