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
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.util.SqlConsole
import com.untar.ultimusic.util.SqlPlan
import com.untar.ultimusic.util.SqlSchema
import com.untar.ultimusic.util.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
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

    /** Fila del resultado de un SELECT en el modo SQL (o su cabecera con el número de filas). */
    data class SqlRow(val index: Int, val text: String, val isHeader: Boolean = false) : SearchResult
}

/** Resultado de la última ejecución en el modo SQL. */
sealed interface SqlOutcome {
    data class Rows(val rows: List<SearchResult>) : SqlOutcome
    data class Done(val message: String) : SqlOutcome
    data class Error(val message: String) : SqlOutcome
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

    // ------------------------------------------------------------------ modo SQL

    private val _sqlOutcome = MutableStateFlow<SqlOutcome?>(null)

    /** Qué pasó con el último comando (null = nada ejecutado todavía / se ha limpiado). */
    val sqlOutcome: StateFlow<SqlOutcome?> = _sqlOutcome.asStateFlow()

    private val _sqlSchema = MutableStateFlow(SqlSchema.EMPTY)

    /** Tablas y columnas reales de la BD, para el desplegable de sugerencias. */
    val sqlSchema: StateFlow<SqlSchema> = _sqlSchema.asStateFlow()

    fun clearSql() {
        _sqlOutcome.value = null
    }

    fun showSqlError(message: String) {
        _sqlOutcome.value = SqlOutcome.Error(message)
    }

    /** Lee de `sqlite_master` + `PRAGMA table_info` para que el desplegable conozca tablas y columnas. */
    fun loadSqlSchema() {
        viewModelScope.launch {
            _sqlSchema.value = withContext(Dispatchers.IO) {
                runCatching {
                    val db = UltiMusicDatabase.get(getApplication()).openHelper.readableDatabase
                    val names = ArrayList<String>()
                    db.query(
                        "SELECT name FROM sqlite_master WHERE type IN ('table','view') " +
                            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name <> 'android_metadata'"
                    ).use { c -> while (c.moveToNext()) names += c.getString(0) }
                    val tables = HashMap<String, List<String>>()
                    for (name in names) {
                        val cols = ArrayList<String>()
                        db.query("PRAGMA table_info(\"$name\")").use { c ->
                            while (c.moveToNext()) cols += c.getString(1)
                        }
                        tables[name.lowercase()] = cols
                    }
                    SqlSchema(tables)
                }.getOrDefault(SqlSchema.EMPTY)
            }
        }
    }

    /**
     * Ejecuta [sql] (ya clasificado y, si tocaba, confirmado por el usuario) sobre la BD de Room.
     * Va por `openHelper` en vez de por un DAO, así que Room no se entera de las escrituras: por eso se
     * fuerza `refreshVersionsAsync` al final, para que los flujos que alimentan toda la app (pestañas,
     * fichas, buscador) se repinten al instante con lo nuevo.
     */
    fun runSql(sql: String) {
        val app = getApplication<Application>()
        val clean = sql.trim().trimEnd(';').trim()
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val room = UltiMusicDatabase.get(app)
                    val db = room.openHelper.writableDatabase
                    when (val plan = SqlConsole.plan(clean)) {
                        is SqlPlan.Invalid -> SqlOutcome.Error(
                            if (plan.arg != null) app.getString(plan.messageRes, plan.arg) else app.getString(plan.messageRes)
                        )
                        SqlPlan.ReadOnly -> readRows(app, db.query(clean), clean)
                        is SqlPlan.Write -> {
                            val changes = db.compileStatement(clean).use { st ->
                                when {
                                    SqlConsole.isUpdateDelete(clean) -> st.executeUpdateDelete()
                                    SqlConsole.isInsert(clean) -> { st.executeInsert(); 1 }
                                    else -> { st.execute(); -1 }
                                }
                            }
                            room.invalidationTracker.refreshVersionsAsync()
                            if (changes >= 0) SqlOutcome.Done(app.getString(R.string.sql_done_changes, changes))
                            else SqlOutcome.Done(app.getString(R.string.sql_done))
                        }
                    }
                } catch (e: Exception) {
                    SqlOutcome.Error(app.getString(R.string.sql_error_failed, e.message ?: e.javaClass.simpleName))
                }
            }
            _sqlOutcome.value = outcome
            if (outcome is SqlOutcome.Done) loadSqlSchema()
        }
    }

    private suspend fun readRows(app: Application, cursor: android.database.Cursor, sql: String): SqlOutcome {
        // Si el SELECT es sobre songs/albums/artists y trae la columna id, las filas se enseñan como
        // las del buscador normal (con carátula y toques) en vez de como texto plano.
        val table = Regex("""\bfrom\s+["`]?(\w+)""", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)?.lowercase()
        val ids = ArrayList<Long>()
        cursor.use { c ->
            val names = c.columnNames
            val idCol = names.indexOfFirst { it.equals("id", ignoreCase = true) }
            val rows = ArrayList<SearchResult>()
            var truncated = false
            while (c.moveToNext()) {
                if (rows.size >= SQL_MAX_ROWS) { truncated = true; break }
                val text = names.indices.joinToString("\n") { i ->
                    val v = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                        android.database.Cursor.FIELD_TYPE_BLOB -> "[blob ${c.getBlob(i).size} bytes]"
                        else -> c.getString(i)
                    }
                    "${names[i]} = $v"
                }
                rows += SearchResult.SqlRow(rows.size + 1, text)
                if (idCol >= 0 && c.getType(idCol) == android.database.Cursor.FIELD_TYPE_INTEGER) ids += c.getLong(idCol)
            }
            if (rows.isEmpty()) return SqlOutcome.Done(app.getString(R.string.sql_no_rows))
            val header = if (truncated) app.getString(R.string.sql_rows_truncated, SQL_MAX_ROWS)
            else app.getString(R.string.sql_rows_header, rows.size)
            val headerRow = SearchResult.SqlRow(0, header, isHeader = true)
            if (ids.size == rows.size) {
                val entities: List<SearchResult> = when (table) {
                    "songs" -> repository.songs.first().associateBy { it.id }
                        .let { m -> ids.mapNotNull { m[it] }.map { SearchResult.SongRow(it) } }
                    "albums" -> repository.albums.first().associateBy { it.id }
                        .let { m -> ids.mapNotNull { m[it] }.map { SearchResult.AlbumRow(it) } }
                    "artists" -> repository.artists.first().associateBy { it.id }
                        .let { m -> ids.mapNotNull { m[it] }.map { SearchResult.PersonRow(it) } }
                    else -> emptyList()
                }
                if (entities.isNotEmpty()) return SqlOutcome.Rows(listOf(headerRow) + entities)
            }
            return SqlOutcome.Rows(listOf<SearchResult>(headerRow) + rows)
        }
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
    /** Un elemento de la biblioteca junto a su nombre visible YA normalizado (ver [TextSearch]). */
    private class Indexed<T>(val item: T, val key: String)

    /** La biblioteca lista para buscar: cada título/nombre se normaliza UNA vez por cambio de la
     *  fonoteca, no una vez por título y por tecla pulsada (que con NFD + regex sobre miles de
     *  títulos era lo que hacía ir a tirones el buscador al escribir). */
    private class SearchIndex(
        val songs: List<Indexed<Song>>,
        val albums: List<Indexed<AlbumSummary>>,
        val artists: List<Indexed<PersonSummary>>
    )

    private val index = combine(repository.songs, repository.albums, repository.artists) { songs, albums, artists ->
        SearchIndex(
            songs.map { Indexed(it, TextSearch.normalize(it.title)) },
            albums.map { Indexed(it, TextSearch.normalize(it.title)) },
            artists.map { Indexed(it, TextSearch.normalize(it.name)) }
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Debounce corto: mientras el usuario teclea seguido no tiene sentido recorrer la biblioteca y
     * repintar la lista entera por cada letra intermedia. Vaciar la caja, en cambio, se aplica al
     * instante (sin debounce) para que cerrar/borrar la búsqueda no deje resultados viejos un rato.
     */
    @OptIn(FlowPreview::class)
    private val needle = _query.map { TextSearch.normalize(it.trim()) }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else QUERY_DEBOUNCE_MS }

    val results: StateFlow<List<SearchResult>> = combine(needle, index) { needle, index ->
        build(needle, index)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Solo las canciones encontradas, en el mismo orden en que se ven. Es lo que se le pasa al
     * reproductor al pulsar una: la búsqueda se comporta como una colección (igual que un álbum),
     * así que suena el resto de resultados a continuación y al acabar se para.
     */
    val matchedSongs: List<Song>
        get() = ((_sqlOutcome.value as? SqlOutcome.Rows)?.rows ?: results.value)
            .filterIsInstance<SearchResult.SongRow>().map { it.song }

    /**
     * Monta la lista mezclada. Cada sección aporta su cabecera SOLO si tiene algún resultado: una
     * cabecera "Álbumes" sin nada debajo sería ruido.
     *
     * Se busca por el nombre VISIBLE de cada cosa (el título de la canción, el del álbum, el nombre
     * del artista), que es lo que el usuario ve escrito en la app y por tanto lo que espera poder
     * buscar. Una canción no aparece por el nombre de su artista: para eso está la sección de
     * artistas, cuya ficha ya lista todas sus canciones.
     */
    private fun build(needle: String, index: SearchIndex): List<SearchResult> {
        if (needle.isEmpty()) return emptyList()

        val out = mutableListOf<SearchResult>()

        index.songs.matching(needle)
            .addSection(out, R.string.tab_songs) { SearchResult.SongRow(it) }

        index.albums.matching(needle)
            .addSection(out, R.string.tab_albums) { SearchResult.AlbumRow(it) }

        index.artists.matching(needle)
            .addSection(out, R.string.tab_artists) { SearchResult.PersonRow(it) }

        return out
    }

    private fun <T> List<Indexed<T>>.matching(needle: String): List<T> =
        mapNotNull { if (it.key.contains(needle)) it.item else null }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 150L
        const val SQL_MAX_ROWS = 500
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
