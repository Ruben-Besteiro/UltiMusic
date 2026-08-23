package com.untar.ultimusic.ui.library

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.formatCompactCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Qué se está mirando en la ficha de detalle. */
enum class DetailKind { ALBUM, ARTIST }

/** Una línea de datos de la cabecera: un icono a la izquierda y un texto a la derecha. */
data class InfoLine(@DrawableRes val icon: Int, val text: String)

/** Todo lo que pinta la cabecera de la ficha. */
data class DetailHeader(
    val title: String,
    val cover: CoverRef,
    val lines: List<InfoLine>
)

/**
 * Estado de la ficha de un álbum o un artista.
 *
 * Los dos casos comparten ViewModel porque la pantalla es la misma: una cabecera con imagen y
 * datos, y debajo la lista de canciones. Lo único que cambia es de qué consulta salen los datos y
 * qué líneas tiene la cabecera, y de eso se encargan los `when (kind)` de aquí.
 *
 * Vive en el ámbito del propio diálogo, así que se destruye al cerrarlo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Qué ficha se está mirando. Lo fija el fragmento nada más crearse. */
    private val target = MutableStateFlow<Pair<DetailKind, Long>?>(null)

    /**
     * Cabecera. `flatMapLatest` es lo que permite que un flujo cambie de fuente: cuando [target]
     * emite, este flujo se suscribe al de ESE álbum/persona y se desengancha del anterior. Sin él
     * tendríamos un "flujo de flujos" (`Flow<Flow<...>>`), que no se puede observar directamente.
     */
    val header: StateFlow<DetailHeader?> = target
        .flatMapLatest { t ->
            when (t?.first) {
                null -> flowOf(null)
                DetailKind.ALBUM -> combine(
                    repository.album(t.second),
                    repository.albumArtistNames(t.second)
                ) { summary, artistNames -> summary?.toHeader(artistNames) }
                DetailKind.ARTIST -> repository.artist(t.second).map { it?.toHeader() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Canciones que se listan debajo. Para un álbum van ordenadas por su número de pista (que ya
     * trae cada [Song], ver [Song.trackNumber]); para un artista ese número no se enseña
     * ([DetailSongsAdapter] solo lo pinta en la ficha de álbum), aunque la canción lo lleve
     * consigo igualmente (es del álbum al que pertenece, no de esta ficha).
     */
    val tracks: StateFlow<List<Song>> = target
        .flatMapLatest { t ->
            when (t?.first) {
                null -> flowOf(emptyList())
                DetailKind.ALBUM -> repository.albumTracks(t.second)
                DetailKind.ARTIST -> repository.artistSongs(t.second)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Las canciones sueltas, para pasárselas al reproductor al pulsar una. */
    val songs: List<Song> get() = tracks.value

    /**
     * Álbumes del carrusel horizontal, solo para artista (en un álbum va vacío: no tiene sentido
     * enseñar el propio álbum dentro de su ficha).
     */
    val albums: StateFlow<List<AlbumSummary>> = target
        .flatMapLatest { t ->
            when (t?.first) {
                DetailKind.ARTIST -> repository.artistAlbums(t.second)
                DetailKind.ALBUM, null -> flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Qué tipo de ficha es esta. Lo necesita el fragmento para saber si el menú de 3 puntos (acciones
     * de álbum: añadir todo a una lista, editar el álbum...) tiene sentido aquí, porque solo existe
     * para álbumes. */
    val currentKind: DetailKind? get() = target.value?.first

    /** El id del álbum en edición; solo tiene valor cuando [currentKind] es [DetailKind.ALBUM]. */
    val currentAlbumId: Long? get() = target.value?.takeIf { it.first == DetailKind.ALBUM }?.second

    /** Borra una canción de verdad (archivo y fila de la base de datos). */
    fun deleteSong(song: Song) {
        viewModelScope.launch { repository.deleteSong(song) }
    }

    /**
     * Borra TODAS las canciones del álbum, una a una, y las saca de cualquier lista en la que
     * estuvieran — exactamente lo mismo que si se borraran sueltas desde la pestaña de Canciones (ver
     * [com.untar.ultimusic.ui.songs.SongsFragment.showDeleteDialog]).
     *
     * Es una función SUSPENDIDA, a diferencia de [deleteSong] (que lanza su propia corrutina y no
     * espera), porque aquí quien llama SÍ necesita saber cuándo termina: la ficha debe cerrarse justo
     * después, y cerrarla antes de tiempo cancelaría el borrado a medias (este ViewModel vive en el
     * ámbito del propio diálogo).
     */
    suspend fun deleteAllSongs() {
        val playlists = PlaylistRepository.get()
        for (song in songs) {
            repository.deleteSong(song)
            playlists.removeSongFromAll(File(song.filePath).name)
        }
    }

    fun setTarget(kind: DetailKind, id: Long) {
        if (target.value != null) return
        target.value = kind to id
    }

    // --- Construcción de la cabecera ---

    private fun AlbumSummary.toHeader(artistNames: List<String>) = DetailHeader(
        title = title,
        cover = cover,
        lines = listOfNotNull(
            // [artistNames] es la lista cruda (sin fallback) de LibraryRepository.albumArtistNames.
            // Si el álbum tiene artistas propios enlazados, [artistName] ya trae esos mismos nombres
            // juntos en un string (ver LibraryDao.observeAlbumSummaries), pero se prefiere la lista
            // cruda aquí porque no depende de ese join hecho en SQL. Si viniera vacía —álbum sin
            // ningún artista propio enlazado— se cae a [artistName], que sí trae el resto de
            // fallbacks (etiqueta original o el más repetido entre sus canciones).
            InfoLine(
                R.drawable.ic_person,
                artistNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: artistName
                    ?: MusicScanner.UNKNOWN_ARTIST
            ),
            InfoLine(R.drawable.ic_music_note, songsText(songCount)),
            InfoLine(R.drawable.ic_timer, TimeFormat.hhmmss(totalDuration)),
            // El año solo aparece si se conoce; una línea con un hueco quedaría peor que ninguna.
            year?.let { InfoLine(R.drawable.ic_calendar, it.toString()) }
        )
    )

    private fun PersonSummary.toHeader() = DetailHeader(
        title = name,
        cover = cover,
        lines = listOfNotNull(
            InfoLine(R.drawable.ic_album, albumsText(albumCount)),
            InfoLine(R.drawable.ic_music_note, songsText(songCount)),
            InfoLine(R.drawable.ic_timer, TimeFormat.hhmmss(totalDuration)),
            // Última línea, solo si se conoce: los suscriptores del canal de YouTube más repetido
            // entre las canciones donde esta persona es la artista principal (ver
            // PersonSummary.popularity).
            popularity?.let { InfoLine(R.drawable.ic_youtube, subscribersText(it)) }
        )
    )

    private fun songsText(count: Int): String = getApplication<Application>().resources
        .getQuantityString(R.plurals.song_count, count, count)

    private fun albumsText(count: Int): String = getApplication<Application>().resources
        .getQuantityString(R.plurals.album_count, count, count)

    private fun subscribersText(count: Long): String = getApplication<Application>()
        .getString(R.string.youtube_subscribers_format, formatCompactCount(count))
}
