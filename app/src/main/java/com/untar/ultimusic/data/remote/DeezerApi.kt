package com.untar.ultimusic.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Cliente de la API pública de Deezer, usado únicamente para el buscador de fragmentos (ver
 * [com.untar.ultimusic.ui.preview.PreviewSearchDialogFragment]): cada canción del catálogo trae un
 * `preview` de 30 s en MP3, sin clave ni cuenta, que se reproduce por streaming sin tener la
 * canción en el dispositivo.
 *
 * Por qué Deezer y no iTunes ([ItunesApi], que también da `previewUrl`): los términos de la Search
 * API de Apple limitan los previews a "promocionar contenido de la tienda y no con fines de
 * entretenimiento", que es justo lo contrario de esta función. Los de Deezer limitan el uso a fines
 * no comerciales (ver `developers.deezer.com/termsofuse`), así que **esta función solo es válida
 * mientras UltiMusic no se monetice**.
 *
 * Mismo esquema que [ItunesApi]: `object` sin estado, `HttpURLConnection` vía [HttpJson] y
 * `org.json` a mano. A diferencia de las otras APIs **no usa la caché** ([HttpJson.get] con
 * `useCache = false`): las URLs de preview llevan un token firmado que caduca, y una búsqueda
 * cacheada una semana devolvería enlaces muertos.
 *
 * Deezer limita a 50 peticiones cada 5 s y avisa con un JSON de error de código
 * [QUOTA_EXCEEDED_CODE] **con HTTP 200** (no con un 429), de ahí el tratamiento aparte de
 * [getJson].
 */
object DeezerApi {

    private const val SEARCH_URL = "https://api.deezer.com/search"
    private const val TRACK_URL = "https://api.deezer.com/track"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    /** Resultados por página; el scroll infinito del diálogo va pidiendo el resto. */
    private const val PAGE_SIZE = 25

    /** Código de error de Deezer para "cuota excedida". */
    private const val QUOTA_EXCEEDED_CODE = 4

    /** Un ISRC siempre tiene 12 caracteres (país, registrante, año y código de grabación). */
    private const val ISRC_LENGTH = 12

    private val rateLimitGuard = RateLimitGuard("Deezer")

    /**
     * Una canción del catálogo con fragmento reproducible. [previewUrl] caduca (ver [freshPreviewUrl]).
     */
    data class PreviewTrack(
        val id: Long,
        val title: String,
        val artist: String,
        val albumTitle: String?,
        val coverUrl: String?,
        val previewUrl: String
    )

    /** [nextIndex] es el `index` (desplazamiento) de la página siguiente; [hasMore] dice si hay. */
    data class SearchPage(
        val tracks: List<PreviewTrack>,
        val nextIndex: Int,
        val hasMore: Boolean
    )

    /**
     * Busca canciones por texto libre (título, artista o ambos). Las que no tienen fragmento se
     * descartan: no habría nada que reproducir al tocarlas.
     */
    suspend fun search(query: String, index: Int = 0): SearchPage = withContext(Dispatchers.IO) {
        val url = "$SEARCH_URL?q=${URLEncoder.encode(query, "UTF-8")}&limit=$PAGE_SIZE&index=$index"
        val json = getJson(url)
        val data = json.optJSONArray("data") ?: JSONArray()
        val tracks = (0 until data.length()).mapNotNull { parseTrack(data.getJSONObject(it)) }
        SearchPage(
            tracks = tracks,
            nextIndex = index + data.length(),
            hasMore = data.length() == PAGE_SIZE && json.has("next")
        )
    }

    /**
     * URL de preview recién emitida para [trackId], o null si Deezer ya no la da. La URL que vino
     * en la búsqueda lleva un token que caduca: si el usuario deja el diálogo abierto un rato y
     * toca una fila, hay que pedir otra.
     */
    suspend fun freshPreviewUrl(trackId: Long): String? = withContext(Dispatchers.IO) {
        parseTrack(getJson("$TRACK_URL/$trackId"))?.previewUrl
    }

    /**
     * El ISRC (código internacional de grabación) de [trackId], o null si Deezer no lo da. Lo usa la
     * búsqueda de tiendas ([MusicBrainzApi.downloadStores]): identifica la grabación exacta, a
     * diferencia de título+artista, que puede casar con un directo o una versión distinta. No viene
     * en los resultados de búsqueda, solo en la ficha de cada canción.
     */
    suspend fun isrcOf(trackId: Long): String? = withContext(Dispatchers.IO) {
        // optString devuelve el texto "null" ante un null de JSON: un ISRC real son 12 caracteres.
        getJson("$TRACK_URL/$trackId").optString("isrc").takeIf { it.length == ISRC_LENGTH }
    }

    private fun getJson(url: String): JSONObject {
        val body = HttpJson.get(
            url = url,
            service = "Deezer",
            guard = rateLimitGuard,
            userAgent = USER_AGENT,
            useCache = false
        )
        val json = JSONObject(body)
        val error = json.optJSONObject("error") ?: return json
        if (error.optInt("code") == QUOTA_EXCEEDED_CODE) rateLimitGuard.rateLimited(null)
        throw IOException("Deezer respondió un error: ${error.optString("message")}")
    }

    /** null si el elemento no trae fragmento (Deezer manda `""` en esos casos) o le falta el id. */
    private fun parseTrack(item: JSONObject): PreviewTrack? {
        // optString devuelve el texto "null" ante un null de JSON, de ahí comprobar "http".
        val preview = item.optString("preview").takeIf { it.startsWith("http") } ?: return null
        val id = item.optLong("id", 0L).takeIf { it != 0L } ?: return null
        val album = item.optJSONObject("album")
        return PreviewTrack(
            id = id,
            title = item.optString("title"),
            artist = item.optJSONObject("artist")?.optString("name").orEmpty(),
            albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
            coverUrl = album?.optString("cover_medium")?.takeIf { it.startsWith("http") },
            previewUrl = preview
        )
    }
}
