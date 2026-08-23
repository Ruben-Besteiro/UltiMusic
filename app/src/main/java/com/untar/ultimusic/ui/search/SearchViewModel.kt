package com.untar.ultimusic.ui.search

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Una fila de la lista de resultados. Es una **clase sellada** (`sealed`): una jerarquía cerrada de
 * la que solo pueden existir estos cuatro tipos y ninguno más. Eso permite meter en UNA sola lista
 * cosas de naturaleza distinta (cabeceras, canciones, álbumes y personas) y que el adaptador decida
 * qué pintar con un `when` que el compilador comprueba que está completo.
 *
 * La alternativa —cuatro listas y cuatro RecyclerViews, o una lista de `Any`— o rompería el scroll
 * continuo de la pantalla o perdería la seguridad de tipos.
 */
sealed interface SearchResult {

    /** Separador con el nombre de la sección ("Canciones", "Álbumes"…). */
    data class Header(@StringRes val titleRes: Int) : SearchResult

    data class SongRow(val song: Song) : SearchResult

    data class AlbumRow(val album: AlbumSummary) : SearchResult

    data class PersonRow(val person: PersonSummary) : SearchResult
}

/**
 * Estado del buscador: una consulta que escribe el usuario y la lista de resultados que sale de
 * cruzarla con la biblioteca.
 *
 * El filtrado se hace **en memoria** sobre los mismos flujos que alimentan las pestañas, no con una
 * consulta SQL. Las razones son dos: SQLite no sabe ignorar tildes (ver [TextSearch]) y la
 * biblioteca de un móvil cabe de sobra en memoria —ya está toda cargada para pintar las pestañas—,
 * así que recorrerla es cuestión de milisegundos.
 *
 * Como los tres flujos de la biblioteca son reactivos, los resultados se corrigen solos: si
 * mientras el buscador está abierto se edita el título de una canción o termina una reconciliación,
 * la lista se repinta sin que el usuario tenga que volver a escribir.
 *
 * Vive en el ámbito del propio diálogo, así que al cerrarlo la búsqueda se olvida.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    private val _query = MutableStateFlow("")

    /** Lo que hay escrito en la caja, tal cual (con tildes y mayúsculas). Se usa para los mensajes. */
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(text: String) {
        _query.value = text
    }

    /** Borra una canción de verdad (archivo y fila de la base de datos). */
    fun deleteSong(song: Song) {
        viewModelScope.launch { repository.deleteSong(song) }
    }

    /**
     * Resultados en el orden pedido: canciones, álbumes y artistas.
     *
     * [combine] es el operador que "casa" varios flujos: cada vez que CUALQUIERA de los cuatro emite
     * (la consulta o una de las tres listas), se vuelve a ejecutar el bloque con el último valor
     * de todos. [flowOn] manda ese trabajo a un hilo de cómputo, para que teclear rápido no bloquee
     * el hilo de la interfaz mientras se recorre la biblioteca entera.
     */
    val results: StateFlow<List<SearchResult>> = combine(
        _query.map { TextSearch.normalize(it.trim()) }.distinctUntilChanged(),
        repository.songs,
        repository.albums,
        repository.artists
    ) { needle, songs, albums, artists ->
        build(needle, songs, albums, artists)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Solo las canciones encontradas, en el mismo orden en que se ven. Es lo que se le pasa al
     * reproductor al pulsar una: la búsqueda se comporta como una colección (igual que un álbum),
     * así que suena el resto de resultados a continuación y al acabar se para.
     */
    val matchedSongs: List<Song>
        get() = results.value.filterIsInstance<SearchResult.SongRow>().map { it.song }

    /**
     * Monta la lista mezclada. Cada sección aporta su cabecera SOLO si tiene algún resultado: una
     * cabecera "Álbumes" sin nada debajo sería ruido.
     *
     * Se busca por el nombre VISIBLE de cada cosa (el título de la canción, el del álbum, el nombre
     * del artista), que es lo que el usuario ve escrito en la app y por tanto lo que espera poder
     * buscar. Una canción no aparece por el nombre de su artista: para eso está la sección de
     * artistas, cuya ficha ya lista todas sus canciones.
     */
    private fun build(
        needle: String,
        songs: List<Song>,
        albums: List<AlbumSummary>,
        artists: List<PersonSummary>
    ): List<SearchResult> {
        if (needle.isEmpty()) return emptyList()

        val out = mutableListOf<SearchResult>()

        songs.filter { TextSearch.contains(it.title, needle) }
            .addSection(out, R.string.tab_songs) { SearchResult.SongRow(it) }

        albums.filter { TextSearch.contains(it.title, needle) }
            .addSection(out, R.string.tab_albums) { SearchResult.AlbumRow(it) }

        artists.filter { TextSearch.contains(it.name, needle) }
            .addSection(out, R.string.tab_artists) { SearchResult.PersonRow(it) }

        return out
    }

    /** Añade a [out] la cabecera y las filas de una sección, o nada si la sección está vacía. */
    private fun <T> List<T>.addSection(
        out: MutableList<SearchResult>,
        @StringRes titleRes: Int,
        toRow: (T) -> SearchResult
    ) {
        if (isEmpty()) return
        out += SearchResult.Header(titleRes)
        mapTo(out, toRow)
    }
}
