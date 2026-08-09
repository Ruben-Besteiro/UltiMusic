package com.untar.ultimusic.data.remote

import com.untar.ultimusic.model.LyricsSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente de lrclib.net (base de datos libre y colaborativa de letras sincronizadas), para el
 * buscador de letras del editor de metadatos (ver
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]).
 *
 * Igual que [MusicBrainzApi], es un `object` sin estado, usa `HttpURLConnection` y `org.json` (ya
 * vienen con Android, y la app solo hace un puñado de llamadas de red, así que no compensa una
 * librería aparte) y manda un `User-Agent` identificando la app por buena práctica.
 *
 * A diferencia de la API oficial de YouTube (ver el javadoc de
 * [com.untar.ultimusic.ui.player.VideoPickerDialogFragment] sobre por qué esa se evita con un
 * WebView), la de lrclib.net es pública y gratuita sin clave ni cuenta, y no exige borrar ni
 * refrescar los datos: se puede llamar directamente y guardar la letra elegida para siempre, igual
 * que se hace con los candidatos de MusicBrainz.
 */
object LrcLibApi {

    private const val BASE_URL = "https://lrclib.net/api/"
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"
    private const val TIMEOUT_MS = 8_000

    /**
     * Busca grabaciones de [title]/[artist]. lrclib.net no distingue publicaciones como MusicBrainz
     * (no hay álbum/single que deduplicar): cada resultado es directamente una grabación con su
     * letra ya incluida, así que no hace falta ninguna petición aparte al elegir una.
     *
     * Se descartan las pistas instrumentales y las que no traen letra de ningún tipo (nada que
     * ofrecerle al usuario). El resto se ordena dejando primero las que sí tienen letra
     * SINCRONIZADA, que es el motivo de ser de este buscador.
     */
    suspend fun search(title: String, artist: String): List<LyricsSuggestion> =
        withContext(Dispatchers.IO) {
            val url = "${BASE_URL}search?track_name=${URLEncoder.encode(title, "UTF-8")}" +
                "&artist_name=${URLEncoder.encode(artist, "UTF-8")}"
            val results = httpGet(url)

            val suggestions = mutableListOf<LyricsSuggestion>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                if (item.optBoolean("instrumental")) continue

                val synced = item.optString("syncedLyrics", null)?.takeIf { it.isNotBlank() }
                val plain = item.optString("plainLyrics", null)?.takeIf { it.isNotBlank() }
                if (synced == null && plain == null) continue

                suggestions += LyricsSuggestion(
                    trackName = item.optString("trackName"),
                    artistName = item.optString("artistName"),
                    albumName = item.optString("albumName", null)?.takeIf { it.isNotBlank() },
                    durationMs = item.optDouble("duration").takeIf { !it.isNaN() }?.let { (it * 1000).toLong() },
                    syncedLyrics = synced,
                    plainLyrics = plain
                )
            }

            suggestions.sortedBy { if (it.syncedLyrics != null) 0 else 1 }
        }

    /** GET genérico: manda el `User-Agent` y convierte cualquier respuesta que no sea "200 OK" en
     * una excepción (quien llame la captura con `runCatching`, ver [LyricsSuggestionsViewModel]). */
    private fun httpGet(url: String): JSONArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("lrclib.net respondió ${connection.responseCode} para $url")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }
}
