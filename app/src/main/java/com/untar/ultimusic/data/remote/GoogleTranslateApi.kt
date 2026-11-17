package com.untar.ultimusic.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.untar.ultimusic.util.HttpStatusException
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

    /** Al primer 429, 1 h de castigo (ver [RateLimitGuard]): Google renueva el bloqueo anti-bot con
     *  cada petición recibida mientras dura, y cada reintento tras un cooldown corto lo alargaba. */
    private val rateLimitGuard = RateLimitGuard("GoogleTranslate", cooldownMs = COOLDOWN_MS)
    private const val COOLDOWN_MS = 60 * 60_000L

    /**
     * Traduce [lines] al idioma del SISTEMA con el idioma de origen autodetectado. Devuelve la lista
     * traducida en el mismo orden y tamaño que [lines], o `null` si falla alguna petición o si algún
     * trozo vuelve con distinto número de líneas (mejor fallar entero que desalinear la letra
     * sincronizada). Nunca lanza. [lines] no debería incluir líneas en blanco (ya las filtra
     * [com.untar.ultimusic.util.LyricsTranslator]).
     */
    suspend fun translateLines(lines: List<String>): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())
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
            }
        }
    }

    /**
     * Romanización ESTÁNDAR del texto original (Hepburn, pinyin, Romanización Revisada, cirílico a
     * la inglesa...) con su lectura según el contexto —los kanji salen con la lectura japonesa, no
     * la china— que es lo que ICU no sabe hacer; la pide a Google con `dt=rm` (campo 3 del segmento
     * final: transliteración del texto de origen). Devuelve una entrada por línea de [lines], o
     * un fallo (con su excepción) si falla alguna petición. Nunca lanza. Cada trozo de la letra
     * ([MAX_CHUNK_CHARS]) va en UNA petición ([transliterateBatch]), con recurso a una por línea.
     * Si cualquier trozo falla, falla todo: no se devuelve una romanización a medias.
     */
    suspend fun transliterateLines(lines: List<String>, sourceLanguage: String? = null): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())
        // Con idioma de origen fijado, una línea de solo kanji no se puede confundir con chino.
        val source = when (sourceLanguage) {
            null, "" -> "auto"
            "zh" -> "zh-CN"
            "iw" -> "he"
            else -> sourceLanguage
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val result = mutableListOf<String>()
                for (piece in chunk(lines)) {
                    rateLimitGuard.ensureNotBlocked()
                    val batch = transliterateBatch(piece, source)
                    if (batch != null) {
                        result.addAll(batch)
                    } else {
                        // Recurso: una petición por línea. Una línea sin romanización se queda como estaba.
                        for (line in piece) {
                            rateLimitGuard.ensureNotBlocked()
                            val body = request("en", line, dt = "t&dt=rm", source = source)
                            result.add(runCatching { parseTransliteration(body) }.getOrDefault(line))
                        }
                    }
                    rateLimitGuard.onSuccess()
                }
                result
            }.onFailure { e ->
                Log.e(TAG, "Fallo romanizando ${lines.size} líneas: ${e.message}", e)
            }
        }
    }

    /** Separador entre líneas al romanizar en bloque. Verificado en dispositivo (noviembre de 2026):
     *  Google devuelve la romanización de todo el texto como UNA sola cadena, sin saltos de línea y
     *  sin segmentos por línea, pero un "|" entre líneas sobrevive tal cual (`a | b | c`). */
    private const val BATCH_SEPARATOR = "|"
    private val BATCH_SPLIT = Regex("""\s*\|\s*""")

    /**
     * Romaniza [piece] en UNA petición, con [BATCH_SEPARATOR] en una línea propia entre cada par de
     * líneas, y parte la respuesta por él. `null` si no se puede fiarse del resultado (alguna línea
     * ya contiene el separador, la petición falla por algo que no es el límite de peticiones, o el
     * número de partes no cuadra): el llamador pide entonces línea a línea. Un límite de peticiones
     * ([ApiRateLimitException]) sí se propaga: insistir con 70 peticiones más solo lo empeoraría.
     */
    private fun transliterateBatch(piece: List<String>, source: String): List<String>? {
        if (piece.size < 2 || piece.any { it.contains(BATCH_SEPARATOR) }) return null
        rateLimitGuard.ensureNotBlocked()
        return try {
            val body = request("en", piece.joinToString("\n$BATCH_SEPARATOR\n"), dt = "t&dt=rm", source = source)
            val parts = parseTransliteration(body).trim().split(BATCH_SPLIT)
            if (parts.size == piece.size) parts
            else {
                Log.w(TAG, "Bloque con ${parts.size} partes romanizadas, se mandaron ${piece.size}; línea a línea")
                null
            }
        } catch (e: ApiRateLimitException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Romanización en bloque fallida (${e.message}); línea a línea")
            null
        }
    }

    private fun parseTransliteration(body: String): String {
        val segments = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            if (seg.length() > 3 && !seg.isNull(3)) sb.append(seg.optString(3, ""))
        }
        if (sb.isBlank()) throw IOException("Google no devolvió romanización")
        return sb.toString().trimEnd('\n')
    }

    private fun request(target: String, text: String, dt: String = "t", source: String = "auto"): String {
        val url = "$ENDPOINT?client=gtx&sl=${URLEncoder.encode(source, "UTF-8")}&tl=${URLEncoder.encode(target, "UTF-8")}&dt=$dt"
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
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code)
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
