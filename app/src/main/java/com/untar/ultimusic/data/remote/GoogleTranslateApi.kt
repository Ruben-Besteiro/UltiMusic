package com.untar.ultimusic.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Cliente del endpoint web de Google Translate (`translate.googleapis.com/translate_a/single`,
 * `client=gtx`), para el botón "あ" de traducir la letra del iPod
 * ([com.untar.ultimusic.ui.player.IPodDialogFragment]). Sin clave, sin cuenta y sin cuota visible.
 *
 * Historial de traductores: ML Kit (se equivocaba mucho), Cloud Translation oficial (exige cuenta con
 * tarjeta), MyMemory (calidad irregular), DeepL (cuota de por vida), y Lingva Translate, que era un
 * proxy de voluntarios sobre este mismo endpoint. En noviembre de 2026 TODAS las instancias públicas
 * de Lingva devolvían 500/503/Cloudflare, así que se llama a Google directamente (misma calidad,
 * un intermediario menos). Es un endpoint no documentado: si algún día deja de responder, el
 * siguiente candidato es MyMemory (`api.mymemory.translated.net/get`).
 */
object GoogleTranslateApi {

    private const val TAG = "GoogleTranslateApi"
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) UltiMusic/1.0"
    private const val TIMEOUT_MS = 10_000

    /** Caracteres máximos por petición (el endpoint admite ~5000 en el cuerpo del POST). */
    private const val MAX_CHUNK_CHARS = 4_000

    private val rateLimitGuard = RateLimitGuard("GoogleTranslate")

    /**
     * Traduce [lines] al idioma del SISTEMA con el idioma de origen autodetectado. Devuelve la lista
     * traducida en el mismo orden y tamaño que [lines], o `null` si falla alguna petición o si algún
     * trozo vuelve con distinto número de líneas (mejor fallar entero que desalinear la letra
     * sincronizada). Nunca lanza. [lines] no debería incluir líneas en blanco (ya las filtra
     * [com.untar.ultimusic.util.LyricsTranslator]).
     */
    suspend fun translateLines(lines: List<String>): List<String>? {
        if (lines.isEmpty()) return emptyList()
        val target = Locale.getDefault().language

        return withContext(Dispatchers.IO) {
            runCatching {
                val result = mutableListOf<String>()
                for (piece in chunk(lines)) {
                    rateLimitGuard.ensureNotBlocked()
                    val body = request(target, piece.joinToString("\n"))
                    val translatedLines = parseTranslation(body).split("\n")
                    if (translatedLines.size != piece.size) {
                        throw IOException("Google devolvió ${translatedLines.size} líneas, se mandaron ${piece.size}")
                    }
                    rateLimitGuard.onSuccess()
                    result.addAll(translatedLines)
                }
                result
            }.onFailure { e ->
                Log.e(TAG, "Fallo traduciendo ${lines.size} líneas: ${e.message}", e)
            }.getOrNull()
        }
    }

    private fun request(target: String, text: String): String {
        val url = "$ENDPOINT?client=gtx&sl=auto&tl=${URLEncoder.encode(target, "UTF-8")}&dt=t"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.outputStream.use {
                it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code == 429) {
                rateLimitGuard.rateLimited(connection.getHeaderField("Retry-After"))
            }
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.reader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Agrupa [lines] en trozos de como mucho [MAX_CHUNK_CHARS] caracteres (una línea enorme va sola). */
    private fun chunk(lines: List<String>): List<List<String>> {
        val chunks = mutableListOf<MutableList<String>>()
        var size = 0
        for (line in lines) {
            if (chunks.isEmpty() || size + line.length + 1 > MAX_CHUNK_CHARS) {
                chunks.add(mutableListOf(line))
                size = line.length
            } else {
                chunks.last().add(line)
                size += line.length + 1
            }
        }
        return chunks
    }

    /** Respuesta `[[[traducido, original, ...], ...], ...]`: una línea larga puede venir partida en
     *  varios segmentos, así que se concatenan todos y luego se vuelve a trocear por saltos de línea. */
    private fun parseTranslation(body: String): String {
        val segments = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            sb.append(seg.optString(0, ""))
        }
        if (sb.isBlank()) throw IOException("Google no devolvió traducción")
        return sb.toString().trimEnd('\n')
    }
}
