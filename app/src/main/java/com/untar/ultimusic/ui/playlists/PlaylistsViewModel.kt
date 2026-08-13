package com.untar.ultimusic.ui.playlists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /**
     * Resúmenes de todas las listas (nombre, nº de canciones, duración total). Depende del [tick]
     * y de la biblioteca: si cambian los archivos o se reescanea la música, se repinta.
     */
    val playlists: StateFlow<List<PlaylistSummary>> =
        combine(tick, library.songs) { _, songs -> songs }
            .mapLatest { songs -> buildSummaries(songs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun rename(oldName: String, newName: String) = mutate { repository.renamePlaylist(oldName, newName) }
    fun delete(name: String) = mutate { repository.deletePlaylist(name) }
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

    /** Fuerza una relectura (p. ej. al volver a la pestaña, por si se editó el archivo desde fuera). */
    fun refresh() { tick.value++ }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            tick.value++
        }
    }
}
