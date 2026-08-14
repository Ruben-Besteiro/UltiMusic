package com.untar.ultimusic.ui.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Estado de la ficha de una lista o un género (ver [CollectionDetailDialogFragment]): su nombre
 * hace de identificador ([CollectionKind.LISTA] va contra un archivo de
 * [PlaylistRepository], [CollectionKind.GENRE] filtra la biblioteca por esa etiqueta).
 *
 * Solo esos dos [CollectionKind] tienen sentido aquí: álbum/artista/productor se reproducen
 * directamente desde su propia ficha (ver
 * [com.untar.ultimusic.ui.library.DetailDialogFragment]), que ya tiene su propia lista de
 * canciones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Qué ficha se está mirando: el tipo y su nombre (de la lista o del género). */
    private val target = MutableStateFlow<Pair<CollectionKind, String>?>(null)

    /**
     * Cómo resolver las canciones de UNA lista por nombre. Lo fija el fragmento con
     * [bindPlaylistSongs] apuntando al [com.untar.ultimusic.ui.playlists.PlaylistsViewModel]
     * COMPARTIDO de la actividad (no uno propio de esta ficha): así, si se quita esta misma lista
     * de una canción desde el propio menú de 3 puntos de esta pantalla —o desde cualquier otra—,
     * el cambio llega aquí al instante por el mismo tick que ya usa la pestaña de Listas, en vez
     * de quedarse con la lista vieja hasta que se vuelva a abrir esta ficha.
     */
    private val playlistSongsSource = MutableStateFlow<((String) -> Flow<List<Song>>)?>(null)

    /**
     * Canciones de la colección, en orden. Un género sale directo de la biblioteca (reactivo: una
     * edición de metadatos se refleja sola). Una lista se resuelve con [playlistSongsSource].
     */
    val songs: StateFlow<List<Song>> = combine(target, playlistSongsSource) { t, source -> t to source }
        .flatMapLatest { (t, source) ->
            when (t?.first) {
                null -> flowOf(emptyList())
                CollectionKind.GENRE -> repository.songsOfGenre(t.second)
                CollectionKind.LISTA -> source?.invoke(t.second) ?: flowOf(emptyList())
                else -> flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Qué se está mirando: null hasta que [setTarget] lo fija. */
    val currentKind: CollectionKind? get() = target.value?.first

    /** Nombre de la lista o del género, el mismo que se pasó a [setTarget]. */
    val currentKey: String? get() = target.value?.second

    /** True si esta ficha se puede reordenar arrastrando (solo una lista, ver [currentKind]). */
    val reorderable: Boolean get() = currentKind == CollectionKind.LISTA

    fun setTarget(kind: CollectionKind, key: String) {
        if (target.value != null) return
        target.value = kind to key
    }

    /** Ver [playlistSongsSource]. Se fija una sola vez, antes de que [songs] se observe. */
    fun bindPlaylistSongs(source: (String) -> Flow<List<Song>>) {
        playlistSongsSource.value = source
    }

    /** Borra una canción de verdad (archivo y fila de la base de datos), y la olvida de toda lista. */
    fun deleteSong(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
            PlaylistRepository.get().removeSongFromAll(File(song.filePath).name)
        }
    }
}
