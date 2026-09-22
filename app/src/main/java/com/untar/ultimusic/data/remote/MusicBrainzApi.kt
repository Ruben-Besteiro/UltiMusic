package com.untar.ultimusic.data.remote

import android.net.Uri
import com.untar.ultimusic.model.DiscographyAlbum
import com.untar.ultimusic.model.DiscographyTrack
import com.untar.ultimusic.model.StoreLink
import com.untar.ultimusic.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Cliente de MusicBrainz (musicbrainz.org), la base de datos musical libre editada por su
 * comunidad, para la discografía oficial de un artista (ver
 * [com.untar.ultimusic.ui.library.DiscographyDialogFragment]).
 *
 * No es [ItunesApi]: el catálogo de álbumes de iTunes mezcla sin remedio la edición original de
 * cada disco con TODAS sus reediciones (remasterizada, deluxe, expandida, caja recopilatoria...) y
 * no distingue un álbum de estudio de un directo o un recopilatorio, así que una discografía sacada
 * de ahí saldría con "Kill 'Em All" repetido media docena de veces y bootlegs mezclados con
 * publicaciones oficiales. MusicBrainz agrupa esas ediciones bajo un único "grupo de publicación"
 * (`release-group`) y, crucialmente, sabe distinguir un álbum de estudio de un directo, un
 * recopilatorio o una banda sonora (`secondary-types`) — justo lo que hace falta para "álbumes y
 * EPs, sin singles ni recopilatorios". No es perfecto (la ficha de un artista muy versionado, sobre
 * todo con bootlegs, puede colar algún directo mal etiquetado o dejar fuera alguna rareza), pero es
 * sensiblemente más limpio que la alternativa.
 *
 * Es gratuita y sin clave ni cuenta, como las otras dos APIs de la aplicación, pero con una
 * condición propia: pide no pasar de una petición por segundo por IP (documentado, no solo
 * recomendado como en iTunes), así que aquí SÍ hay que espaciar las peticiones ANTES de mandarlas
 * (ver [paced]) y no solo reaccionar a un 429 como hace [RateLimitGuard] con las otras dos — de
 * hecho ni siquiera contesta 429 cuando se pasa: contesta 503 "currently busy" (comprobado a
 * mano), que sin este freno propio se leería como un fallo de red cualquiera y no evitaría que la
 * siguiente petición volviera a salir demasiado pronto.
 */
object MusicBrainzApi {

    private const val BASE_URL = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Cover Art Archive: una imagen por publicación, indexada por el mismo id de MusicBrainz. Se
     *  pide directamente como URL de imagen (redirige al archivo): no hace falta ninguna petición a
     *  la API de MusicBrainz para saber si existe, Coil ya sabe seguir la redirección y el `error()`
     *  de quien la carga se encarga de cuando no hay ninguna carátula subida. */
    private const val COVER_ART_ARCHIVE = "https://coverartarchive.org"

    /** Mínimo respiro entre dos peticiones (ver la documentación de la clase), con un pelín de
     *  margen sobre el segundo exacto que pide MusicBrainz. */
    private const val MIN_INTERVAL_MS = 1_100L

    /** Umbral de relevancia (0-100, lo da el propio buscador) para aceptar un artista sin
     *  coincidencia exacta de nombre; ver [resolveArtistId]. */
    private const val HIGH_SCORE_THRESHOLD = 95

    /** Tope de grabaciones de MusicBrainz que se consultan por canción (ver [downloadStores]): cada
     *  una cuesta dos peticiones a 1 por segundo, y un mismo ISRC puede casar con varias. */
    private const val MAX_RECORDINGS = 2

    /** Relaciones URL de MusicBrainz que significan "aquí se consigue el archivo". */
    private const val FREE_DOWNLOAD_TYPE = "download for free"
    private val STORE_RELATION_TYPES = setOf("purchase for download", FREE_DOWNLOAD_TYPE)

    private val rateLimitGuard = RateLimitGuard("MusicBrainz")

    /** Instante de la última petición mandada a este servicio, para el freno de [paced]. Todas las
     *  peticiones de una discografía se hacen en secuencia (nunca a la vez), así que basta con una
     *  marca de tiempo simple, sin candado. */
    @Volatile
    private var lastRequestAtMs = 0L

    /**
     * Discografía oficial de [artistName]: álbumes y EPs (sin singles, directos, recopilatorios,
     * bandas sonoras...), ordenados por fecha de publicación, cada uno con su lista de pistas.
     *
     * Puede tardar varios segundos: aparte de resolver el artista y pedir su lista de publicaciones
     * (dos peticiones), hace falta UNA petición más por cada álbum para traer su lista de pistas
     * (ver [tracksFor]), y todas van espaciadas por el freno de [paced]. Quien llame debe enseñar un
     * indicador de carga mientras tanto.
     *
     * Vacía —sin lanzar— si el artista no se encuentra en MusicBrainz. Cualquier fallo de red o del
     * propio servicio se deja subir tal cual, para que quien llame distinga "no hay nada que
     * enseñar" de "no se ha podido comprobar" (mismo reparto que [ItunesApi]).
     */
    suspend fun discography(artistName: String): List<DiscographyAlbum> = withContext(Dispatchers.IO) {
        val artistId = resolveArtistId(artistName) ?: return@withContext emptyList()
        releaseGroupsOf(artistId).mapNotNull { rg ->
            // A diferencia de resolveArtistId/releaseGroupsOf (donde un fallo de red aborta la
            // discografía entera, ver la documentación de esta función), aquí se salta solo ESTE
            // álbum: con quince peticiones en secuencia, que una tropiece no debería tirar las
            // catorce que sí han ido bien. Un reintento (espaciado igual que cualquier otra
            // petición, ver paced()) antes de rendirse: un solo 503 puntual de MusicBrainz a mitad
            // de la secuencia no debería dejar caer el álbum entero de la lista.
            val tracks = runCatching { tracksFor(rg.optString("id")) }
                .recoverCatching { tracksFor(rg.optString("id")) }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) return@mapNotNull null
            DiscographyAlbum(
                title = rg.optString("title"),
                year = rg.optString("first-release-date", null)?.take(4)?.toIntOrNull(),
                coverUrl = "$COVER_ART_ARCHIVE/release-group/${rg.optString("id")}/front-250",
                tracks = tracks
            )
        }
    }

    // --- Resolver el artista ---

    /**
     * El `id` (MBID) de [artistName] en MusicBrainz, o null si no hay ningún candidato razonable.
     * Se prefiere una coincidencia EXACTA normalizada (ver [TextMatch.normalize]) entre los primeros
     * resultados; si no hay ninguna, se acepta el más relevante según el propio buscador de
     * MusicBrainz (campo `score`, 0-100) siempre que sea muy alto — una variante de escritura menor
     * ("Beyoncé" sin tilde, por ejemplo) no debería dejar al usuario sin discografía por un detalle
     * así.
     */
    private suspend fun resolveArtistId(artistName: String): String? {
        if (artistName.isBlank()) return null
        val url = "$BASE_URL/artist/?query=${URLEncoder.encode("artist:${luceneQuote(artistName)}", "UTF-8")}" +
            "&fmt=json&limit=5"
        // Un fallo de red o del propio servicio se deja subir tal cual (ver la documentación de
        // discography()): solo un array "artists" vacío o ausente —la petición SÍ ha respondido,
        // simplemente no hay ningún artista con ese nombre— cuenta como "no encontrado".
        val results = JSONObject(paced(url)).optJSONArray("artists") ?: return null

        val normalizedQuery = TextMatch.normalize(artistName)
        var bestFallback: JSONObject? = null
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            if (TextMatch.normalize(candidate.optString("name")) == normalizedQuery) {
                return candidate.optString("id")
            }
            if (bestFallback == null && candidate.optInt("score", 0) >= HIGH_SCORE_THRESHOLD) {
                bestFallback = candidate
            }
        }
        return bestFallback?.optString("id")
    }

    // --- Publicaciones (álbumes y EPs) ---

    /**
     * Grupos de publicación de tipo álbum o EP de [artistId] que no llevan NINGÚN tipo secundario
     * (directo, recopilatorio, banda sonora, remix...) — ver la documentación de la clase sobre por
     * qué esto y no el catálogo crudo de iTunes. Ordenados por fecha de la primera publicación (los
     * que no la tienen, al final).
     *
     * Se usa el buscador (`/release-group/`, con `query`) y no el navegador por artista
     * (`/release-group?artist=`) precisamente porque el navegador no deja filtrar por "sin tipo
     * secundario" en el propio servidor: con un artista muy versionado (bootlegs, sobre todo) puede
     * devolver miles de grupos entre los que habría que paginar a mano para separar los pocos
     * álbumes de estudio del resto, una petición por página a 1 por segundo. El buscador hace ese
     * filtrado él mismo y basta con una sola petición.
     */
    private suspend fun releaseGroupsOf(artistId: String): List<JSONObject> {
        val query = "arid:$artistId AND (primarytype:Album OR primarytype:EP) AND NOT secondarytype:*"
        val url = "$BASE_URL/release-group/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=100"
        val results = JSONObject(paced(url)).optJSONArray("release-groups") ?: JSONArray()
        return results.asList().sortedBy { it.optString("first-release-date", null) ?: "9999" }
    }

    // --- Pistas de un álbum ---

    /** Una edición oficial de un álbum, con sus pistas agrupadas por disco/medio TAL CUAL las trae
     *  esa edición (ver [tracksFor]: el índice de cada elemento de [media] es el número de disco,
     *  1-based). */
    private data class ReleaseCandidate(val date: String?, val media: List<List<JSONObject>>) {
        val totalTracks: Int get() = media.sumOf { it.size }
    }

    /**
     * Lista de pistas de la edición "canónica" del grupo de publicación [releaseGroupId]: entre
     * todas sus ediciones oficiales (remasterizaciones, prensados por país, vinilo partido en
     * caras, promocionales sueltos que a veces quedan mal enlazados al grupo...) se elige la que
     * MÁS SE REPITE por número total de pistas —el recuento "de consenso" entre todas las
     * ediciones oficiales, mucho más fiable que quedarse con la que menos pistas tenga: una
     * promocional de dos temas sueltos, si la hay, sería justo esa mínima y no el álbum real—.
     *
     * El recuento de consenso se calcula SOLO entre las ediciones de un único disco/medio, y no
     * entre todas: así una vinilo repartida en dos caras (que trae las MISMAS pistas que la
     * edición normal, solo que partidas en dos con la numeración de cada una empezando de nuevo
     * en 1, ver [DiscographyTrack.discNumber]) no puede colarse por casualidad como si fuera el
     * recuento real. Solo se cae a TODAS las ediciones (de uno o más discos) si no hay ninguna de
     * un único disco. Empate de recuento, el más pequeño (la edición base, no una ampliada que por
     * lo que sea empatara en frecuencia); empate de fecha, la más antigua.
     *
     * Vacía si el grupo no tiene ninguna edición oficial con pistas (fallo de red puntual, o
     * simplemente sin datos en MusicBrainz).
     */
    private suspend fun tracksFor(releaseGroupId: String): List<DiscographyTrack> {
        val url = "$BASE_URL/release?release-group=$releaseGroupId&inc=recordings&status=official" +
            "&fmt=json&limit=100"
        val releases = JSONObject(paced(url)).optJSONArray("releases") ?: return emptyList()

        val candidates = releases.asList().mapNotNull { release ->
            val mediaArray = release.optJSONArray("media")?.asList() ?: return@mapNotNull null
            val media: List<List<JSONObject>> = mediaArray.map { medium ->
                medium.optJSONArray("tracks")?.asList() ?: emptyList()
            }
            if (media.sumOf { it.size } == 0) return@mapNotNull null
            ReleaseCandidate(release.optString("date", null), media)
        }
        if (candidates.isEmpty()) return emptyList()

        val singleMedium = candidates.filter { it.media.size == 1 }
        val pool = singleMedium.ifEmpty { candidates }

        val countFrequency = pool.groupingBy { it.totalTracks }.eachCount()
        val maxFrequency = countFrequency.values.maxOrNull() ?: return emptyList()
        val consensusCount = countFrequency.filterValues { it == maxFrequency }.keys.min()

        val best = pool.filter { it.totalTracks == consensusCount }
            .minByOrNull { it.date ?: "9999" }
            ?: return emptyList()

        return best.media.flatMapIndexed { discIndex, mediumTracks ->
            mediumTracks.map { track ->
                val recording = track.optJSONObject("recording")
                DiscographyTrack(
                    title = track.optString("title").ifBlank { recording?.optString("title").orEmpty() },
                    discNumber = discIndex + 1,
                    trackNumber = track.optInt("position", 0),
                    durationMs = track.optLong("length", 0L).takeIf { it > 0L }
                        ?: recording?.optLong("length", 0L)?.takeIf { it > 0L }
                )
            }
        }
    }

    // --- Tiendas de descarga de una canción ---

    /**
     * Dónde se puede conseguir el archivo de una canción, según los enlaces curados por la
     * comunidad de MusicBrainz: relaciones URL de tipo `purchase for download` y `download for
     * free`, tanto en la propia grabación (su sección "Relationships") como en cada lanzamiento que
     * la contiene (sus "External links"). Es lo que alimenta el botón de tiendas del buscador de
     * fragmentos (ver [com.untar.ultimusic.ui.preview.StoreLinksDialogFragment]).
     *
     * Las tiendas no tienen API pública de búsqueda, y rastrear sus webs es frágil y va contra los
     * términos de varias; estos enlaces, en cambio, son datos abiertos (CC0) y apuntan directamente
     * a la página de la grabación o del lanzamiento. A cambio la cobertura es la que haya añadido la
     * comunidad: una canción a la venta puede salir sin ningún enlace.
     *
     * [isrc] identifica la grabación exacta y es lo primero que se prueba; sin él (o si MusicBrainz
     * no lo conoce) se busca por título+artista. Son entre 3 y 5 peticiones espaciadas por [paced]
     * (unos 4-5 s), así que quien llame debe enseñar un indicador de carga.
     *
     * Una fila por tienda (la primera que aparezca, con las de la grabación por delante de las de
     * los lanzamientos). Se descartan las de Apple: no se puede comprar ni descargar desde Android
     * ni desde su web. Vacía —sin lanzar— si no hay ninguna; los fallos de red o del servicio se
     * dejan subir para distinguir "no hay tiendas" de "no se ha podido comprobar".
     */
    suspend fun downloadStores(title: String, artist: String, isrc: String?): List<StoreLink> =
        withContext(Dispatchers.IO) {
            val links = LinkedHashMap<String, StoreLink>()
            for (recordingId in recordingIdsFor(title, artist, isrc)) {
                val recording = JSONObject(paced("$BASE_URL/recording/$recordingId?inc=url-rels&fmt=json"))
                collectStoreLinks(recording.optJSONArray("relations"), links)

                // Una sola petición trae los enlaces de TODAS las ediciones de esta grabación.
                val releasesUrl = "$BASE_URL/release?recording=$recordingId&inc=url-rels&fmt=json&limit=100"
                val releases = JSONObject(paced(releasesUrl)).optJSONArray("releases") ?: JSONArray()
                releases.asList().forEach { collectStoreLinks(it.optJSONArray("relations"), links) }
            }
            links.values.toList()
        }

    /**
     * Las grabaciones de MusicBrainz que corresponden a la canción, como mucho [MAX_RECORDINGS].
     * Primero por ISRC; si no lo hay, MusicBrainz no lo conoce o la petición falla por otro motivo
     * que un rate limit, por título+artista quedándose solo con los candidatos de título igual
     * (normalizado, ver [TextMatch]) y puntuación alta, y sin "live" en la desambiguación (un
     * directo trae otros enlaces, los de su concierto, no los de la canción de estudio).
     */
    private suspend fun recordingIdsFor(title: String, artist: String, isrc: String?): List<String> {
        if (!isrc.isNullOrBlank()) {
            val fromIsrc = try {
                JSONObject(paced("$BASE_URL/isrc/${URLEncoder.encode(isrc, "UTF-8")}?fmt=json"))
                    .optJSONArray("recordings")?.asList().orEmpty()
                    .map { it.optString("id") }
                    .filter { it.isNotBlank() }
            } catch (limited: ApiRateLimitException) {
                throw limited
            } catch (unknown: IOException) {
                // 404: MusicBrainz no tiene ese ISRC. Se sigue por el buscador de texto.
                emptyList()
            }
            if (fromIsrc.isNotEmpty()) return fromIsrc.take(MAX_RECORDINGS)
        }
        if (title.isBlank()) return emptyList()

        val query = buildString {
            append("recording:").append(luceneQuote(title))
            if (artist.isNotBlank()) append(" AND artist:").append(luceneQuote(artist))
        }
        val url = "$BASE_URL/recording/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=5"
        val candidates = JSONObject(paced(url)).optJSONArray("recordings")?.asList().orEmpty()
        return candidates
            .filter {
                it.optInt("score", 0) >= HIGH_SCORE_THRESHOLD &&
                    TextMatch.looselyEqual(title, it.optString("title")) &&
                    !it.optString("disambiguation").contains("live", ignoreCase = true)
            }
            .map { it.optString("id") }
            .filter { it.isNotBlank() }
            .take(MAX_RECORDINGS)
    }

    /** Añade a [into] los enlaces de tienda de [relations] (el array `relations` de una grabación o
     * de un lanzamiento), una vez por tienda: si ya hay uno de esa tienda no se pisa. */
    private fun collectStoreLinks(relations: JSONArray?, into: MutableMap<String, StoreLink>) {
        for (relation in relations?.asList().orEmpty()) {
            val type = relation.optString("type")
            if (type !in STORE_RELATION_TYPES) continue
            val url = relation.optJSONObject("url")?.optString("resource")
                ?.takeIf { it.startsWith("http") } ?: continue
            val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: continue
            if (host == "apple.com" || host.endsWith(".apple.com")) continue
            val name = storeName(host)
            into.putIfAbsent(name, StoreLink(name = name, url = url, free = type == FREE_DOWNLOAD_TYPE))
        }
    }

    /** Nombre legible de la tienda a partir del dominio; los que no se reconocen se enseñan tal cual. */
    private fun storeName(host: String): String = when {
        host == "bandcamp.com" || host.endsWith(".bandcamp.com") -> "Bandcamp"
        host.contains("amazon.") -> "Amazon"
        host == "qobuz.com" || host.endsWith(".qobuz.com") -> "Qobuz"
        host == "newgrounds.com" || host.endsWith(".newgrounds.com") -> "Newgrounds"
        else -> host
    }

    // --- Peticiones HTTP ---

    /**
     * GET espaciado al menos [MIN_INTERVAL_MS] desde la petición anterior a este mismo servicio (ver
     * la documentación de la clase): MusicBrainz no reacciona con un 429 reintentable como iTunes o
     * Genius, así que aquí el freno tiene que ir ANTES de mandar la petición, no solo después de un
     * fallo.
     */
    private suspend fun paced(url: String): String {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)
        if (wait > 0) delay(wait)
        lastRequestAtMs = System.currentTimeMillis()
        return HttpJson.get(url = url, service = "MusicBrainz", guard = rateLimitGuard, userAgent = USER_AGENT)
    }

    private fun JSONArray.asList(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** Envuelve [value] entre comillas para una búsqueda de frase exacta en Lucene (el lenguaje de
     *  consulta de MusicBrainz), escapando las comillas y barras invertidas que pudiera traer. */
    private fun luceneQuote(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
