package com.untar.ultimusic.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cliente mínimo de la YouTube Data API v3 (`videos.list`/`channels.list`), para las visitas del
 * vídeo de una canción (ver el subtítulo de `SongsAdapter`/`CollectionSongsAdapter` y
 * [com.untar.ultimusic.model.Song.youtubeViewCount]) y los suscriptores del canal de un artista (ver
 * [channelStats] y [com.untar.ultimusic.model.PersonSummary.popularity]).
 *
 * No tiene nada que ver con [com.untar.ultimusic.model.Song.videoUrl] en sí: ese enlace lo pone
 * SIEMPRE el usuario (ver el comentario de
 * [com.untar.ultimusic.data.db.entities.SongEntity.videoUrl]); esto solo ENRIQUECE con datos de
 * solo lectura que YouTube expone públicamente para cualquier vídeo o canal, sin necesitar ni tocar
 * la reproducción.
 *
 * `part=statistics` con hasta 50 ids separados por coma en una sola llamada cuesta 1 unidad de las
 * 10 000 diarias que da una clave por defecto, así que un refresco diario de toda la fonoteca (ver
 * [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue]) sale barato incluso con
 * miles de canciones.
 */
object YouTubeStatsApi {

    private const val VIDEOS_ENDPOINT = "https://www.googleapis.com/youtube/v3/videos"
    private const val CHANNELS_ENDPOINT = "https://www.googleapis.com/youtube/v3/channels"
    /** Endpoint de validación: una lista estática que no depende de ningún vídeo/canal concreto, así
     *  que sirve para comprobar la clave sola (ver [validateKey]) con el coste de cuota más bajo
     *  posible (1 unidad, igual que `videos.list`/`channels.list`). */
    private const val REGIONS_ENDPOINT = "https://www.googleapis.com/youtube/v3/i18nRegions"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Más corto que el de las búsquedas normales: en [validateKey] hay alguien mirando la pantalla y
     *  esperando un sí o un no (mismo motivo que [GeniusApi.VALIDATION_TIMEOUT_MS]). */
    private const val VALIDATION_TIMEOUT_MS = 5_000

    /** Como mucho 50 ids por llamada: es el tope que impone la propia API, tanto para vídeos como
     *  para canales. */
    const val MAX_IDS_PER_CALL = 50

    /** Falso si no hay clave configurada (ver [YouTubeApiKeyStore]): no tiene sentido intentar la
     *  petición. */
    val isConfigured: Boolean get() = YouTubeApiKeyStore.key.isNotBlank()

    /** Mismo freno de rate limit que las otras tres APIs (ver [RateLimitGuard]), propio de YouTube. */
    private val rateLimitGuard = RateLimitGuard("YouTube")

    /** Visitas y canal de un vídeo (ver [videoStats]). [channelId] es null si YouTube no lo trae en
     *  la respuesta, algo que no debería pasar para un vídeo público pero que se trata igual que
     *  cualquier otro dato ausente: sin romper nada, solo sin popularidad de artista para esa fila. */
    data class VideoStats(val viewCount: Long, val channelId: String?)

    /**
     * Visitas y canal de hasta [MAX_IDS_PER_CALL] vídeos a la vez, mapeados por id de vídeo. Un
     * vídeo borrado, privado o que ya no exista simplemente no sale en la respuesta, así que el mapa
     * puede venir más corto que [videoIds] sin que eso sea un error.
     *
     * Pedir `part=snippet,statistics` en la misma llamada no cuesta más cuota que pedir solo
     * `statistics`: en `videos.list` el coste es fijo (1 unidad) por llamada, no por parte.
     *
     * Nunca lanza: esto es un dato decorativo del subtítulo de una canción (y de la popularidad de su
     * artista), y un fallo de red o de cuota no puede romper el refresco de la fonoteca ni, mucho
     * menos, la reproducción. Ante cualquier problema se devuelve el mapa vacío y se reintenta en el
     * siguiente refresco diario.
     */
    suspend fun videoStats(videoIds: List<String>): Map<String, VideoStats> {
        if (videoIds.isEmpty() || !isConfigured) return emptyMap()
        require(videoIds.size <= MAX_IDS_PER_CALL) {
            "La API de YouTube no acepta más de $MAX_IDS_PER_CALL ids por llamada"
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val body = HttpJson.get(
                    url = videosRequestUrl(videoIds),
                    service = "YouTube",
                    guard = rateLimitGuard,
                    userAgent = USER_AGENT,
                    // Se refresca como mucho una vez al día (ver YouTubeStatsRefresh), así que no
                    // hace falta más caché que esa: un TTL corto es solo para que dos refrescos
                    // seguidos por un fallo a medias no vuelvan a gastar la misma llamada.
                    ttlMs = TimeUnit.HOURS.toMillis(12)
                )
                parseVideoStats(body)
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Suscriptores de hasta [MAX_IDS_PER_CALL] canales a la vez, mapeados por id de canal. La usa
     * [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue] para resolver el canal "de
     * cada artista" (los candidatos salen de `LibraryDao.artistChannelCandidates`) y guardar sus
     * suscriptores directamente en la fila del artista. Nunca lanza, pero a diferencia de [videoStats]
     * SÍ distingue "pregunté y no había dato" de "no llegué ni a preguntar": null cuando la llamada
     * falla entera (red, cuota, lo que sea) y mapa —vacío o no— cuando YouTube de verdad respondió. La
     * distinción importa porque quien llama, a falta de dato, escribe explícitamente `null` en el
     * artista (canal borrado / sin candidato válido) — algo que solo tiene sentido si de verdad se
     * preguntó; confundirlo con un fallo de red borraría suscriptores buenos por una cuota agotada.
     *
     * Un canal con el recuento de suscriptores oculto (`hiddenSubscriberCount = true`) no trae
     * `subscriberCount` en la respuesta, así que cae en el mismo caso que uno que no exista: sin dato
     * válido dentro de una llamada que SÍ respondió, se prueba con el siguiente candidato del artista.
     */
    suspend fun channelStats(channelIds: List<String>): Map<String, Long>? {
        if (channelIds.isEmpty() || !isConfigured) return emptyMap()
        require(channelIds.size <= MAX_IDS_PER_CALL) {
            "La API de YouTube no acepta más de $MAX_IDS_PER_CALL ids por llamada"
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val body = HttpJson.get(
                    url = channelsRequestUrl(channelIds),
                    service = "YouTube",
                    guard = rateLimitGuard,
                    userAgent = USER_AGENT,
                    ttlMs = TimeUnit.HOURS.toMillis(12)
                )
                parseChannelSubscriberCounts(body)
            }.getOrNull()
        }
    }

    /**
     * Comprueba contra YouTube si [candidate] es una clave válida, para poder decírselo al usuario en
     * el momento de pegarla (ver [com.untar.ultimusic.ui.sort.YouTubeApiKeyDialogFragment]), igual
     * que [GeniusApi.validateToken]. Mismo motivo para no pasar por [HttpJson]: aquí hace falta una
     * petición de verdad, sin caché (la clave no forma parte de ninguna URL cacheada, así que esto no
     * tiene el problema de [GeniusApi.validateToken], pero se mantiene el mismo patrón por
     * consistencia) y sin tocar [rateLimitGuard], para que una clave recién pegada no pague el
     * castigo de la anterior.
     */
    suspend fun validateKey(candidate: String): Boolean = withContext(Dispatchers.IO) {
        if (candidate.isBlank()) return@withContext false
        runCatching {
            val url = "$REGIONS_ENDPOINT?part=snippet&key=${URLEncoder.encode(candidate.trim(), "UTF-8")}"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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

    private fun videosRequestUrl(videoIds: List<String>): String {
        val ids = videoIds.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        val key = URLEncoder.encode(YouTubeApiKeyStore.key, "UTF-8")
        return "$VIDEOS_ENDPOINT?part=snippet,statistics&id=$ids&key=$key"
    }

    private fun channelsRequestUrl(channelIds: List<String>): String {
        val ids = channelIds.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        val key = URLEncoder.encode(YouTubeApiKeyStore.key, "UTF-8")
        return "$CHANNELS_ENDPOINT?part=statistics&id=$ids&key=$key"
    }

    private fun parseVideoStats(body: String): Map<String, VideoStats> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyMap()
        val result = mutableMapOf<String, VideoStats>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val views = item.optJSONObject("statistics")
                ?.optString("viewCount")
                ?.toLongOrNull()
                ?: continue
            val channelId = item.optJSONObject("snippet")
                ?.optString("channelId", null)
                ?.takeIf { it.isNotBlank() }
            result[id] = VideoStats(views, channelId)
        }
        return result
    }

    private fun parseChannelSubscriberCounts(body: String): Map<String, Long> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val subscribers = item.optJSONObject("statistics")
                ?.optString("subscriberCount")
                ?.toLongOrNull()
                ?: continue
            result[id] = subscribers
        }
        return result
    }
}
