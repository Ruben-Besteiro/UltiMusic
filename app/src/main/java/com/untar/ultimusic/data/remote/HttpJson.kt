package com.untar.ultimusic.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * El GET que usan las tres APIs de la aplicación. Antes cada una tenía el suyo, idénticos salvo por
 * las cabeceras y el mensaje de error; se han unificado aquí porque las dos cosas que se les han
 * añadido —la caché y el manejo del 429— tienen que valer para las tres por igual.
 *
 * El orden importa y es este:
 * 1. **Caché** ([ApiCache]): si hay una respuesta reciente para esa URL exacta, se devuelve sin
 *    tocar la red. Es lo primero de todo, así que ni siquiera un servicio castigado por rate limit
 *    impide seguir usando lo ya descargado.
 * 2. **Castigo** ([RateLimitGuard]): si ese servicio nos ha limitado hace poco, se corta aquí con
 *    [ApiRateLimitException] sin abrir la conexión.
 * 3. **Petición**. Un 429 (o un 403, que es como Genius contesta a veces al pasarse de cuota) se
 *    traduce en [ApiRateLimitException] y arranca el castigo; cualquier otro código que no sea 200,
 *    en la [IOException] genérica de siempre.
 * 4. **Guardado**: solo los 200 entran en la caché (ver
 *    [ApiCacheEntity][com.untar.ultimusic.data.db.cache.ApiCacheEntity] sobre por qué los fallos no).
 *
 * Devuelve el cuerpo **sin parsear**: cada API sabe si lo suyo es un objeto o un array, y así lo que
 * se guarda en la caché es exactamente lo que llegó por el cable.
 */
internal object HttpJson {

    private const val TIMEOUT_MS = 8_000

    /** El 429 de siempre y el 403 con el que Genius responde a veces al agotar la cuota del token. */
    private val RATE_LIMIT_CODES = setOf(429, HttpURLConnection.HTTP_FORBIDDEN)

    /**
     * @param service nombre legible para los mensajes de error ("iTunes", "Genius"...).
     * @param guard el [RateLimitGuard] de ese servicio; cada uno lleva su cuenta por separado.
     * @param ttlMs cuánto vale lo cacheado para esta llamada.
     * @param headers cabeceras propias del servicio (el `Authorization` de Genius, por ejemplo).
     */
    fun get(
        url: String,
        service: String,
        guard: RateLimitGuard,
        userAgent: String,
        ttlMs: Long = ApiCache.DEFAULT_TTL_MS,
        headers: Map<String, String> = emptyMap()
    ): String {
        ApiCache.get(url, ttlMs)?.let { return it }

        guard.ensureNotBlocked()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS

        try {
            val code = connection.responseCode
            if (code in RATE_LIMIT_CODES) {
                guard.rateLimited(connection.getHeaderField("Retry-After"))
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw ApiUnauthorizedException(
                    service = service,
                    error = parseAuthError(connection.getHeaderField("WWW-Authenticate"))
                )
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("$service respondió $code para $url")
            }

            // UTF-8 explícito: iTunes sirve el JSON como `text/javascript` sin declarar codificación
            // y, con el valor por defecto de la plataforma, los títulos en japonés o con acentos
            // llegan rotos.
            val body = connection.inputStream.reader(Charsets.UTF_8).use { it.readText() }

            guard.onSuccess()
            ApiCache.put(url, body)
            return body
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Saca el `error=` de una cabecera `WWW-Authenticate`, que en un 401 de Bearer viene tal que
     * `Bearer realm="Genius", error="invalid_token", error_description="..."` (RFC 6750). Devuelve
     * null si no está: ver [ApiUnauthorizedException.invalidToken] sobre qué se asume entonces.
     *
     * Las comillas son opcionales en el formato, de ahí el `"?` de los dos lados.
     */
    private fun parseAuthError(header: String?): String? =
        header?.let { AUTH_ERROR.find(it)?.groupValues?.getOrNull(1) }?.takeIf { it.isNotBlank() }

    private val AUTH_ERROR = Regex("""error\s*=\s*"?([A-Za-z_]+)"?""")
}
