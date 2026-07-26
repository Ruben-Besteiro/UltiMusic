package com.untarlamanteca.ultimusic.ui.library

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.LibraryRepository
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.AlbumSummary
import com.untarlamanteca.ultimusic.model.AlbumTrack
import com.untarlamanteca.ultimusic.model.PersonSummary
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverRef
import com.untarlamanteca.ultimusic.util.DynamicColor
import com.untarlamanteca.ultimusic.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Qué se está mirando en la ficha de detalle. */
enum class DetailKind { ALBUM, ARTIST, PRODUCER }

/** Una línea de datos de la cabecera: un icono a la izquierda y un texto a la derecha. */
data class InfoLine(@DrawableRes val icon: Int, val text: String)

/** Todo lo que pinta la cabecera de la ficha. */
data class DetailHeader(
    val title: String,
    val cover: CoverRef,
    val lines: List<InfoLine>
)

/**
 * Estado de la ficha de un álbum, un artista o un productor.
 *
 * Los tres casos comparten ViewModel porque la pantalla es la misma: una cabecera con imagen y
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
                DetailKind.ALBUM -> repository.album(t.second).map { it?.toHeader() }
                DetailKind.ARTIST -> repository.artist(t.second).map { it?.toHeader() }
                DetailKind.PRODUCER -> repository.producer(t.second).map { it?.toHeader() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Canciones que se listan debajo. Para un álbum llevan su número de pista y van ordenadas por
     * él; para un artista o un productor no hay número que enseñar (sus canciones vienen de álbumes
     * distintos), así que se envuelven con `trackNumber = null` para poder reutilizar el adaptador.
     */
    val tracks: StateFlow<List<AlbumTrack>> = target
        .flatMapLatest { t ->
            when (t?.first) {
                null -> flowOf(emptyList())
                DetailKind.ALBUM -> repository.albumTracks(t.second)
                DetailKind.ARTIST -> repository.artistSongs(t.second).map { it.asTracks() }
                DetailKind.PRODUCER -> repository.producerSongs(t.second).map { it.asTracks() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Color con el que se tiñe la ficha, sacado de su propia carátula (ver [DynamicColor]). */
    private val _accentColor = MutableStateFlow(DynamicColor.DEFAULT)
    val accentColor = _accentColor.asStateFlow()

    /** Las canciones sueltas, para pasárselas al reproductor al pulsar una. */
    val songs: List<Song> get() = tracks.value.map { it.song }

    fun setTarget(kind: DetailKind, id: Long) {
        if (target.value != null) return
        target.value = kind to id

        // El acento se calcula UNA sola vez, con la primera cabecera que llegue. Recalcularlo en
        // cada emisión haría parpadear la pantalla al editar cualquier canción del álbum.
        viewModelScope.launch {
            val cover = header.first { it != null }!!.cover
            val app = getApplication<Application>()
            _accentColor.value = DynamicColor.fromCover(app, CoverArt.cover(app, cover))
        }
    }

    // --- Construcción de la cabecera ---

    private fun AlbumSummary.toHeader() = DetailHeader(
        title = title,
        cover = cover,
        lines = listOfNotNull(
            InfoLine(R.drawable.ic_person, artistName ?: MusicScanner.UNKNOWN_ARTIST),
            InfoLine(R.drawable.ic_music_note, songsText(songCount)),
            InfoLine(R.drawable.ic_timer, TimeFormat.hhmmss(totalDuration)),
            // El año solo aparece si se conoce; una línea con un hueco quedaría peor que ninguna.
            year?.let { InfoLine(R.drawable.ic_calendar, it.toString()) }
        )
    )

    private fun PersonSummary.toHeader() = DetailHeader(
        title = name,
        cover = cover,
        lines = listOf(
            InfoLine(R.drawable.ic_album, albumsText(albumCount)),
            InfoLine(R.drawable.ic_music_note, songsText(songCount)),
            InfoLine(R.drawable.ic_timer, TimeFormat.hhmmss(totalDuration))
        )
    )

    private fun songsText(count: Int): String = getApplication<Application>().resources
        .getQuantityString(R.plurals.song_count, count, count)

    private fun albumsText(count: Int): String = getApplication<Application>().resources
        .getQuantityString(R.plurals.album_count, count, count)
}

private fun List<Song>.asTracks(): List<AlbumTrack> = map { AlbumTrack(it, null) }
