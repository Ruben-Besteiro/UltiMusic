package com.untar.ultimusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.remote.MusicBrainzApi
import com.untar.ultimusic.model.DiscographyAlbum
import com.untar.ultimusic.model.DiscographyTrack
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.TextMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Estado de la carga que pinta [DiscographyDialogFragment]. */
sealed interface DiscographyUiState {
    data object Loading : DiscographyUiState
    data class Success(val albums: List<DiscographyAlbumUi>) : DiscographyUiState
    data object Empty : DiscographyUiState
    data object Error : DiscographyUiState
}

/** Un álbum de [DiscographyAlbum] ya cruzado con la fonoteca: [isOnDevice] dice si hay AL MENOS
 *  una canción de él en el dispositivo (ver [DiscographyViewModel.crossReference]). */
data class DiscographyAlbumUi(
    val album: DiscographyAlbum,
    val isOnDevice: Boolean,
    val tracks: List<DiscographyTrackUi>
)

/** Una pista de [DiscographyTrack] ya cruzada con la fonoteca. */
data class DiscographyTrackUi(
    val track: DiscographyTrack,
    val isOnDevice: Boolean
)

/**
 * Discografía oficial de un artista (ver [com.untar.ultimusic.data.remote.MusicBrainzApi]) cruzada
 * con lo que el usuario tiene catalogado, para [DiscographyDialogFragment].
 *
 * Es un `AndroidViewModel` (y no uno normal, como
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel]) porque necesita
 * [LibraryRepository] para saber qué hay ya en el dispositivo.
 */
class DiscographyViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    private val _state = MutableStateFlow<DiscographyUiState>(DiscographyUiState.Loading)
    val state: StateFlow<DiscographyUiState> = _state.asStateFlow()

    /** Artista sobre el que se pidió la discografía, para poder repetir la carga con [retry] sin
     *  que quien llame tenga que volver a pasar los argumentos. [albumTitle] solo tiene valor
     *  cuando se pide desde la ficha de un ÁLBUM (ver [load]): acota el catálogo del artista a
     *  ese único álbum en vez de enseñarlos todos. */
    private var target: Triple<Long, String, String?>? = null

    /** Lanza la carga la PRIMERA vez que se llama; si el sistema recrea el diálogo (p. ej. al girar
     *  la pantalla), no la repite — mismo guard que [DetailViewModel.setTarget]. */
    fun load(artistId: Long, artistName: String, albumTitle: String? = null) {
        if (target != null) return
        target = Triple(artistId, artistName, albumTitle)
        runLoad()
    }

    /** Botón "Reintentar" del estado de error. */
    fun retry() = runLoad()

    private fun runLoad() {
        val (artistId, artistName, albumTitle) = target ?: return
        viewModelScope.launch {
            _state.value = DiscographyUiState.Loading
            val result = runCatching { MusicBrainzApi.discography(artistName) }
            _state.value = result.fold(
                onSuccess = { albums ->
                    // Con albumTitle (ficha de álbum) se acota el catálogo entero del artista a
                    // ese único álbum, mismo emparejamiento parcial que crossReference más abajo.
                    val scoped = if (albumTitle != null) {
                        albums.filter { TextMatch.looselyEqual(it.title, albumTitle) }
                    } else {
                        albums
                    }
                    if (scoped.isEmpty()) {
                        DiscographyUiState.Empty
                    } else {
                        val deviceSongs = repository.artistSongs(artistId).first()
                        DiscographyUiState.Success(scoped.map { it.crossReference(deviceSongs) })
                    }
                },
                onFailure = { DiscographyUiState.Error }
            )
        }
    }

    /**
     * Cruza un álbum del catálogo oficial con [deviceSongs] (las canciones de ESTE artista que ya
     * hay en la fonoteca).
     *
     * El álbum sale "en el dispositivo" si al menos una canción tiene, entre sus álbumes enlazados,
     * uno cuyo título coincide (parcialmente, ver [TextMatch.looselyEqual]) con el de este álbum —
     * SOLO por título, sin mirar canción por canción: así, si una canción suelta está mal escrita
     * pero el álbum no, el álbum sigue en blanco aunque esa canción concreta no lo esté.
     *
     * Cada pista, dentro de ese subconjunto ya emparejado por álbum, sale "en el dispositivo" si el
     * título coincide (parcialmente) O si el número de pista Y el de disco dentro de ESE álbum
     * coinciden LOS DOS a la vez — un disco sin especificar (en la pista del catálogo siempre hay
     * uno, ver [com.untar.ultimusic.data.remote.MusicBrainzApi.tracksFor]; en la canción del
     * dispositivo puede faltar si el usuario no lo etiquetó) cuenta como 1. Comparar solo el número
     * de pista sin el de disco emparejaba, por ejemplo, la pista 4 del disco 2 de un álbum de dos
     * discos con la pista 4 del disco 1 con la que no tiene nada que ver.
     */
    private fun DiscographyAlbum.crossReference(deviceSongs: List<Song>): DiscographyAlbumUi {
        val albumSongs = deviceSongs.filter { song ->
            song.albums.any { TextMatch.looselyEqual(it.album.title, title) }
        }
        val trackUis = tracks.map { track ->
            val isOnDevice = albumSongs.any { song ->
                val entry = song.albums.first { TextMatch.looselyEqual(it.album.title, title) }
                val numbersMatch = entry.trackNumber == track.trackNumber &&
                    (entry.discNumber ?: 1) == track.discNumber
                TextMatch.looselyEqual(song.title, track.title) || numbersMatch
            }
            DiscographyTrackUi(track, isOnDevice)
        }
        return DiscographyAlbumUi(this, albumSongs.isNotEmpty(), trackUis)
    }
}
