package com.untar.ultimusic.data.remote

import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.ReleaseType
import com.untar.ultimusic.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Cliente de la iTunes Search API (el catálogo de la tienda de Apple, el mismo que consulta la app
 * Música), para el autorrelleno de metadatos del editor (ver
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment]).
 *
 * Es un `object` (un singleton de Kotlin: una única instancia compartida por toda la app, sin
 * necesidad de pedirla ni de guardarla en ningún sitio) en vez de una clase con constructor,
 * porque no necesita ningún `Context` de Android ni guarda ningún estado entre peticiones — cada
 * función hace la suya y devuelve, sin recordar nada de la anterior.
 *
 * Se usa `HttpURLConnection` (viene con el propio Android) en vez de una librería de red como
 * OkHttp o Retrofit: la app entera solo hace unos pocos tipos de llamada de red (esta, lrclib.net y
 * la miniatura de YouTube, ver [com.untar.ultimusic.data.LibraryRepository.downloadVideoThumbnail]),
 * así que no compensa la dependencia extra. Por el mismo motivo se parsean las respuestas a mano
 * con `org.json` (también viene con Android) en vez de con Gson o Moshi: son unos pocos campos de
 * un único tipo de respuesta, no vale la pena generar clases para ello.
 *
 * La API es pública y gratuita, sin clave ni cuenta y sin `User-Agent` obligatorio (aun así se
 * manda uno identificando la app, por cortesía y para poder ser contactados, igual que en
 * [LrcLibApi]). Apple no publica un límite oficial de peticiones, pero recomienda no pasar de unas
 * 20 por minuto; la búsqueda es un disparo suelto por gesto del usuario, así que solo el scroll
 * infinito de [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel] puede encadenar varias
 * seguidas — ahí el respiro entre peticiones vive en el propio ViewModel, no aquí.
 *
 * ## Por qué iTunes y no MusicBrainz
 *
 * Antes esto era un cliente de MusicBrainz. iTunes cambia el modelo de datos por completo, y a
 * mejor para lo que hace falta aquí:
 * - Cada resultado de canción YA es "esta grabación dentro de esta publicación concreta" (la
 *   `collection`), que es justo la unidad que representa [MetadataSuggestion]: se puede elegir el
 *   año y la portada del single en vez de los del álbum donde acabó apareciendo, sin tener que
 *   deduplicar ediciones por país como obligaba MusicBrainz.
 * - El género viene YA en la respuesta de búsqueda (`primaryGenreName`), así que desaparece la
 *   segunda petición que MusicBrainz exigía para pedirlos aparte.
 * - La portada viene siempre (`artworkUrl100`) y con la resolución que se pida (ver
 *   [fullResCoverUrl]), en vez de depender de que alguien la hubiera subido a Cover Art Archive.
 *
 * A cambio se pierden dos cosas que MusicBrainz sí daba: el país de publicación (iTunes solo sabe
 * en qué TIENDA se vende, ver [storefront]) y los tipos secundarios de publicación (en directo,
 * recopilatorio, banda sonora, remix). Por eso el campo "País" ya no existe en el editor y la
 * etiqueta de cada fila se queda en Álbum/Single/EP (ver [ReleaseType]).
 */
object ItunesApi {

    private const val BASE_URL = "https://itunes.apple.com/search"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Cuántos resultados se piden por página. iTunes admite hasta 200 de golpe, pero se piden de
     * 25 en 25 para que la primera pantalla de sugerencias llegue cuanto antes: el resto lo va
     * trayendo el scroll infinito conforme haga falta. */
    private const val PAGE_SIZE = 25

    /** Tienda que se usa si el dispositivo no tiene ninguna región configurada (ver [storefront]).
     * La de EE. UU. es la de catálogo más amplio, así que es la reserva menos mala. */
    private const val DEFAULT_STOREFRONT = "US"

    /**
     * iTunes busca dentro de UNA tienda por petición, y la tienda decide tanto qué hay en el
     * catálogo como en qué idioma vienen los títulos, los nombres de artista y los géneros. Se usa
     * la región del propio dispositivo para que los resultados salgan en el idioma en el que el
     * usuario tiene el móvil, y para que el catálogo sea el de su país (que es el que de verdad
     * conoce). `Locale.getDefault().country` da el código ISO de dos letras ("ES", "JP", "US"…),
     * exactamente el formato que espera el parámetro `country` de la API; puede venir vacío si el
     * idioma configurado no lleva región asociada, de ahí [DEFAULT_STOREFRONT].
     */
    private val storefront: String
        get() = Locale.getDefault().country.takeIf { it.length == 2 } ?: DEFAULT_STOREFRONT

    /**
     * Las URLs de carátula de iTunes acaban siempre en un segmento con el tamaño pedido
     * (`…/abc.jpg/100x100bb.jpg`): cambiándolo se pide esa misma imagen en cualquier resolución,
     * sin tener que consultar nada. Antes se pedía `100000x100000-999` como modo "dame el
     * original", pero el CDN de Apple ya responde 400 a esas dimensiones (comprobado a mano); por
     * eso ahora se pide [MAX_SIZE], que el CDN sí sirve y que sigue siendo mucho mayor que ninguna
     * carátula real (Apple nunca aloja más de unos 3000×3000), así que en la práctica se sigue
     * consiguiendo el original.
     *
     * Este era justo el fallo detrás de "la carátula no se aplica al autorrellenar": cuando Genius
     * no reconoce la canción (nombre distinto al de iTunes, p. ej.) no hay carátula propia con la
     * que sustituir esta, la descarga de la de iTunes fallaba en silencio (ver
     * [com.untar.ultimusic.util.NetworkImage.download], best-effort) y el editor se quedaba tal
     * cual estaba. Con Genius sí encontrando la canción no se notaba, porque su URL de carátula es
     * otra distinta y no pasa por aquí.
     *
     * [MetadataSuggestion.coverUrl] se queda en [THUMB_SIZE] porque ahí solo hace falta pintar un
     * thumbnail en la lista de sugerencias.
     */
    fun fullResCoverUrl(coverUrl: String): String = resizeArtwork(coverUrl, MAX_SIZE)

    /** Mayor tamaño que el CDN de Apple sirve sin devolver 400 (ver [fullResCoverUrl]). */
    private const val MAX_SIZE = "3000x3000bb.jpg"

    /** Tamaño de la miniatura de la lista de sugerencias. Se pide bastante mayor que los 100px que
     * da iTunes por defecto porque la fila la pinta a unos 56dp, que en una pantalla densa son
     * bastantes más de 100 píxeles reales. */
    private const val THUMB_SIZE = "250x250bb.jpg"

    private fun resizeArtwork(url: String, size: String): String =
        url.substringBeforeLast('/') + "/" + size

    /**
     * Una página de candidatos ya filtrados y deduplicados más lo necesario para pedir la
     * siguiente: [nextOffset] es el `offset` que hay que mandar en la próxima llamada, y [hasMore]
     * dice si es razonable esperar que quede algo detrás.
     *
     * A diferencia de MusicBrainz, iTunes NO devuelve el total de resultados que ha encontrado
     * (`resultCount` es solo cuántos trae ESTA página), así que [hasMore] se deduce: si la página
     * ha venido llena hasta el tope pedido, casi seguro hay más; si ha venido corta, se ha llegado
     * al final. Es también lo que corta el scroll infinito cuando se alcanza el tope de resultados
     * que iTunes está dispuesto a paginar.
     */
    data class SearchPage(
        val suggestions: List<MetadataSuggestion>,
        val nextOffset: Int,
        val hasMore: Boolean
    )

    /**
     * Busca canciones por título y artista. Cada elemento de la lista es UNA publicación (álbum,
     * single…) en la que salió la canción encontrada: la misma canción puede aparecer varias veces,
     * una por cada publicación distinta (ver la documentación de [MetadataSuggestion] sobre por qué
     * esto es justo lo que hace falta para distinguir el single del álbum).
     *
     * [offset] es la página a pedir (0 = la primera): ver [SearchPage].
     *
     * La consulta es texto libre con título Y artista juntos. iTunes no permite restringir a varios
     * campos a la vez (su parámetro `attribute` acepta uno solo: o `songTerm`, o `artistTerm`…),
     * pero su búsqueda libre ya mira título, artista y álbum a la vez y ordena por relevancia y
     * popularidad, así que meter los dos en el mismo `term` es lo que mejor acota — sin el artista,
     * un título común ("Hello") devuelve cientos de canciones sin relación y la buena puede quedar
     * fuera del tope que iTunes pagina.
     *
     * Aun así se reordena cada página para subir del todo arriba los candidatos que de verdad
     * coinciden con lo buscado (ver [matchesAnyArtist] y [TextMatch]): iTunes ordena sobre todo
     * por popularidad, así que una versión de otro artista más famoso puede colarse por delante de
     * la que el usuario tiene en el archivo.
     */
    suspend fun searchSongs(title: String, artist: String, offset: Int = 0): SearchPage =
        withContext(Dispatchers.IO) {
            val results = searchWithArtistFallback(entity = "song", main = title, artist = artist, offset = offset)
            val searchedArtists = splitArtists(artist)

            // Una misma publicación puede salir dos veces (la versión de estudio y una remasterizada
            // que comparten álbum, por ejemplo). Como todos los resultados son de la MISMA canción
            // buscada, quedarse con la primera de cada colección es quedarse con la más relevante.
            val usedCollections = mutableSetOf<Long>()
            val ranked = mutableListOf<RankedSuggestion>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                // media=music&entity=song no debería traer otra cosa, pero un videoclip colado
                // rellenaría el formulario con datos que no son de la canción.
                if (item.optString("kind") != "song") continue

                val collectionId = item.optLong("collectionId", 0L)
                if (collectionId == 0L || !usedCollections.add(collectionId)) continue

                val collectionName = item.optString("collectionName")
                val trackCount = item.optInt("trackCount", 0)
                val artistName = item.optString("artistName")

                val suggestion = MetadataSuggestion(
                    title = item.optString("trackName"),
                    artist = artistName,
                    albumTitle = cleanCollectionName(collectionName),
                    year = parseYear(item.optString("releaseDate", null)),
                    coverUrl = item.optString("artworkUrl100", null)?.let { resizeArtwork(it, THUMB_SIZE) },
                    genre = item.optString("primaryGenreName", null)?.takeIf { it.isNotBlank() },
                    trackNumber = item.optInt("trackNumber", 0).takeIf { it > 0 },
                    discNumber = item.optInt("discNumber", 0).takeIf { it > 0 },
                    releaseType = releaseTypeOf(collectionName, trackCount)
                )
                ranked += RankedSuggestion(
                    suggestion,
                    matchRank(
                        artistMatches = matchesAnyArtist(searchedArtists, artistName),
                        titleMatches = TextMatch.looselyEqual(title, suggestion.title)
                    )
                )
            }

            SearchPage(
                suggestions = ranked.sortedBy { it.rank }.map { it.suggestion },
                nextOffset = offset + results.length(),
                hasMore = results.length() == PAGE_SIZE
            )
        }

    /**
     * Busca álbumes por título y artista. Más simple que [searchSongs]: cada resultado de iTunes ya
     * es un álbum entero (una `collection`), sin pista ni disco que mirar dentro. [offset] es la
     * página a pedir (0 = la primera): ver [SearchPage]. Mismo texto libre y mismo reordenado por
     * coincidencia que en [searchSongs], por los mismos motivos.
     */
    suspend fun searchAlbums(album: String, artist: String, offset: Int = 0): SearchPage =
        withContext(Dispatchers.IO) {
            val results = searchWithArtistFallback(entity = "album", main = album, artist = artist, offset = offset)
            val searchedArtists = splitArtists(artist)

            val usedCollections = mutableSetOf<Long>()
            val ranked = mutableListOf<RankedSuggestion>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val collectionId = item.optLong("collectionId", 0L)
                if (collectionId == 0L || !usedCollections.add(collectionId)) continue

                val collectionName = item.optString("collectionName")
                val artistName = item.optString("artistName")

                val suggestion = MetadataSuggestion(
                    title = cleanCollectionName(collectionName),
                    artist = artistName,
                    // En una sugerencia de álbum, `title` YA es el título del álbum.
                    albumTitle = null,
                    year = parseYear(item.optString("releaseDate", null)),
                    coverUrl = item.optString("artworkUrl100", null)?.let { resizeArtwork(it, THUMB_SIZE) },
                    genre = item.optString("primaryGenreName", null)?.takeIf { it.isNotBlank() },
                    trackNumber = null,
                    discNumber = null,
                    releaseType = releaseTypeOf(collectionName, item.optInt("trackCount", 0))
                )
                ranked += RankedSuggestion(
                    suggestion,
                    matchRank(
                        artistMatches = matchesAnyArtist(searchedArtists, artistName),
                        titleMatches = TextMatch.looselyEqual(album, suggestion.title)
                    )
                )
            }

            SearchPage(
                suggestions = ranked.sortedBy { it.rank }.map { it.suggestion },
                nextOffset = offset + results.length(),
                hasMore = results.length() == PAGE_SIZE
            )
        }

    // --- Peticiones HTTP ---

    /**
     * Pide una página con "título artista" y, si no encuentra NADA, la repite solo con el título.
     *
     * El motivo es que iTunes exige que todas las palabras del `term` casen con algo del catálogo:
     * si el artista está acreditado en la tienda con un nombre distinto al que el usuario tiene en
     * sus etiquetas (p. ej. "ピノキオピー" en la tienda japonesa frente a "PinocchioP" en el
     * archivo), meterlo en la consulta no acota la búsqueda — la vacía del todo. Cuando eso pasa,
     * el título solo sigue encontrando la canción, y el reordenado por coincidencia de artista
     * (que sí normaliza, ver [matchesAnyArtist]) hace el resto.
     *
     * La reserva se aplica en CUALQUIER página, no solo en la primera: así, si una búsqueda entera
     * va por el camino del título solo, todas sus páginas van por el mismo y el scroll infinito
     * sigue avanzando por un único listado coherente, sin tener que recordar entre llamadas cuál de
     * las dos consultas se usó.
     */
    private fun searchWithArtistFallback(
        entity: String,
        main: String,
        artist: String,
        offset: Int
    ): JSONArray {
        val combined = listOf(main, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val results = httpGetResults(searchUrl(entity, combined, offset))
        if (results.length() > 0 || artist.isBlank() || main.isBlank()) return results
        return httpGetResults(searchUrl(entity, main.trim(), offset))
    }

    private fun searchUrl(entity: String, term: String, offset: Int): String =
        "$BASE_URL?term=${URLEncoder.encode(term, "UTF-8")}" +
            "&country=$storefront&media=music&entity=$entity&limit=$PAGE_SIZE&offset=$offset"

    /** Castigo por rate limit de iTunes, independiente del de las otras dos APIs. Apple no publica
     *  un límite oficial pero recomienda no pasar de unas 20 peticiones por minuto **y por IP**;
     *  como cada petición sale del móvil de cada usuario, esto protege sobre todo del scroll rápido
     *  de una sola persona, no de la suma de todas. */
    private val rateLimitGuard = RateLimitGuard("iTunes")

    /** GET genérico, ahora con caché y freno de rate limit ([HttpJson]). Devuelve ya el array
     * `results`, que es lo único que interesa de la respuesta, y sigue convirtiendo cualquier
     * respuesta que no sea "200 OK" en una excepción (quien llame la captura con `runCatching`, ver
     * [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel]). */
    private fun httpGetResults(url: String): JSONArray {
        val body = HttpJson.get(
            url = url,
            service = "iTunes",
            guard = rateLimitGuard,
            userAgent = USER_AGENT
        )
        return JSONObject(body).optJSONArray("results") ?: JSONArray()
    }

    // --- Ayudas de parseo ---

    /** Las fechas de iTunes vienen en ISO completo ("2007-11-06T08:00:00Z"), pero basta el año, así
     * que se toman los 4 primeros caracteres. */
    private fun parseYear(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /**
     * iTunes no tiene un campo con el tipo de publicación: lo mete en el propio nombre de la
     * colección, que para los singles y los EP acaba en " - Single" / " - EP" (en la tienda del
     * idioma que sea, pero esas dos palabras no se traducen). El número de pistas sirve de
     * respaldo, porque algún single antiguo no lleva el sufijo.
     */
    private fun releaseTypeOf(collectionName: String, trackCount: Int): ReleaseType = when {
        collectionName.endsWith(SINGLE_SUFFIX, ignoreCase = true) -> ReleaseType.SINGLE
        collectionName.endsWith(EP_SUFFIX, ignoreCase = true) -> ReleaseType.EP
        trackCount == 1 -> ReleaseType.SINGLE
        else -> ReleaseType.ALBUM
    }

    /** Quita el sufijo de tipo del nombre de la colección (ver [releaseTypeOf]): en el campo
     * "Álbum(es)" del editor tiene que quedar el nombre a secas ("Rejected Diva"), no el rótulo de
     * la tienda ("Rejected Diva - Single"). El tipo no se pierde: se enseña como etiqueta propia de
     * la fila (ver [MetadataSuggestion.releaseType]). */
    private fun cleanCollectionName(collectionName: String): String = collectionName
        .removeSuffix(SINGLE_SUFFIX)
        .removeSuffix(EP_SUFFIX)
        .trim()

    private const val SINGLE_SUFFIX = " - Single"
    private const val EP_SUFFIX = " - EP"

    // --- Reordenado por coincidencia (ver searchSongs/searchAlbums) ---

    /** Candidato con su puesto en la cola de reordenado (ver [matchRank]) — solo vive dentro de
     * una página, nunca sale de aquí: [SearchPage] ya entrega la lista pura, sin este número. */
    private data class RankedSuggestion(val suggestion: MetadataSuggestion, val rank: Int)

    /**
     * 0 (el mejor puesto) si coinciden artista Y título, 1 si coincide solo el artista, 2 si
     * coincide solo el título, 3 si no coincide nada — `sortedBy` (estable) respeta el orden de
     * iTunes dentro de cada grupo, así que esto solo reordena "en bloques", nunca revuelve
     * candidatos igual de buenos entre sí.
     */
    private fun matchRank(artistMatches: Boolean, titleMatches: Boolean): Int = when {
        artistMatches && titleMatches -> 0
        artistMatches -> 1
        titleMatches -> 2
        else -> 3
    }

    /** Divide "Artista1, Artista2" en cada nombre suelto — mismo separador de comas que usan los
     * campos multivalor del editor (ver `multi_value_hint`) — y descarta los trozos vacíos. */
    private fun splitArtists(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * true si ALGUNO de [searchedArtists] coincide (normalizado, ver [TextMatch]) con el artista
     * acreditado por iTunes. Sin artistas buscados (el usuario buscó solo por título), no
     * discrimina: cuenta como coincidencia para no penalizar a nadie.
     *
     * A diferencia de MusicBrainz, iTunes acredita un único nombre por canción y no expone alias ni
     * transliteraciones, así que aquí solo hay un texto contra el que comparar; los nombres
     * compuestos ("Fulanito feat. Menganita") sí se cubren porque la comparación es laxa.
     */
    private fun matchesAnyArtist(searchedArtists: List<String>, creditedArtist: String): Boolean {
        if (searchedArtists.isEmpty()) return true
        return searchedArtists.any { TextMatch.looselyEqual(it, creditedArtist) }
    }
}
