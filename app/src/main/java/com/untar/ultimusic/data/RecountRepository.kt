package com.untar.ultimusic.data

import android.content.Context
import com.untar.ultimusic.data.db.LibraryDao
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.data.db.entities.PlayEventEntity
import com.untar.ultimusic.data.db.relations.SongPlayTotalsRow
import com.untar.ultimusic.model.GenreSlice
import com.untar.ultimusic.model.HistogramBar
import com.untar.ultimusic.model.HistogramData
import com.untar.ultimusic.model.RecountData
import com.untar.ultimusic.model.RecountEntry
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.GroupCoverSource
import com.untar.ultimusic.util.GroupKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Fachada de UltiMusic Recount. Hace dos cosas, y conviene verlas como dos mitades independientes:
 *
 * 1. **Anotar** ([record]): mete una fila en `play_events` por cada escucha que haya llegado al 50%.
 *    Es lo ÚNICO que se guarda. Quien decide cuándo llamar es
 *    [com.untar.ultimusic.playback.PlayTracker].
 * 2. **Derivar** ([recount], [histogram]): recalcula todo lo que se pinta cruzando
 *    esas filas con la fonoteca **tal y como está ahora mismo**.
 *
 * La segunda mitad no tiene ninguna caché, y es a propósito: los flujos cuelgan directamente de Room
 * ([LibraryDao.observePlayTotals], [LibraryRepository.songs], [LibraryDao.observeHiddenSongIds]), así
 * que una edición de metadatos reemite sola y el Recount se repinta al instante, sin nada que
 * invalidar a mano. La consecuencia buena es justo la regla del feature: si el usuario le cambia el
 * género a una canción, el top de géneros de 2024 cambia con ella, porque no había ningún "top de
 * 2024" guardado en ningún sitio — se acaba de calcular.
 *
 * Los casos de un id de escucha que ya no está en la fonoteca no son el mismo caso, y de
 * distinguirlos va media clase:
 *
 * - **Oculta por la lista gris** → se descarta ENTERA. Para la app es como si no existiera: ni suma
 *   tiempo, ni sale en ningún top, ni pinta en la tarta. Si el usuario saca su carpeta de la lista
 *   gris, vuelve con todas sus escuchas intactas, porque nunca se borró nada.
 * - **Borrada de verdad** → va al cubo agregado "Canciones borradas", que compite por su puesto en
 *   el top 10 de canciones como una fila más.
 * - **Viva** → cuenta normal, y además reparte sus escuchas entre sus artistas y sus géneros.
 *
 * Del cubo de borradas no se sabe de quién era ni de qué género, así que aparece SOLO en el top de
 * canciones. Por eso las sumas de los tops de artistas y géneros no cuadran con
 * [RecountData.totalMs]: el total es lo que el usuario escuchó de verdad, y los otros dos solo
 * reparten lo atribuible.
 */
class RecountRepository private constructor(
    private val dao: LibraryDao,
    private val libraryRepository: LibraryRepository
) {

    // --- Anotar ---

    /**
     * Guarda una escucha. [startedAt] es epoch ms del instante en que la canción empezó a sonar y
     * [playedMs] los milisegundos que sonó de verdad (ver [PlayEventEntity]).
     *
     * El año se decide AQUÍ, una sola vez, con el calendario local del dispositivo, y ya no se
     * vuelve a tocar: ver la cabecera de [PlayEventEntity] sobre por qué no se calcula al consultar.
     */
    suspend fun record(songId: Long, startedAt: Long, playedMs: Long) = withContext(Dispatchers.IO) {
        dao.insertPlayEvent(
            PlayEventEntity(
                songId = songId,
                startedAt = startedAt,
                playedMs = playedMs,
                year = yearOf(startedAt)
            )
        )
    }

    // --- Derivar ---

    /**
     * Todo lo que se pinta de [year]. Ver la cabecera de la clase sobre por qué se recalcula entero
     * cada vez en vez de guardarse.
     *
     * Los tres flujos que se combinan son los tres ingredientes del cruce: lo que sonó, lo que sigue
     * vivo en la fonoteca, y lo que está oculto por la lista gris (que no es lo mismo que borrado).
     */
    fun recount(year: Int): Flow<RecountData> = combine(
        dao.observePlayTotals(year),
        libraryRepository.songs,
        dao.observeHiddenSongIds()
    ) { totals, songs, hiddenIds ->
        build(year, totals, songs, hiddenIds.toSet())
    }.flowOn(Dispatchers.Default)

    /**
     * El histograma de la fonoteca por año de publicación. NO depende del año de [recount]: es un
     * retrato de la biblioteca entera, no de lo que se escuchó ese año.
     */
    val histogram: Flow<HistogramData> = libraryRepository.songs
        .map { songs -> buildHistogram(songs) }
        .flowOn(Dispatchers.Default)

    // --- Cálculo ---

    private fun build(
        year: Int,
        totals: List<SongPlayTotalsRow>,
        songs: List<Song>,
        hiddenIds: Set<Long>
    ): RecountData {
        // `songs` ya viene sin ocultas ni borradas (observeSongs filtra hiddenByGreylist), así que
        // "no está en este mapa" significa una de las dos cosas, y hiddenIds es quien las separa.
        val byId = songs.associateBy { it.id }

        val songEntries = mutableListOf<RecountEntry>()
        var deletedPlays = 0
        var deletedMs = 0L
        val artistTotals = LinkedHashMap<String, Accumulator>()
        val genreTotals = LinkedHashMap<String, Accumulator>()

        for (row in totals) {
            val song = byId[row.songId]
            if (song == null) {
                // Oculta por la lista gris: se descarta entera, no es una canción borrada.
                if (row.songId in hiddenIds) continue
                deletedPlays += row.plays
                deletedMs += row.playedMs
                continue
            }
            songEntries += RecountEntry(
                label = song.title,
                plays = row.plays,
                playedMs = row.playedMs,
                songId = song.id,
                cover = CoverRef(
                    ownImage = song.imageName,
                    songPath = song.filePath,
                    videoThumbnail = song.videoThumbnailName
                )
            )
            // Una canción con 3 artistas suma su reproducción ENTERA a los 3, no un tercio a cada
            // uno: es la misma regla que ya usan la pestaña de Artistas y la de Géneros para contar
            // canciones (ver LibraryDao.observeArtistSummaries y LibraryRepository.genres).
            for (artist in song.artists) {
                artistTotals.getOrPut(artist.name) { Accumulator() }.add(row.plays, row.playedMs)
            }
            for (genre in song.genres) {
                genreTotals.getOrPut(genre) { Accumulator() }.add(row.plays, row.playedMs)
            }
        }

        // El cubo de borradas entra en el top de canciones como una fila más y compite por su puesto.
        // Va sin etiqueta: el texto sale de strings.xml al pintarlo (ver RecountTopAdapter), porque
        // este repositorio no tiene por qué saber en qué idioma se llama.
        if (deletedPlays > 0) {
            songEntries += RecountEntry(
                label = "",
                plays = deletedPlays,
                playedMs = deletedMs,
                isDeletedBucket = true
            )
        }

        val artistCovers = artistCoversByName(songs)
        // El total del año se calcula antes que los tops porque es el denominador del porcentaje de
        // cada fila (ver RecountEntry.share): los tres tops se reparten sobre el MISMO total, el de
        // canciones, y no cada uno sobre la suma de su propia lista.
        val totalPlays = songEntries.sumOf { it.plays }
        return RecountData(
            year = year,
            totalPlays = totalPlays,
            totalMs = songEntries.sumOf { it.playedMs },
            topSongs = songEntries.topTen(totalPlays),
            topArtists = artistTotals.toEntries { name -> artistCovers[name] }.topTen(totalPlays),
            topGenres = genreTotals.toEntries { null }.topTen(totalPlays),
            genreSlices = slices(genreTotals)
        )
    }

    /**
     * La carátula de cada artista, indexada por nombre. Los tops se agregan por NOMBRE y no por id
     * (ver [RecountEntry]), así que hace falta este puente para poder pintar la imagen; el id sale de
     * la primera canción donde aparezca ese artista, lo cual basta porque el nombre de artista es
     * único en la base de datos (`Index(["tagName"], unique = true)` en `ArtistEntity`).
     */
    private fun artistCoversByName(songs: List<Song>): Map<String, CoverRef> {
        val covers = HashMap<String, CoverRef>()
        for (song in songs) {
            for (artist in song.artists) {
                covers.getOrPut(artist.name) {
                    CoverRef(
                        ownImage = artist.imageName,
                        group = GroupCoverSource(GroupKind.ARTIST, artist.id)
                    )
                }
            }
        }
        return covers
    }

    /**
     * Reparte la tarta. Los géneros por debajo de [MIN_SLICE_FRACTION] se funden en un único sector
     * "Otros": con 30 o 40 géneros, cada uno con dos o tres reproducciones, la tarta sale un abanico
     * de rayas indistinguibles. La leyenda de la pantalla sí los lista todos, así que no se pierde
     * información; solo deja de dibujarse por separado lo que no se vería.
     *
     * El sector "Otros" va siempre el último, aunque su suma lo pusiera más arriba: es un cajón de
     * sastre, no un género que compita con los demás.
     */
    private fun slices(genreTotals: Map<String, Accumulator>): List<GenreSlice> {
        val total = genreTotals.values.sumOf { it.plays }
        if (total == 0) return emptyList()

        val sorted = genreTotals.entries.sortedWith(
            compareByDescending<Map.Entry<String, Accumulator>> { it.value.plays }
                .thenByDescending { it.value.ms }
                .thenBy { it.key.lowercase() }
        )
        val result = mutableListOf<GenreSlice>()
        var otherPlays = 0
        var otherMs = 0L
        for ((name, value) in sorted) {
            val fraction = value.plays.toFloat() / total
            if (fraction >= MIN_SLICE_FRACTION) {
                result += GenreSlice(name, value.plays, value.ms, fraction)
            } else {
                otherPlays += value.plays
                otherMs += value.ms
            }
        }
        if (otherPlays > 0) {
            result += GenreSlice("", otherPlays, otherMs, otherPlays.toFloat() / total, isOther = true)
        }
        return result
    }

    /**
     * Agrupa las canciones por año de publicación y aplica la regla del hueco: una cadena de
     * [MIN_GAP_LENGTH] o más años seguidos sin ninguna canción se queda solo en sus dos extremos
     * (marcados con `isGapEdge`), y la pantalla dibuja una raya vertical entre ellos.
     *
     * Sin esto, una fonoteca con un disco de 1968 y el resto a partir de 2015 se pintaría como 45
     * columnas vacías y un puñado de barras aplastadas contra el borde derecho.
     */
    private fun buildHistogram(songs: List<Song>): HistogramData {
        val counts = HashMap<Int, Int>()
        var unknown = 0
        for (song in songs) {
            val year = song.year
            // Las canciones sin año no caben en ningún punto del eje X, así que se cuentan aparte
            // (nota bajo el gráfico) en vez de inventarles una columna. Un año absurdo salido de una
            // etiqueta rota tampoco entra: una sola estiraría el eje un siglo hacia atrás.
            if (year == null || year <= 0) {
                unknown++
                continue
            }
            counts[year] = (counts[year] ?: 0) + 1
        }
        if (counts.isEmpty()) return HistogramData(emptyList(), unknown)

        val first = counts.keys.min()
        val last = counts.keys.max()
        val bars = mutableListOf<HistogramBar>()
        var year = first
        while (year <= last) {
            val count = counts[year] ?: 0
            if (count > 0) {
                bars += HistogramBar(year, count)
                year++
                continue
            }
            // Cadena de años vacíos: se mide entera y luego se decide si se resume o se dibuja tal cual.
            var gapEnd = year
            while (gapEnd + 1 <= last && (counts[gapEnd + 1] ?: 0) == 0) gapEnd++
            if (gapEnd - year + 1 >= MIN_GAP_LENGTH) {
                bars += HistogramBar(year, 0, isGapEdge = true)
                bars += HistogramBar(gapEnd, 0, isGapEdge = true)
            } else {
                for (empty in year..gapEnd) bars += HistogramBar(empty, 0)
            }
            year = gapEnd + 1
        }
        return HistogramData(bars, unknown)
    }

    // --- Utilidades ---

    /** Acumulador mutable de (reproducciones, milisegundos) mientras se recorren los totales. Un
     *  `data class` inmutable obligaría a recrear la entrada del mapa en cada suma. */
    private class Accumulator(var plays: Int = 0, var ms: Long = 0L) {
        fun add(plays: Int, ms: Long) {
            this.plays += plays
            this.ms += ms
        }
    }

    private fun Map<String, Accumulator>.toEntries(cover: (String) -> CoverRef?): List<RecountEntry> =
        map { (name, value) -> RecountEntry(name, value.plays, value.ms, cover = cover(name)) }

    /**
     * Orden canónico de los tres tops: más reproducciones primero, desempate por tiempo escuchado y
     * luego por nombre, para que dos elementos empatados no bailen de posición entre repintados.
     *
     * Aprovecha para rellenar [RecountEntry.share] con [totalPlays] de denominador. Se hace al final,
     * y no mientras se acumulan los totales, porque hasta que no está la lista entera no se sabe cuál
     * es el total del año.
     */
    private fun List<RecountEntry>.topTen(totalPlays: Int): List<RecountEntry> = sortedWith(
        compareByDescending<RecountEntry> { it.plays }
            .thenByDescending { it.playedMs }
            .thenBy { it.label.lowercase() }
    ).take(TOP_SIZE).map { entry ->
        entry.copy(share = if (totalPlays > 0) entry.plays.toFloat() / totalPlays else 0f)
    }

    private fun yearOf(epochMs: Long): Int =
        Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.YEAR)

    companion object {
        private const val TOP_SIZE = 10

        /** Por debajo de esta fracción del total, un género no se dibuja como sector propio y entra
         *  en "Otros" (sigue apareciendo entero en la leyenda). */
        private const val MIN_SLICE_FRACTION = 0.02f

        /** "Más de 2 años seguidos sin nada" — a partir de 3, el hueco se resume con una raya. */
        private const val MIN_GAP_LENGTH = 3

        @Volatile
        private var instance: RecountRepository? = null

        fun get(context: Context): RecountRepository =
            instance ?: synchronized(this) {
                instance ?: RecountRepository(
                    dao = UltiMusicDatabase.get(context).libraryDao(),
                    libraryRepository = LibraryRepository.get(context)
                ).also { instance = it }
            }
    }
}
