package com.untar.ultimusic.ui.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.common.SongSelection
import com.untar.ultimusic.util.FichaSort
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortOption
import com.untar.ultimusic.util.sortedByOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Estado de la ficha de una lista, un género o una etiqueta (ver [CollectionDetailDialogFragment]):
 * [CollectionKind.LISTA] va contra un archivo de [PlaylistRepository], [CollectionKind.GENRE] filtra
 * la biblioteca por ese género, [CollectionKind.TAG] filtra por esa etiqueta (ver
 * [com.untar.ultimusic.ui.library.TagsViewModel]).
 *
 * Solo esos tres [CollectionKind] tienen sentido aquí: álbum/artista se reproducen
 * directamente desde su propia ficha (ver
 * [com.untar.ultimusic.ui.library.DetailDialogFragment]), que ya tiene su propia lista de
 * canciones.
 *
 * La CLAVE ([currentKey]) es el nombre para Lista/Género (no tienen otra cosa: ni tabla ni id), pero
 * el `id` (como texto) para Etiqueta, que sí es una fila Room de verdad — ver [displayTitle] sobre
 * cómo se resuelve el nombre a partir de ese id para pintar el título de la ficha.
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
     * Cómo resolver las canciones de UNA etiqueta por id. Lo fija el fragmento con [bindTagSongs]
     * apuntando al [com.untar.ultimusic.ui.library.TagsViewModel] COMPARTIDO de la actividad, mismo
     * motivo que [playlistSongsSource] con Listas.
     */
    private val tagSongsSource = MutableStateFlow<((Long) -> Flow<List<Song>>)?>(null)

    /**
     * Solo para [CollectionKind.TAG]: resuelve el nombre ACTUAL de la etiqueta a partir de su id
     * (ver [displayTitle]). Género/Lista no lo necesitan: su [currentKey] YA es el nombre.
     */
    private val tagNameSource = MutableStateFlow<((Long) -> Flow<String?>)?>(null)

    /**
     * Canciones de la colección, en orden. Un género sale directo de la biblioteca (reactivo: una
     * edición de metadatos se refleja sola). Una lista se resuelve con [playlistSongsSource], una
     * etiqueta con [tagSongsSource].
     */
    val songs: StateFlow<List<Song>> =
        combine(target, playlistSongsSource, tagSongsSource) { t, playlistSource, tagSource -> Triple(t, playlistSource, tagSource) }
            .flatMapLatest { (t, playlistSource, tagSource) ->
                when (t?.first) {
                    null -> flowOf(emptyList())
                    CollectionKind.GENRE -> repository.songsOfGenre(t.second)
                        .sortedBy(FichaSort.flow(LibraryTab.GENRE_SONGS))
                    CollectionKind.LISTA -> playlistSource?.invoke(t.second) ?: flowOf(emptyList())
                    CollectionKind.TAG -> t.second.toLongOrNull()?.let { id -> tagSource?.invoke(id) }
                        ?.sortedBy(FichaSort.flow(LibraryTab.TAG_SONGS))
                        ?: flowOf(emptyList())
                    else -> flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ordena con el ojo de la ficha (género o etiqueta, ver [FichaSort]); se reordena sola al cambiarlo. */
    private fun Flow<List<Song>>.sortedBy(sort: Flow<SortOption>): Flow<List<Song>> =
        combine(this, sort) { list, option -> list.sortedByOption(option) }.flowOn(Dispatchers.Default)

    /**
     * Carrusel horizontal de álbumes, solo para un género (igual que el de la ficha de artista, ver
     * [com.untar.ultimusic.ui.library.DetailViewModel.albums]): los álbumes cuyos géneros propios
     * incluyen este (ver [LibraryRepository.genreAlbums]). Vacío para lista/etiqueta.
     */
    val albums: StateFlow<List<AlbumSummary>> = target
        .flatMapLatest { t ->
            if (t?.first == CollectionKind.GENRE) repository.genreAlbums(t.second) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Título de la ficha. Para Género/Lista es literalmente [currentKey] (su nombre, síncrono, como
     * antes). Para Etiqueta hay que resolverlo: [currentKey] guarda el id como texto, no el nombre
     * (ver el porqué en el javadoc de la clase).
     */
    val displayTitle: StateFlow<String> =
        combine(target, tagNameSource) { t, tagNameFn -> t to tagNameFn }
            .flatMapLatest { (t, tagNameFn) ->
                when (t?.first) {
                    CollectionKind.TAG -> t.second.toLongOrNull()
                        ?.let { id -> tagNameFn?.invoke(id) }
                        ?.map { it.orEmpty() }
                        ?: flowOf("")
                    else -> flowOf(t?.second.orEmpty())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Qué se está mirando: null hasta que [setTarget] lo fija. */
    val currentKind: CollectionKind? get() = target.value?.first

    /** Nombre de la lista/género, o id (como texto) de la etiqueta — la misma clave que se pasó a
     *  [setTarget]. Para pintar el título de la ficha usar [displayTitle], no esto directamente. */
    val currentKey: String? get() = target.value?.second

    /** True si esta ficha se puede reordenar arrastrando (solo una lista, ver [currentKind]). */
    val reorderable: Boolean get() = currentKind == CollectionKind.LISTA

    /** Selección múltiple por pulsación larga (ver [SongSelection]): mismo mecanismo que
     *  [com.untar.ultimusic.ui.SongsViewModel.selectedIds], aplicado a las canciones de ESTA ficha
     *  (ver [CollectionSongsAdapter]/[CollectionDetailDialogFragment]). */
    private val selection = SongSelection()
    val selectedIds: StateFlow<Set<Long>> = selection.selectedIds
    fun startSelection(songId: Long) = selection.start(songId)
    fun toggleSelection(songId: Long) = selection.toggle(songId)
    fun clearSelection() = selection.clear()

    fun setTarget(kind: CollectionKind, key: String) {
        if (target.value != null) return
        target.value = kind to key
    }

    /** Ver [playlistSongsSource]. Se fija una sola vez, antes de que [songs] se observe. */
    fun bindPlaylistSongs(source: (String) -> Flow<List<Song>>) {
        playlistSongsSource.value = source
    }

    /** Ver [tagSongsSource]. Se fija una sola vez, antes de que [songs] se observe. */
    fun bindTagSongs(source: (Long) -> Flow<List<Song>>) {
        tagSongsSource.value = source
    }

    /** Ver [tagNameSource]. Se fija una sola vez, antes de que [displayTitle] se observe. */
    fun bindTagName(source: (Long) -> Flow<String?>) {
        tagNameSource.value = source
    }

    /**
     * Renombra el género que se está mirando (ver
     * [CollectionDetailDialogFragment.showRenameGenreDialog]): a diferencia de una etiqueta -una
     * fila de Room con su propio id, que no cambia al renombrarla- la CLAVE de un género es su
     * propio nombre (ver el javadoc de la clase), así que tras guardar hay que reapuntar [target] al
     * nuevo nombre a mano: sin esto [songs] seguiría filtrando por el nombre VIEJO (ya no queda
     * ninguna canción con él) y [displayTitle] se quedaría con el título antiguo.
     */
    fun renameGenre(newName: String) {
        val key = currentKey?.takeIf { currentKind == CollectionKind.GENRE } ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == key) return
        viewModelScope.launch {
            repository.renameGenre(key, trimmed)
            target.value = CollectionKind.GENRE to trimmed
        }
    }

    /** Borra una canción de verdad (archivo y fila de la base de datos), y la olvida de toda lista. */
    fun deleteSong(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
            PlaylistRepository.get().removeSongFromAll(File(song.filePath).name)
        }
    }

    /** Igual que [deleteSong] pero para varias a la vez (borrado desde la selección múltiple). */
    fun deleteSongs(songs: List<Song>) {
        viewModelScope.launch {
            val playlists = PlaylistRepository.get()
            for (song in songs) {
                repository.deleteSong(song)
                playlists.removeSongFromAll(File(song.filePath).name)
            }
        }
    }
}
