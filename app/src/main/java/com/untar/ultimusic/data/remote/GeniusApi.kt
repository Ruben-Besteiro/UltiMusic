package com.untar.ultimusic.data.remote

import com.untar.ultimusic.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente de la API de Genius, para **enriquecer** el candidato que el usuario elige en el
 * autorrelleno de metadatos (ver [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment]).
 *
 * ## Qué pinta aquí, si ya está iTunes
 *
 * No compite con [ItunesApi]: cubren cosas distintas y casi no se solapan.
 *
 * La lista de candidatos la manda **siempre** iTunes, porque su unidad es "una fila por
 * publicación" y eso es lo que deja elegir el single en vez del álbum (ver [ItunesApi]). Genius no
 * podría producir esas filas: da un único álbum por canción, sin ediciones que comparar. Pero sabe
 * tres cosas que iTunes no sabe, y que además son campos que UltiMusic modela y que hasta ahora no
 * rellenaba nadie:
 * - **Productores** (`producer_artists`), que aquí tienen su propio campo en el editor de metadatos.
 * - **La canción original de un remix** (`song_relationships` → `remix_of`), que es exactamente lo
 *   que guardan los campos `og*` (ver [com.untar.ultimusic.model.Song]).
 * - **El enlace del videoclip** (`media`), que si no hay que buscar a mano en el iPod.
 *
 * Estos tres solo se piden del candidato que el usuario ELIGE (ver [detailsFor]): son dos peticiones
 * más por candidato (buscar y luego pedir la ficha completa) y no tendría sentido gastarlas en
 * veinticinco filas que a lo mejor no se llegan ni a mirar.
 *
 * La **carátula de la canción** (`song_art_image_url`) es distinta: sale ya en la propia respuesta de
 * búsqueda, sin ficha completa de por medio, así que [songArtFor] la pide fila a fila según se
 * enseñan en la lista (ver [com.untar.ultimusic.ui.editor.MetadataSuggestionsAdapter]) — a diferencia
 * de la de iTunes, que es la del disco entero, esta es la de la grabación en concreto, y es lo que se
 * ve en juego en el bug que motivó esto: la fila enseñaba la portada del álbum aunque la canción
 * tuviera carátula propia, y solo se corregía al elegirla. Con el token por usuario (ver
 * [GeniusTokenStore]) esa petición de más por fila ya no compite por una cuota compartida.
 *
 * ## Es opcional a propósito
 *
 * A diferencia de iTunes y lrclib.net, la API de Genius exige un *client access token*, y lo pone
 * **cada usuario** desde el diálogo de [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment]
 * (ver [GeniusTokenStore] sobre por qué no viaja uno dentro del APK). Mientras no lo haya,
 * [isConfigured] queda en false, no se llama a Genius y el autorrelleno sigue funcionando
 * exactamente igual, solo que sin estos extras. Por el mismo motivo, casi todo
 * fallo aquí (sin red, token caducado, la canción no está en Genius, la que hay no es la misma) se
 * traduce en devolver null y no rellenar nada: el enriquecimiento nunca puede empeorar lo que iTunes
 * ya había dado, solo añadirle cosas.
 *
 * La única excepción es [ApiRateLimitException], que [detailsFor] sí propaga: ver allí el porqué.
 *
 * ## Sobre las letras
 *
 * Genius NO sirve letras por su API (solo el enlace a su web), así que ese campo se sigue pidiendo
 * a [LrcLibApi], que además las da sincronizadas — algo que Genius no ofrece de ninguna forma.
 */
object GeniusApi {

    private const val BASE_URL = "https://api.genius.com"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Cuántos resultados de búsqueda se miran buscando uno que case de verdad. Genius ordena por
     * popularidad, así que si la canción buscada no está entre los primeros es que no la tiene. */
    private const val MAX_HITS_CHECKED = 10

    /** Más corto que el de las búsquedas: en [validateToken] hay alguien mirando la pantalla y
     *  esperando un sí o un no, así que vale más contestar "no se ha podido comprobar" que tenerlo
     *  ocho segundos delante de un spinner. */
    private const val VALIDATION_TIMEOUT_MS = 5_000

    /** false si no se ha configurado ningún token (ver el javadoc de la clase): entonces no se
     * llama a Genius siquiera. Se comprueba antes de cada uso para que la app siga siendo usable
     * sin token, no como un error que haya que capturar. */
    val isConfigured: Boolean
        get() = GeniusTokenStore.token.isNotBlank()

    /**
     * Comprueba contra Genius si [candidate] es un token válido, para poder decírselo al usuario en
     * el momento de pegarlo (ver [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment]) y no tres
     * pantallas después en forma de "el autorrelleno no trae productores".
     *
     * **No pasa por [HttpJson] a propósito**, y esto es importante: la caché de [ApiCache] tiene por
     * clave la URL, que no incluye el token. Validando por ahí, un token inválido podría dar por
     * bueno el cuerpo cacheado de una petición que hizo otro token válido, y la comprobación diría
     * que sí a cualquier cosa. Aquí hace falta una petición de verdad, y además sin tocar el
     * [rateLimitGuard]: un token recién pegado no tiene por qué pagar el castigo del anterior.
     */
    suspend fun validateToken(candidate: String): Boolean = withContext(Dispatchers.IO) {
        if (candidate.isBlank()) return@withContext false
        runCatching {
            val connection = (URL("$BASE_URL/search?q=test").openConnection() as HttpURLConnection)
                .apply {
                    setRequestProperty("Authorization", "Bearer ${candidate.trim()}")
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = VALIDATION_TIMEOUT_MS
                    readTimeout = VALIDATION_TIMEOUT_MS
                }
            try {
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Lo que Genius aporta sobre un candidato ya elegido. Todos los campos son opcionales: Genius
     * puede conocer la canción pero no tener productores anotados, o no ser un remix, o no tener
     * vídeo enlazado.
     */
    data class SongDetails(
        /** Artistas acreditados, ya SEPARADOS uno por uno: el principal más los colaboradores.
         *
         * Es la ventaja decisiva de Genius sobre iTunes en este campo. iTunes acredita una única
         * cadena con todos pegados ("The Weeknd & ROSALÍA"), que no se puede partir sin romper a
         * los grupos que llevan "&" en su propio nombre (Simon & Garfunkel, Earth, Wind & Fire);
         * Genius los da en campos distintos (`primary_artist` y `featured_artists`), así que no hay
         * nada que adivinar. */
        val artists: List<String>,
        val producers: List<String>,
        /** Carátula propia de la canción, no la del álbum. */
        val songArtUrl: String?,
        /** Enlace de YouTube del videoclip, tal cual lo publica Genius en `media`. */
        val videoUrl: String?,
        /** Datos de la canción ORIGINAL si esta es un remix (ver los campos `og*` de
         * [com.untar.ultimusic.model.Song]). Van los cuatro juntos o no va ninguno. */
        val ogTitle: String?,
        val ogArtist: String?,
        val ogAlbum: String?,
        val ogYear: Int?
    )

    /**
     * Busca [title]/[artist] en Genius y devuelve lo que sepa de esa canción, o null si no hay
     * token, si falla la red, o si **no está seguro** de haber encontrado la misma canción.
     *
     * Esa última condición es la importante: rellenar el formulario con los productores de otra
     * canción parecida sería mucho peor que no rellenarlos, y el usuario no tendría forma de
     * notarlo. Por eso el candidato tiene que casar en título Y en artista (laxamente, ver
     * [TextMatch]) antes de aceptarlo; si el editor no traía artista, basta el título.
     *
     * **Salvo dos casos, de los que sí hay que enterarse** ([rethrowIfActionable]): que Genius nos
     * haya limitado, y que el token haya dejado de valer. Devolver null también ahí sería
     * indistinguible de "Genius no tiene esta canción", y el usuario se quedaría sin saber por qué el
     * autorrelleno dejó de traer productores de golpe — sin ninguna forma de arreglarlo, además, en el
     * segundo caso. Quien llama las captura y avisa (ver
     * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment]); el autorrelleno sigue
     * adelante igual con lo que dio iTunes, así que la promesa de "nunca empeora" se mantiene.
     */
    suspend fun detailsFor(title: String, artist: String): SongDetails? = withContext(Dispatchers.IO) {
        if (!isConfigured || title.isBlank()) return@withContext null
        runCatching {
            val songId = findSongId(title, artist) ?: return@runCatching null
            val song = httpGet("$BASE_URL/songs/$songId?text_format=plain")
                .optJSONObject("response")?.optJSONObject("song") ?: return@runCatching null

            val original = song.optJSONArray("song_relationships").firstRelated("remix_of")
            // El álbum y el año de la canción original solo están en su ficha completa, no en la
            // versión reducida que viene dentro de la relación: se pide aparte, UNA sola vez, y
            // solo si de verdad es un remix (lo más común es que no lo sea y no cueste nada).
            val originalFull = original?.let { fullSongOf(it) }

            SongDetails(
                artists = song.creditedArtists(),
                producers = song.optJSONArray("producer_artists").artistNames(),
                songArtUrl = song.optString("song_art_image_url", null)?.takeIf { it.isNotBlank() },
                videoUrl = youtubeUrl(song.optJSONArray("media")),
                ogTitle = original?.optString("title", null)?.takeIf { it.isNotBlank() },
                ogArtist = original?.creditedArtist(),
                ogAlbum = originalFull?.optJSONObject("album")?.optString("name", null)
                    ?.takeIf { it.isNotBlank() },
                ogYear = (originalFull ?: original)?.let { releaseYear(it) }
            )
        }.getOrElse { error -> rethrowIfActionable(error) }
    }

    /**
     * Qué hacer con un fallo de [detailsFor]: casi todos se tragan devolviendo null (el
     * enriquecimiento es un extra y nunca debe romper el autorrelleno), pero dos hay que dejarlos
     * subir porque **el usuario puede y debe hacer algo al respecto**:
     *
     * - [ApiRateLimitException]: se ha agotado la cuota; hay que decir cuánto esperar.
     * - [ApiUnauthorizedException] con el token inválido: su API Client ha dejado de valer y tiene
     *   que sacar otro. Además se **borra el token guardado** aquí mismo, para que el resto de la
     *   aplicación deje de intentarlo y el diálogo de configuración vuelva a ofrecerse.
     *
     * Un 401 por cabecera mal formada NO borra nada: ver [ApiUnauthorizedException] sobre por qué se
     * distinguen los dos casos.
     */
    private fun rethrowIfActionable(error: Throwable): Nothing? {
        if (error is ApiRateLimitException) throw error
        if (error is ApiUnauthorizedException && error.invalidToken) {
            GeniusTokenStore.clear()
            throw error
        }
        return null
    }

    // --- Búsqueda ---

    /**
     * Carátula propia de la canción (no la del álbum) para una fila de la lista de sugerencias, o
     * null si no hay token, si falla la red, si Genius no tiene esa canción o si no hay forma de
     * estar seguro de que el resultado es la misma canción (mismo criterio de [findMatchingResult]
     * que usa [detailsFor]).
     *
     * A diferencia de [detailsFor] no pide la ficha completa de la canción: `song_art_image_url` ya
     * viene en el propio resultado de búsqueda, así que esto es UNA petición por fila, no dos. Quien
     * llama (ver [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel.coverArtOverride]) la
     * cachea por título+artista para no repetirla si la fila se recicla al hacer scroll.
     *
     * Cualquier fallo, [ApiRateLimitException] incluido, se traduce en null: a diferencia de
     * [detailsFor] esto no lo elige el usuario, lo dispara el scroll solo, así que avisar de un rate
     * limit por cada fila que pase por pantalla sería ruido puro. Una vez castigado, [rateLimitGuard]
     * corta en seco sin tocar la red (ver [HttpJson]), así que las filas siguientes no cuestan nada.
     *
     * Un token que ha dejado de valer sí se apunta ([ApiUnauthorizedException]): se borra el guardado,
     * igual que en [detailsFor], para que [isConfigured] pase a false y las demás filas ni lo
     * intenten. Pero **no se avisa desde aquí** — el aviso sale al elegir un candidato, que es cuando
     * el usuario está pendiente de la pantalla y no mientras arrastra una lista.
     */
    suspend fun songArtFor(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured || title.isBlank()) return@withContext null
        runCatching {
            findMatchingResult(title, artist)?.optString("song_art_image_url", null)
                ?.takeIf { it.isNotBlank() }
        }.getOrElse { error ->
            if (error is ApiUnauthorizedException && error.invalidToken) GeniusTokenStore.clear()
            null
        }
    }

    /** Id del primer resultado que case en título y artista con lo buscado, o null si ninguno lo
     * hace (ver [detailsFor] sobre por qué se prefiere no rellenar a rellenar mal). */
    private fun findSongId(title: String, artist: String): Long? =
        findMatchingResult(title, artist)?.optLong("id", 0L)?.takeIf { it != 0L }

    /** El primer resultado de búsqueda que case en título y artista con lo buscado (laxamente, ver
     * [TextMatch]), o null si ninguno lo hace. Comparten esto [findSongId] (que solo necesita el id
     * para pedir la ficha completa) y [songArtFor] (que ya tiene todo lo que necesita aquí mismo). */
    private fun findMatchingResult(title: String, artist: String): JSONObject? {
        val term = listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val hits = httpGet("$BASE_URL/search?q=${URLEncoder.encode(term, "UTF-8")}")
            .optJSONObject("response")?.optJSONArray("hits") ?: return null

        for (i in 0 until minOf(hits.length(), MAX_HITS_CHECKED)) {
            val result = hits.getJSONObject(i).optJSONObject("result") ?: continue
            if (!TextMatch.looselyEqual(title, result.optString("title"))) continue
            if (artist.isNotBlank() && !TextMatch.looselyEqual(artist, result.creditedArtist().orEmpty())) continue
            return result
        }
        return null
    }

    /** Ficha completa de una canción que solo se conoce por su versión reducida (la que viene
     * dentro de `song_relationships`). Best-effort: si falla, quien llama se queda con lo que ya
     * tenía de la versión reducida. */
    private fun fullSongOf(lean: JSONObject): JSONObject? {
        val id = lean.optLong("id", 0L)
        if (id == 0L) return null
        return runCatching {
            httpGet("$BASE_URL/songs/$id?text_format=plain")
                .optJSONObject("response")?.optJSONObject("song")
        }.getOrNull()
    }

    // --- Ayudas de parseo ---

    /** Genius acredita a los artistas de dos formas según el endpoint: `artist_names` (ya con las
     * colaboraciones unidas, "Fulanito & Menganita") o el objeto `primary_artist` (solo el
     * principal). Se prefiere el primero por ser más completo. */
    private fun JSONObject.creditedArtist(): String? =
        optString("artist_names", null)?.takeIf { it.isNotBlank() }
            ?: optJSONObject("primary_artist")?.optString("name", null)?.takeIf { it.isNotBlank() }

    /** El artista principal y los colaboradores como nombres sueltos, sin repetir ninguno (Genius
     * a veces lista al principal también entre los `featured_artists`). Ver [SongDetails.artists]
     * sobre por qué esto vale más que la cadena ya pegada de `artist_names`. */
    private fun JSONObject.creditedArtists(): List<String> {
        val names = mutableListOf<String>()
        optJSONObject("primary_artist")?.optString("name", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { names += it }
        names += optJSONArray("featured_artists").artistNames()
        return names.distinctBy { TextMatch.normalize(it) }
    }

    private fun JSONArray?.artistNames(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optJSONObject(it)?.optString("name", null) }
            .filter { it.isNotBlank() }
    }

    /** La primera canción relacionada del tipo pedido (p. ej. `remix_of`), o null si esta canción
     * no tiene esa relación. Cada entrada del array agrupa un tipo de relación con TODAS las
     * canciones que la cumplen, así que hay que entrar dos niveles. */
    private fun JSONArray?.firstRelated(relationshipType: String): JSONObject? {
        if (this == null) return null
        for (i in 0 until length()) {
            val relationship = optJSONObject(i) ?: continue
            if (relationship.optString("relationship_type") != relationshipType) continue
            val songs = relationship.optJSONArray("songs") ?: continue
            if (songs.length() > 0) return songs.optJSONObject(0)
        }
        return null
    }

    /** El primer enlace de YouTube de `media` (Genius lista ahí también Spotify, SoundCloud…, que
     * no sirven para el modo vídeo del iPod). */
    private fun youtubeUrl(media: JSONArray?): String? {
        if (media == null) return null
        for (i in 0 until media.length()) {
            val entry = media.optJSONObject(i) ?: continue
            if (entry.optString("provider") != "youtube") continue
            val url = entry.optString("url", null)
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    /** Genius da la fecha en dos formatos según la antigüedad del dato: desglosada en
     * `release_date_components` o como texto ISO en `release_date`. Basta el año. */
    private fun releaseYear(song: JSONObject): Int? {
        song.optJSONObject("release_date_components")?.optInt("year", 0)?.takeIf { it > 0 }
            ?.let { return it }
        return song.optString("release_date", null)?.take(4)?.toIntOrNull()
    }

    // --- Peticiones HTTP ---

    /** Castigo por rate limit de Genius. Con el token puesto por cada usuario (ver
     * [GeniusTokenStore]) la cuota ya no se comparte con nadie más: esto solo protege la cuenta del
     * propio usuario de sus propias peticiones seguidas, igual que [ItunesApi] con la suya. */
    private val rateLimitGuard = RateLimitGuard("Genius")

    /** GET genérico con el token en la cabecera `Authorization`, como exige Genius, ahora con caché
     * y freno de rate limit ([HttpJson]). Cualquier respuesta que no sea "200 OK" se convierte en
     * excepción; ver [detailsFor] sobre cuáles se tragan y cuál no. */
    private fun httpGet(url: String): JSONObject =
        JSONObject(
            HttpJson.get(
                url = url,
                service = "Genius",
                guard = rateLimitGuard,
                userAgent = USER_AGENT,
                headers = mapOf("Authorization" to "Bearer ${GeniusTokenStore.token}")
            )
        )
}
