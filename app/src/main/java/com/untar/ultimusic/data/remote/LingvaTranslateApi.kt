package com.untar.ultimusic.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Cliente de Lingva Translate (`github.com/thedaviddelta/lingva-translate`), para el botón "あ" de
 * traducir la letra del iPod ([com.untar.ultimusic.ui.player.IPodDialogFragment]). Es el CUARTO
 * traductor que prueba esta clase a lo largo de su historial —ML Kit Translate (on-device, pero se
 * equivocaba mucho), la Cloud Translation API oficial de Google (pedía cuenta de Google Cloud con
 * tarjeta desde el minuto uno), MyMemory (sin cuenta, pero calidad más irregular) y DeepL (mejor
 * calidad, pero desde julio de 2026 su plan gratuito es un millón de caracteres DE POR VIDA, sin
 * renovación, además de exigir cuenta)—: Lingva es un proxy de código abierto sobre el motor REAL de
 * Google Translate, así que da la misma calidad que el traductor de Google de toda la vida, sin
 * clave, sin cuenta y sin cuota que se agote.
 *
 * El pero es que corre sobre instancias públicas gestionadas por voluntarios, sin ningún SLA: no
 * todas están siempre en pie (ver [INSTANCES] y cómo se prueban en orden). Si algún día TODAS las de
 * la lista fallan a la vez, hay que renovarla a mano con la lista actualizada de
 * https://github.com/thedaviddelta/lingva-translate#instances (comprobado a mano en agosto de 2026:
 * de 9 instancias públicas probadas, solo `lingva.dialectapp.org` respondía).
 */
object LingvaTranslateApi {

    private const val TAG = "LingvaTranslateApi"

    /**
     * Instancias públicas conocidas, en el orden en que se prueban (la primera que responda gana).
     * `lingva.dialectapp.org` va primera por ser la única que funcionaba en la comprobación de
     * agosto de 2026; el resto se dejan como reserva por si vuelven o por si esa cae en el futuro.
     */
    private val INSTANCES = listOf(
        "lingva.dialectapp.org",
        "lingva.lunar.icu",
        "translate.plausibility.cloud",
        "lingva.garudalinux.org",
        "lingva.thedaviddelta.com",
    )

    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"
    private const val TIMEOUT_MS = 10_000

    /** Bytes máximos de la porción `/api/v1/auto/xx/TEXTO` ya codificada para URL, por debajo del
     *  límite de longitud de URL que aplican algunos servidores/proxies (el 414 que se ve alguna vez
     *  en la web de Lingva es justo eso). Con esto, una letra de canción normal cabe en una sola
     *  petición; una excepcionalmente larga se trocea (ver [chunk]). */
    private const val MAX_CHUNK_ENCODED_BYTES = 2_000

    private val rateLimitGuard = RateLimitGuard("Lingva")

    /**
     * Traduce [lines] al idioma del SISTEMA en el menor número de peticiones posible (ver [chunk]),
     * dejando que Lingva/Google detecten el idioma de origen solos (`auto`). Devuelve la lista
     * traducida en el mismo orden y tamaño que [lines], o `null` si TODAS las instancias de
     * [INSTANCES] fallan para algún trozo, o si algún trozo vuelve con más o menos líneas de las que
     * se mandaron (se preferiría fallar entero antes que desalinear la letra sincronizada). Nunca
     * lanza.
     *
     * [lines] no debería incluir líneas en blanco: quien llama ([com.untar.ultimusic.util.LyricsTranslator])
     * ya las filtra antes.
     */
    suspend fun translateLines(lines: List<String>): List<String>? {
        if (lines.isEmpty()) return emptyList()
        val target = Locale.getDefault().language

        return withContext(Dispatchers.IO) {
            runCatching {
                val result = mutableListOf<String>()
                for (piece in chunk(lines)) {
                    rateLimitGuard.ensureNotBlocked()
                    val path = "/api/v1/auto/${encodePathSegment(target)}/" +
                        encodePathSegment(piece.joinToString("\n"))
                    val response = requestFirstWorkingInstance(path)
                        ?: throw IOException("Ninguna instancia de Lingva ha respondido")
                    val translatedText = parseTranslation(response)
                        ?: throw IOException("Lingva no devolvió traducción: ${response.take(300)}")
                    val translatedLines = translatedText.split("\n")
                    // Si el trozo vuelve con más o menos líneas de las que se mandaron, la traducción
                    // se desalinearía de la letra sincronizada: mejor fallar todo el intento.
                    if (translatedLines.size != piece.size) {
                        throw IOException("Lingva devolvió ${translatedLines.size} líneas, se mandaron ${piece.size}")
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

    /**
     * Prueba [INSTANCES] en orden contra [path] y devuelve el cuerpo de la primera que responda 200.
     * Un fallo de red, un error 4xx/5xx o un timeout pasan a la siguiente instancia en vez de rendirse
     * (salvo 429, que corta aquí mismo: significa que Lingva/Google nos está limitando de verdad, y
     * probar otra instancia no lo arregla, solo lo empeora).
     */
    private fun requestFirstWorkingInstance(path: String): String? {
        for (host in INSTANCES) {
            val connection = runCatching { URL("https://$host$path").openConnection() as HttpURLConnection }
                .getOrNull() ?: continue
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                val code = connection.responseCode
                if (code == 429) {
                    rateLimitGuard.rateLimited(connection.getHeaderField("Retry-After"))
                }
                if (code != HttpURLConnection.HTTP_OK) continue
                return connection.inputStream.reader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                continue
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /**
     * Agrupa [lines] en el menor número de trozos posible, cada uno por debajo de
     * [MAX_CHUNK_ENCODED_BYTES] al codificar sus líneas juntas para URL (que es como viajan de
     * verdad, ver [translateLines]). Una línea que por sí sola ya supere el límite va en su propio
     * trozo (mejor intentarlo y que falle esa petición que trocear una palabra a la mitad).
     */
    private fun chunk(lines: List<String>): List<List<String>> {
        val chunks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            val current = chunks.lastOrNull()
            val candidateBytes = if (current == null) {
                encodePathSegment(line).toByteArray(Charsets.UTF_8).size
            } else {
                encodePathSegment(current.joinToString("\n") + "\n" + line).toByteArray(Charsets.UTF_8).size
            }
            if (current == null || candidateBytes > MAX_CHUNK_ENCODED_BYTES) {
                chunks.add(mutableListOf(line))
            } else {
                current.add(line)
            }
        }
        return chunks
    }

    /** [URLEncoder] codifica para `application/x-www-form-urlencoded` (parámetros de formulario o
     *  query), donde el espacio se escribe "+". El texto de Lingva va en la RUTA de la URL
     *  (`/api/v1/auto/es/texto`), no en un parámetro, y ahí un "+" es un carácter literal, no un
     *  espacio — de ahí que las letras traducidas salieran con "+" en vez de espacios. Se corrige
     *  reemplazando ese "+" por el "%20" que sí vale en cualquier parte de la URL. */
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** `{"translation": "..."}`. `null` si la respuesta no tiene esa forma. */
    private fun parseTranslation(body: String): String? {
        val text = runCatching { JSONObject(body).optString("translation") }.getOrNull()
        return text?.takeIf { it.isNotBlank() }
    }
}
