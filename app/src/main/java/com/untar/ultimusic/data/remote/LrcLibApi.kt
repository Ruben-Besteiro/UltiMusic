package com.untar.ultimusic.data.remote

import com.untar.ultimusic.model.LyricsSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Cliente de lrclib.net (base de datos libre y colaborativa de letras sincronizadas), para el
 * buscador de letras del editor de metadatos (ver
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]).
 *
 * Igual que [ItunesApi], es un `object` sin estado, usa `HttpURLConnection` y `org.json` (ya
 * vienen con Android, y la app solo hace un puñado de llamadas de red, así que no compensa una
 * librería aparte) y manda un `User-Agent` identificando la app por buena práctica.
 *
 * A diferencia de la API oficial de YouTube (ver el javadoc de
 * [com.untar.ultimusic.ui.player.VideoPickerDialogFragment] sobre por qué esa se evita con un
 * WebView), la de lrclib.net es pública y gratuita sin clave ni cuenta, y no exige borrar ni
 * refrescar los datos: se puede llamar directamente y guardar la letra elegida para siempre, igual
 * que se hace con los candidatos de iTunes.
 */
object LrcLibApi {

    private const val BASE_URL = "https://lrclib.net/api/"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /**
     * Margen para dar por buena la duración de un candidato. lrclib.net guarda la duración en
     * SEGUNDOS, y quien sube la letra la manda ya redondeada, así que dos copias del mismo archivo
     * pueden estar registradas con un segundo de diferencia: este margen absorbe justo ese
     * redondeo y nada más (lrclib.net es más laxo —admite 2 s al buscar una letra por duración—
     * pero con ese margen ya cabe una versión distinta de verdad, que es precisamente lo que la
     * marca quiere descartar).
     */
    private const val DURATION_TOLERANCE_MS = 1_000L

    /**
     * Busca grabaciones de [title]/[artist]. lrclib.net no distingue publicaciones como iTunes (no
     * hay álbum/single que deduplicar): cada resultado es directamente una grabación con su letra
     * ya incluida, así que no hace falta ninguna petición aparte al elegir una.
     *
     * [songDurationMs] es la duración del archivo que se está editando (0 si se desconoce), que se
     * coteja con la de cada candidato ([LyricsSuggestion.durationMatches]).
     *
     * Se descartan las pistas instrumentales y las que no traen letra de ningún tipo (nada que
     * ofrecerle al usuario). El resto se ordena dejando primero las que tienen letra SINCRONIZADA,
     * que es el motivo de ser de este buscador, y dentro de cada grupo las que además duran lo
     * mismo que el archivo: son las que se sincronizaron contra ESA grabación y no irán desfasadas.
     */
    suspend fun search(
        title: String,
        artist: String,
        songDurationMs: Long
    ): List<LyricsSuggestion> =
        runSearch(
            "track_name=${URLEncoder.encode(title, "UTF-8")}" +
                "&artist_name=${URLEncoder.encode(artist, "UTF-8")}",
            songDurationMs
        )

    /**
     * Igual que [search], pero para la barra de búsqueda manual del diálogo (ver
     * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]): un único campo de texto
     * libre en vez de título+artista separados, así que aquí se usa el parámetro genérico `q` de
     * lrclib.net (busca por título, artista y álbum a la vez) en lugar de `track_name`/`artist_name`.
     */
    suspend fun search(query: String, songDurationMs: Long): List<LyricsSuggestion> =
        runSearch("q=${URLEncoder.encode(query, "UTF-8")}", songDurationMs)

    /**
     * Un resultado de lrclib.net tal cual, SIN filtrar ni ordenar, para la búsqueda automática (ver
     * [com.untar.ultimusic.data.LyricsAutoFetcher]): a diferencia del buscador manual, esa necesita
     * ver también los instrumentales, porque que el mejor candidato lo sea es justo lo que le dice
     * que deje de buscar letra para esa canción.
     */
    data class Candidate(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val durationMs: Long?,
        val instrumental: Boolean,
        val syncedLyrics: String?,
        val plainLyrics: String?
    )

    /** Búsqueda por campos para [com.untar.ultimusic.data.LyricsAutoFetcher]; [album] opcional,
     *  como la segunda variante de consulta de namida. Lanza excepción si falla la red. */
    suspend fun searchCandidates(title: String, artist: String, album: String?): List<Candidate> =
        withContext(Dispatchers.IO) {
            val params = buildList {
                add("track_name=${URLEncoder.encode(title, "UTF-8")}")
                if (artist.isNotBlank()) add("artist_name=${URLEncoder.encode(artist, "UTF-8")}")
                if (!album.isNullOrBlank()) add("album_name=${URLEncoder.encode(album, "UTF-8")}")
            }.joinToString("&")
            fetchCandidates(params)
        }

    private fun fetchCandidates(queryParams: String): List<Candidate> {
        val results = httpGet("${BASE_URL}search?$queryParams")
        return (0 until results.length()).map { i ->
            val item = results.getJSONObject(i)
            Candidate(
                trackName = item.optString("trackName"),
                artistName = item.optString("artistName"),
                albumName = item.optText("albumName"),
                durationMs = item.optDouble("duration").takeIf { !it.isNaN() }?.let { (it * 1000).toLong() },
                instrumental = item.optBoolean("instrumental"),
                syncedLyrics = item.optText("syncedLyrics"),
                plainLyrics = item.optText("plainLyrics")
            )
        }
    }

    /**
     * Campo de texto o null. Ojo: el `optString` de Android convierte un `null` JSON en el TEXTO
     * "null", y lrclib.net devuelve `null` en las letras que no tiene; sin este filtro ese "null"
     * pasaba por letra de verdad y se guardaba tal cual. También se descarta si viene como texto.
     */
    private fun JSONObject.optText(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && !it.trim().equals("null", ignoreCase = true) }
    }

    private suspend fun runSearch(queryParams: String, songDurationMs: Long): List<LyricsSuggestion> =
        withContext(Dispatchers.IO) {
            val suggestions = fetchCandidates(queryParams)
                .filter { !it.instrumental && (it.syncedLyrics != null || it.plainLyrics != null) }
                .map { candidate ->
                    LyricsSuggestion(
                        trackName = candidate.trackName,
                        artistName = candidate.artistName,
                        albumName = candidate.albumName,
                        durationMs = candidate.durationMs,
                        durationMatches = durationMatches(songDurationMs, candidate.durationMs),
                        syncedLyrics = candidate.syncedLyrics,
                        plainLyrics = candidate.plainLyrics
                    )
                }

            // Dos criterios, en este orden: primero la letra sincronizada (sin ella el buscador no
            // tiene sentido) y luego la duración. `sortedWith` mantiene el orden relativo original
            // dentro de cada grupo, que es el de relevancia que ya da lrclib.net.
            suggestions.sortedWith(
                compareBy({ if (it.syncedLyrics != null) 0 else 1 }, { if (it.durationMatches) 0 else 1 })
            )
        }

    /** Sin duración por alguno de los dos lados no se puede afirmar nada, así que no coincide. */
    private fun durationMatches(songDurationMs: Long, candidateMs: Long?): Boolean {
        if (songDurationMs <= 0L || candidateMs == null) return false
        return abs(songDurationMs - candidateMs) <= DURATION_TOLERANCE_MS
    }

    /** Castigo por rate limit de lrclib.net, independiente del de las otras dos APIs. lrclib.net no
     *  publica un límite y la mantienen voluntarios, así que aquí portarse bien importa más todavía
     *  que en las comerciales. */
    private val rateLimitGuard = RateLimitGuard("lrclib.net")

    /** GET genérico, ahora con caché y freno de rate limit: ver [HttpJson], que es donde vive todo
     *  eso. Sigue convirtiendo cualquier respuesta que no sea "200 OK" en una excepción, que quien
     *  llame captura con `runCatching` (ver [LyricsSuggestionsViewModel]). */
    private fun httpGet(url: String): JSONArray =
        JSONArray(
            HttpJson.get(
                url = url,
                service = "lrclib.net",
                guard = rateLimitGuard,
                userAgent = USER_AGENT
            )
        )
}
