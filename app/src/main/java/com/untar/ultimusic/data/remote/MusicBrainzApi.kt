package com.untar.ultimusic.data.remote

import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente de MusicBrainz (la base de datos musical libre y colaborativa, "la Wikipedia de la
 * música") y de su hermana Cover Art Archive, para el autorrelleno de metadatos del editor (ver
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment]).
 *
 * Es un `object` (un singleton de Kotlin: una única instancia compartida por toda la app, sin
 * necesidad de pedirla ni de guardarla en ningún sitio) en vez de una clase con constructor,
 * porque no necesita ningún `Context` de Android ni guarda ningún estado entre peticiones — cada
 * función hace la suya y devuelve, sin recordar nada de la anterior.
 *
 * Se usa `HttpURLConnection` (viene con el propio Android) en vez de una librería de red como
 * OkHttp o Retrofit: la app entera solo hace dos tipos de llamada de red (esta y la miniatura de
 * YouTube, ver [com.untar.ultimusic.data.LibraryRepository.downloadVideoThumbnail]), así que no
 * compensa la dependencia extra. Por el mismo motivo se parsean las respuestas a mano con
 * `org.json` (también viene con Android) en vez de con Gson o Moshi: son unos pocos campos de unos
 * pocos tipos de respuesta, no vale la pena generar clases para ello.
 *
 * MusicBrainz exige, para poder usar su API pública, mandar un `User-Agent` que identifique la
 * aplicación y dé un contacto real (así avisan si algo va mal en vez de bloquear sin más) — de ahí
 * [USER_AGENT]. También limita a 1 petición por segundo sin registrarse: la búsqueda inicial y los
 * géneros de un candidato (ver [fetchGenres]) siguen siendo un disparo suelto por gesto del
 * usuario, así que no necesitan ningún control de ritmo propio. El scroll infinito de
 * [MetadataSuggestionsViewModel] sí puede encadenar varias páginas seguidas — ahí el respiro entre
 * peticiones vive en el propio ViewModel, no aquí.
 */
object MusicBrainzApi {

    private const val BASE_URL = "https://musicbrainz.org/ws/2/"
    private const val COVER_ART_BASE = "https://coverartarchive.org/"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Cover Art Archive sirve la miniatura de 250px con este mismo sufijo; quitándolo se obtiene
     * la imagen original en máxima resolución, la que interesa al guardarla de verdad (ver uso en
     * los editores). [MetadataSuggestion.coverUrl] se queda en 250px porque ahí solo hace falta
     * pintar un thumbnail en la lista de sugerencias. */
    fun fullResCoverUrl(coverUrl: String): String = coverUrl.removeSuffix("-250")

    /** Cuántos resultados se piden a MusicBrainz por página. Es el mismo tamaño de página que usa
     * la web de MusicBrainz (se puede comprobar dividiendo el total de resultados entre el número
     * de páginas que enseña su paginador), para que "cargar más" avance por el mismo corte que si
     * el usuario paginase a mano en el navegador. */
    private const val PAGE_SIZE = 25

    private const val TIMEOUT_MS = 8_000

    /**
     * Una página de candidatos ya filtrados y deduplicados (ver [searchSongs]) más lo necesario
     * para pedir la siguiente: [nextOffset] es el `offset` que hay que mandar en la próxima
     * llamada, y [hasMore] dice si a MusicBrainz le queda algo más después de esta página (según
     * su propio `count` de resultados totales — ojo, ese `count` es de ANTES de filtrar, así que
     * puede quedar página siguiente aunque esta se haya quedado sin candidatos, ver
     * [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel.fetchAccumulating]).
     */
    data class SearchPage(
        val suggestions: List<MetadataSuggestion>,
        val nextOffset: Int,
        val hasMore: Boolean
    )

    /**
     * Publicaciones que casi seguro NO son "la canción/el álbum en sí" sino un producto derivado
     * (una entrevista, un audiolibro...): se descartan siempre, nunca son lo que el usuario busca
     * al rellenar metadatos. En minúsculas porque MusicBrainz no es constante con las mayúsculas
     * en `secondary-types`.
     */
    private val IGNORED_SECONDARY_TYPES = setOf(
        "interview", "audiobook", "spokenword", "dj-mix", "mixtape/street"
    )

    /** Estados de publicación que SÍ interesan (nada de bootlegs/promos/retiradas). Se comprueba a
     * mano en Kotlin, release a release, en vez de metiéndolo en la consulta Lucene: ver la nota
     * en [searchSongs] sobre por qué mezclar texto libre con una cláusula de campo (`AND
     * status:...`) rompía el orden de relevancia de MusicBrainz. En minúsculas porque MusicBrainz
     * no es constante con las mayúsculas en `status`. */
    private val ALLOWED_STATUSES = setOf("official", "pseudo-release")

    /** MusicBrainz devuelve, en cada resultado, un `score` de 0 a 100 — NO es una nota de calidad
     * absoluta, es la relevancia de Lucene para esa consulta normalizada de forma que el MEJOR
     * resultado de esa búsqueda siempre vale 100 y el resto se escalan proporcionalmente a lo cerca
     * que quedó su puntuación real de la del primero. Por eso no vale comparar el score de una
     * búsqueda con el de otra distinta, pero dentro de la MISMA sí: uno muy por debajo del mejor
     * comparte mucho menos texto con lo buscado, así que casi siempre es ruido sin relación real
     * (p. ej. una canción cualquiera que solo comparte una de las dos palabras buscadas). Se
     * descarta la grabación/álbum entero si no llega, antes de mirar sus releases siquiera. */
    private const val MIN_SCORE = 50

    /**
     * Busca canciones por título y artista. Cada elemento de la lista es UNA publicación (álbum,
     * single...) de la canción encontrada: la misma grabación puede aparecer varias veces, una
     * por cada publicación distinta en la que salió (ver la documentación de [MetadataSuggestion]
     * sobre por qué esto es justo lo que hace falta para distinguir el single del álbum).
     *
     * [offset] es la página a pedir (0 = la primera): ver [SearchPage].
     *
     * La consulta es texto libre, SIN restringir a ningún campo — a propósito, para calcar lo que
     * hace el buscador de la propia web de MusicBrainz (que tampoco restringe campo). Antes se
     * probó con campos concretos (`recording:`, `alias:`, `comment:`...): encuentra menos, porque
     * una traducción/transliteración del título puede vivir en el título canónico, en un alias, en
     * la desambiguación entre paréntesis o en el título de una pista suelta, y no hay forma de
     * enumerar de antemano en cuál de todos esos sitios va a estar. El texto libre los mira todos
     * a la vez sin que haga falta saberlo.
     *
     * A cambio, el texto libre trae MUCHO ruido — se comprobó a mano que el candidato bueno puede
     * quedar sepultado bajo un centenar de resultados sueltos sin relación real. Por eso, a
     * diferencia de un espejo exacto de la web, aquí SÍ se filtra (solo publicaciones oficiales o
     * Pseudo-Release, nada de bootlegs/promos; fuera entrevistas/audiolibros/etc.; una fila por
     * grabación+release-group, no por cada edición suelta) y se reordena cada página para subir
     * arriba del todo los candidatos que de verdad coinciden con lo buscado, con dos señales (ver
     * [matchesAnyArtist]/[matchesTitle]):
     * - El artista acreditado (o CUALQUIERA de sus alias — nombre de búsqueda, transliteración,
     *   nombre legal...) coincide con alguno de los artistas separados por comas que se buscan. Sin
     *   mirar los alias, un artista acreditado con un nombre distinto al que puso el usuario (p. ej.
     *   "ピノキオピー" en vez de "PinocchioP", la misma persona) nunca puntuaría, aunque sea
     *   exactamente a quien se busca.
     * - El título de la RELEASE (no el de la grabación) coincide con el título buscado: una
     *   traducción/transliteración puede vivir ahí — el título "oficial" de una edición concreta —
     *   sin que la grabación en sí se llame así.
     * MusicBrainz no puntúa por ninguna de las dos cosas (solo por relevancia de texto libre), así
     * que sin esto el candidato bueno podría seguir apareciendo tarde dentro de cada página aunque
     * ya no compita con bootlegs ni entrevistas.
     */
    suspend fun searchSongs(title: String, artist: String, offset: Int = 0): SearchPage =
        withContext(Dispatchers.IO) {
            // Solo el título va en la consulta a MusicBrainz; el artista se usa nada más para
            // reordenar (ver matchesAnyArtist más abajo), no para filtrar qué trae la API. Motivo:
            // el texto libre exige que TODAS las palabras aparezcan en algún campo indexado de la
            // publicación, y si el artista está acreditado con un alias distinto al que puso el
            // usuario (p. ej. "ピノキオピー" en vez de "PinocchioP"), el nombre buscado puede no
            // aparecer en ningún sitio de esa publicación — MusicBrainz la descartaría antes de que
            // el reordenado por alias llegue a verla siquiera.
            //
            // TAMPOCO se mete el filtro de status (official/pseudo-release) en la consulta, aunque
            // sería lo más directo: comprobado con curl que un `AND (status:official OR
            // status:"pseudo-release")` pegado al texto libre le CAMBIA el cálculo de relevancia a
            // MusicBrainz (deja de puntuar por lo bien que casa el texto) y puede hundir al
            // candidato bueno por debajo de resultados sueltos sin relación real — pasó exactamente
            // así con "Rejected Diva": primero de 14617 resultados en texto libre puro, fuera del
            // top 5 en cuanto se le añade el AND de status. Por eso el filtro de estado se aplica a
            // mano más abajo, release a release (ver [ALLOWED_STATUSES]), después de recibir la
            // respuesta — así la consulta que ve MusicBrainz es texto libre puro, con el mismo
            // orden de relevancia que en el buscador de la web.
            //
            // Solo se escapan barra invertida y comillas (lo justo para que un título con comillas
            // dentro no rompa la consulta): todo lo demás se manda tal cual, igual que lo
            // escribiría el usuario en el buscador de la web.
            val query = escapeLucene(title).trim()
            val json = httpGet(searchUrl("recording", query, offset))
            val recordings = json.optJSONArray("recordings") ?: JSONArray()
            val searchedArtists = splitArtists(artist)

            // Una misma publicación (mismo release-group) puede colarse dos veces: bajo
            // grabaciones distintas (una versión de estudio y un directo pueden compartir álbum) o,
            // dentro de la misma grabación, una vez por cada país en que se vendió. Este set
            // recuerda qué release-group ya se ha usado para no repetir la misma edición.
            val usedReleaseGroups = mutableSetOf<String>()
            val ranked = mutableListOf<RankedSuggestion>()

            for (i in 0 until recordings.length()) {
                val recording = recordings.getJSONObject(i)
                if (recording.optInt("score", 0) < MIN_SCORE) continue
                val releases = recording.optJSONArray("releases")
                if (releases == null || releases.length() == 0) continue // sin publicar: nada que ofrecer

                val recordingTitle = recording.optString("title")
                val artistCredits = recording.optJSONArray("artist-credit")
                val recordingArtist = joinArtistCredit(artistCredits)
                val recordingId = recording.optString("id")
                val fallbackYear = parseYear(recording.optString("first-release-date", null))
                // Es de la grabación entera, no cambia release a release: se calcula una vez.
                val artistMatches = matchesAnyArtist(searchedArtists, artistCreditMatchTexts(artistCredits))

                // De las publicaciones de ESTA grabación, una por release-group: la de fecha más
                // temprana si varias la tienen, o la primera que aparezca si ninguna la tiene.
                val bestReleaseByGroup = LinkedHashMap<String, JSONObject>()
                for (j in 0 until releases.length()) {
                    val release = releases.getJSONObject(j)
                    if (release.optString("status").lowercase() !in ALLOWED_STATUSES) continue
                    val releaseGroup = release.optJSONObject("release-group") ?: continue
                    val secondaryTypes = toStringList(releaseGroup.optJSONArray("secondary-types"))
                    if (secondaryTypes.any { it.lowercase() in IGNORED_SECONDARY_TYPES }) continue

                    val groupId = releaseGroup.optString("id")
                    if (groupId.isEmpty() || groupId in usedReleaseGroups) continue

                    val current = bestReleaseByGroup[groupId]
                    val currentDate = current?.optString("date").orEmpty()
                    val candidateDate = release.optString("date").orEmpty()
                    // Una fecha ausente/vacía NUNCA gana ni desplaza a una ya encontrada: comparar
                    // cadenas vacías como si fueran "la más antigua" la pondría delante por error.
                    val candidateWins = current == null ||
                        (candidateDate.isNotEmpty() && (currentDate.isEmpty() || candidateDate < currentDate))
                    if (candidateWins) bestReleaseByGroup[groupId] = release
                }

                for ((groupId, release) in bestReleaseByGroup) {
                    usedReleaseGroups += groupId
                    val releaseGroup = release.getJSONObject("release-group")
                    val firstMedium = release.optJSONArray("media")?.optJSONObject(0)
                    val firstTrack = firstMedium?.optJSONArray("track")?.optJSONObject(0)
                    val titleMatches = matchesTitle(title, release.optString("title"))

                    val suggestion = MetadataSuggestion(
                        kind = SuggestionKind.SONG,
                        title = recordingTitle,
                        artist = recordingArtist,
                        albumTitle = releaseGroup.optString("title", null) ?: release.optString("title"),
                        year = parseYear(release.optString("date", null)) ?: fallbackYear,
                        country = release.optString("country", null),
                        coverUrl = "${COVER_ART_BASE}release/${release.optString("id")}/front-250",
                        genreEntityId = recordingId,
                        trackNumber = firstTrack?.optString("number")?.toIntOrNull(),
                        discNumber = firstMedium?.optInt("position", -1)?.takeIf { it > 0 },
                        primaryType = releaseGroup.optString("primary-type", null),
                        secondaryTypes = toStringList(releaseGroup.optJSONArray("secondary-types"))
                    )
                    ranked += RankedSuggestion(suggestion, matchRank(artistMatches, titleMatches))
                }
            }

            val total = json.optInt("count", 0)
            SearchPage(
                suggestions = ranked.sortedBy { it.rank }.map { it.suggestion },
                nextOffset = offset + recordings.length(),
                hasMore = offset + recordings.length() < total
            )
        }

    /**
     * Busca álbumes por título y artista. A diferencia de [searchSongs], cada resultado de
     * MusicBrainz (un *release-group*) YA es la unidad correcta: no hace falta deduplicar por
     * país ni mirar publicaciones sueltas, porque un release-group agrupa todas las ediciones de
     * un mismo álbum bajo un único id.
     *
     * [offset] es la página a pedir (0 = la primera): ver [SearchPage]. Texto libre, filtrado
     * (fuera entrevistas/audiolibros/etc.) y reordenado por coincidencia de artista, por los
     * mismos motivos que en [searchSongs].
     */
    suspend fun searchAlbums(album: String, artist: String, offset: Int = 0): SearchPage =
        withContext(Dispatchers.IO) {
            val query = "${escapeLucene(album)} ${escapeLucene(artist)}".trim()
            val json = httpGet(searchUrl("release-group", query, offset))
            val groups = json.optJSONArray("release-groups") ?: JSONArray()
            val searchedArtists = splitArtists(artist)

            val ranked = mutableListOf<RankedSuggestion>()
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                if (group.optInt("score", 0) < MIN_SCORE) continue
                val secondaryTypes = toStringList(group.optJSONArray("secondary-types"))
                if (secondaryTypes.any { it.lowercase() in IGNORED_SECONDARY_TYPES }) continue

                val id = group.optString("id")
                if (id.isEmpty()) continue

                val artistCredits = group.optJSONArray("artist-credit")
                val suggestion = MetadataSuggestion(
                    kind = SuggestionKind.ALBUM,
                    title = group.optString("title"),
                    artist = joinArtistCredit(artistCredits),
                    albumTitle = null,
                    year = parseYear(group.optString("first-release-date", null)),
                    // Ningún release concreto elegido para un release-group (ver searchAlbums):
                    // no hay un único país que ponerle sin inventárselo.
                    country = null,
                    coverUrl = "${COVER_ART_BASE}release-group/$id/front-250",
                    genreEntityId = id,
                    trackNumber = null,
                    discNumber = null,
                    primaryType = group.optString("primary-type", null),
                    secondaryTypes = secondaryTypes
                )
                val artistMatches = matchesAnyArtist(searchedArtists, artistCreditMatchTexts(artistCredits))
                ranked += RankedSuggestion(suggestion, matchRank(artistMatches, titleMatches = false))
            }

            val total = json.optInt("count", 0)
            SearchPage(
                suggestions = ranked.sortedBy { it.rank }.map { it.suggestion },
                nextOffset = offset + groups.length(),
                hasMore = offset + groups.length() < total
            )
        }

    /**
     * Los géneros de MusicBrainz son "folksonomía": etiquetas que cualquiera puede votar, no un
     * campo fijo de la canción o el álbum. No vienen en la búsqueda — hay que pedirlos aparte, por
     * eso esta función es independiente y solo se llama para el candidato que el usuario elige,
     * nunca para los diez de la lista (ver la nota sobre el ritmo de peticiones más arriba).
     */
    suspend fun fetchGenres(kind: SuggestionKind, mbId: String): List<String> =
        withContext(Dispatchers.IO) {
            val entity = if (kind == SuggestionKind.SONG) "recording" else "release-group"
            val json = httpGet("$BASE_URL$entity/$mbId?inc=genres&fmt=json")
            val genres = json.optJSONArray("genres") ?: return@withContext emptyList()
            (0 until genres.length())
                .map { genres.getJSONObject(it) }
                .sortedByDescending { it.optInt("count") }
                .take(3)
                .mapNotNull { it.optString("name", null) }
        }

    // --- Peticiones HTTP ---

    private fun searchUrl(entity: String, query: String, offset: Int): String =
        "$BASE_URL$entity/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=$PAGE_SIZE&offset=$offset"

    /** GET genérico: manda el `User-Agent` que exige MusicBrainz, y convierte cualquier respuesta
     * que no sea "200 OK" en una excepción (quien llame la captura con `runCatching`, ver
     * [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel]). */
    private fun httpGet(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("MusicBrainz respondió ${connection.responseCode} para $url")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    // --- Ayudas de parseo ---

    /** Escapa lo mínimo imprescindible para que el texto no rompa la sintaxis de Lucene (el motor
     * de búsqueda de MusicBrainz): una barra invertida o unas comillas sueltas en el título
     * cortarían la query a medias. El resto de caracteres especiales de Lucene (paréntesis,
     * asterisco...) se dejan tal cual a propósito — son justo los que [searchSongs]/[searchAlbums]
     * quieren que el texto libre trate con la misma flexibilidad que el buscador de la web. */
    private fun escapeLucene(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Las fechas de MusicBrainz pueden venir completas ("1975-11-21"), solo con año ("1975") o
     * directamente ausentes: basta el año, así que se toman los 4 primeros caracteres. */
    private fun parseYear(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /** Un "artist-credit" es cómo MusicBrainz representa colaboraciones ("Fulanito feat.
     * Menganita"): una lista de artistas con la palabra de enlace ("feat.", "&"...) ya pegada a
     * cada uno, así que basta con concatenarlos en orden. */
    private fun joinArtistCredit(credits: JSONArray?): String {
        if (credits == null) return ""
        val builder = StringBuilder()
        for (i in 0 until credits.length()) {
            val credit = credits.getJSONObject(i)
            builder.append(credit.optString("name"))
            builder.append(credit.optString("joinphrase"))
        }
        return builder.toString()
    }

    private fun toStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    // --- Reordenado por coincidencia (ver searchSongs/searchAlbums) ---

    /** Candidato con su puesto en la cola de reordenado (ver [matchRank]) — solo vive dentro de
     * una página, nunca sale de aquí: [SearchPage] ya entrega la lista pura, sin este número. */
    private data class RankedSuggestion(val suggestion: MetadataSuggestion, val rank: Int)

    /**
     * 0 (el mejor puesto) si coinciden artista Y título de release, 1 si coincide solo el
     * artista, 2 si coincide solo el título, 3 si no coincide nada — `sortedBy` (estable) respeta
     * el orden de MusicBrainz dentro de cada grupo, así que esto solo reordena "en bloques", nunca
     * revuelve candidatos igual de buenos entre sí.
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

    /** Quita mayúsculas, espacios y signos de puntuación antes de comparar dos textos: así
     * "PinocchioP" y "Pinocchio-P" (o "pinocchio p") se ven iguales. `isLetterOrDigit` funciona
     * con cualquier alfabeto (también japonés), así que sirve igual para nombres occidentales que
     * para acreditados en kanji/kana. */
    private fun normalizeForMatch(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Todos los textos con los que MusicBrainz podría identificar a los artistas acreditados de
     * una grabación/álbum: el nombre tal como sale en el crédito, el nombre "oficial" del
     * artista, su sort-name, y el nombre/sort-name de CADA alias suyo (traducciones,
     * transliteraciones, nombres de búsqueda, nombre legal...). Sin mirar los alias, un artista
     * acreditado con un nombre distinto al que puso el usuario en los metadatos (p. ej.
     * "ピノキオピー" en vez de "PinocchioP", la misma persona) nunca coincidiría.
     */
    private fun artistCreditMatchTexts(credits: JSONArray?): List<String> {
        if (credits == null) return emptyList()
        val texts = mutableListOf<String>()
        for (i in 0 until credits.length()) {
            val credit = credits.getJSONObject(i)
            texts += credit.optString("name")
            val artist = credit.optJSONObject("artist") ?: continue
            texts += artist.optString("name")
            texts += artist.optString("sort-name")
            val aliases = artist.optJSONArray("aliases") ?: continue
            for (j in 0 until aliases.length()) {
                val alias = aliases.getJSONObject(j)
                texts += alias.optString("name")
                texts += alias.optString("sort-name")
            }
        }
        return texts.filter { it.isNotBlank() }
    }

    /** true si ALGUNO de [searchedArtists] coincide (normalizado, ver [normalizeForMatch]) con
     * ALGUNO de [creditTexts] (ver [artistCreditMatchTexts]). Sin artistas buscados (el usuario
     * buscó solo por título), no discrimina: cuenta como coincidencia para no penalizar a nadie. */
    private fun matchesAnyArtist(searchedArtists: List<String>, creditTexts: List<String>): Boolean {
        if (searchedArtists.isEmpty()) return true
        val normalizedCredits = creditTexts.map(::normalizeForMatch)
        return searchedArtists.any { searched ->
            val normalizedSearched = normalizeForMatch(searched)
            normalizedSearched.isNotEmpty() && normalizedCredits.any { it.contains(normalizedSearched) }
        }
    }

    /** true si el título de la RELEASE (p. ej. "(Not) A Devil", la edición inglesa de una canción
     * japonesa) coincide, normalizado, con el título buscado — una traducción/transliteración
     * puede vivir solo ahí, sin que la propia grabación se llame así (ver [searchSongs]). */
    private fun matchesTitle(searchedTitle: String, candidateTitle: String): Boolean {
        if (searchedTitle.isBlank() || candidateTitle.isBlank()) return false
        val normalizedSearched = normalizeForMatch(searchedTitle)
        val normalizedCandidate = normalizeForMatch(candidateTitle)
        return normalizedSearched.isNotEmpty() &&
            (normalizedCandidate.contains(normalizedSearched) || normalizedSearched.contains(normalizedCandidate))
    }
}
