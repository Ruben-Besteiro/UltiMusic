package com.untar.ultimusic.ui.playlists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.SortPreferences
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.PlaylistResumeStore
import com.untar.ultimusic.util.SortOption
import com.untar.ultimusic.util.sortedByOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Estado de la pestaña de Listas. Vive en el ámbito de la ACTIVIDAD (`by activityViewModels()`)
 * para que lo compartan la pestaña de Listas, el diálogo de "añadir a lista" y la pestaña de
 * Canciones (que la usa para [forgetSong] al eliminar una canción).
 *
 * A diferencia de [LibraryViewModel], la fuente aquí son ARCHIVOS ([PlaylistRepository]), que no
 * reemiten solos al cambiar. Por eso hay un [tick]: cada vez que algo muta una lista se
 * incrementa, y los flujos derivados se recalculan. Combinamos además con la lista de canciones de
 * la biblioteca para poder resolver los nombres de archivo a canciones reales (recuento y duración).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlaylistRepository.get()
    private val library = LibraryRepository.get(app)

    /** Se incrementa tras cada cambio en disco para forzar la relectura de los flujos derivados. */
    private val tick = MutableStateFlow(0)

    /** Criterio de orden de la pestaña, persistido (ver [SortPreferences]), igual que en
     *  [com.untar.ultimusic.ui.SongsViewModel] y [com.untar.ultimusic.ui.library.LibraryViewModel]. */
    private val _sort = MutableStateFlow(SortPreferences.get(LibraryTab.PLAYLISTS))
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    /**
     * Resúmenes de todas las listas (nombre, nº de canciones, duración total), ya ordenados. Depende
     * del [tick], de la biblioteca y de [_sort]: si cambian los archivos, se reescanea la música o se
     * cambia el orden, se repinta.
     */
    val playlists: StateFlow<List<PlaylistSummary>> =
        combine(tick, library.songs, _sort) { _, songs, sort -> songs to sort }
            .mapLatest { (songs, sort) -> buildSummaries(songs).sortedByOption(sort) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(option: SortOption) {
        _sort.value = option
        SortPreferences.save(LibraryTab.PLAYLISTS, option)
    }

    /**
     * Unión de todos los nombres de archivo que están en alguna lista, reactiva al mismo [tick] que
     * [playlists]. La usa [com.untar.ultimusic.ui.library.TagsViewModel] (vía
     * `bindPlaylistFilenames`) para calcular la etiqueta predefinida "En ninguna lista" sin que
     * [com.untar.ultimusic.data.LibraryRepository] tenga que depender de [PlaylistRepository]
     * directamente (ver el comentario de `LibraryRepository.tagSummaries`).
     */
    val allFilenamesInPlaylists: StateFlow<Set<String>> =
        tick.mapLatest { repository.allFilenamesInAnyPlaylist() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Canciones de UNA lista, reactivas al [tick] compartido y a la biblioteca — igual que
     * [playlists], pero resueltas para una sola lista en vez de resumidas para todas. La usa
     * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment] para que su ficha se
     * entere al instante de cualquier cambio de pertenencia hecho desde cualquier otra pantalla
     * (p. ej. quitar la canción actual de esta misma lista desde su propio menú de 3 puntos, que
     * pasa por [addSongs]/[removeSongs] y por tanto por este mismo [tick]).
     */
    fun songsOf(name: String): Flow<List<Song>> =
        combine(tick, library.songs) { _, songs -> songs }
            .mapLatest { songs ->
                val byFilename = songs.associateBy { File(it.filePath).name }
                repository.resolveSongs(name, byFilename)
            }

    private suspend fun buildSummaries(songs: List<Song>): List<PlaylistSummary> {
        val byFilename = songs.associateBy { File(it.filePath).name }
        return repository.listPlaylistNames().map { name ->
            val resolved = repository.resolveSongs(name, byFilename)
            PlaylistSummary(
                name = name,
                songCount = resolved.size,
                totalDuration = resolved.sumOf { it.duration }
            )
        }
    }

    /** Índice basename→canción de la biblioteca actual (para resolver una lista al abrirla). */
    suspend fun songIndex(): Map<String, Song> =
        library.songs.first().associateBy { File(it.filePath).name }

    // --- Mutaciones (refrescan al terminar) ---

    fun create(name: String) = mutate { repository.createPlaylist(name) }

    /** Si el renombrado tiene éxito, el "Posición actual" guardado (ver [PlaylistResumeStore]) sigue
     *  a la lista con su nuevo nombre en vez de quedarse huérfano bajo el antiguo. */
    fun rename(oldName: String, newName: String) = mutate {
        if (repository.renamePlaylist(oldName, newName)) PlaylistResumeStore.rename(oldName, newName)
    }

    /** Borra también el "Posición actual" guardado: sin esto, un nombre reciclado para una lista
     *  nueva heredaría el de la borrada. */
    fun delete(name: String) = mutate {
        repository.deletePlaylist(name)
        PlaylistResumeStore.clear(name)
    }

    fun addSongs(name: String, filenames: List<String>) = mutate { repository.addSongs(name, filenames) }
    fun removeSongs(name: String, filenames: List<String>) = mutate { repository.removeSongs(name, filenames) }
    fun reorder(name: String, filenames: List<String>) = mutate { repository.setFilenames(name, filenames) }

    /**
     * Saca una canción de TODAS las listas porque su archivo ya no existe. Lo dispara
     * `MainActivity` cuando el reproductor no encuentra el archivo (ver `PlayerViewModel.missingFile`).
     *
     * Antes de tocar nada se comprueba que la carpeta de música sea legible: si no lo fuera, todas
     * las canciones parecerían perdidas y vaciaríamos las listas del usuario, que son archivos
     * suyos y no se pueden deshacer.
     */
    fun forgetSong(filename: String) = mutate {
        if (library.libraryFolderReadable()) repository.removeSongFromAll(filename)
    }

    /** Igual que [forgetSong] pero para varios archivos a la vez (borrado desde la selección
     *  múltiple de Canciones), en una sola pasada -no una llamada por canción- para no disparar
     *  [tick] N veces seguidas. */
    fun forgetSongs(filenames: List<String>) = mutate {
        if (library.libraryFolderReadable()) filenames.forEach { repository.removeSongFromAll(it) }
    }

    /** Fuerza una relectura (p. ej. al volver a la pestaña, por si se editó el archivo desde fuera). */
    fun refresh() { tick.value++ }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            tick.value++
        }
    }
}
