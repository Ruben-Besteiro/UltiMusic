package com.untar.ultimusic.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Nos han limitado las peticiones (HTTP 429, o un 403 de Genius que en la práctica significa lo
 * mismo). Es una [IOException] como las demás para que el `runCatching` de quien llama la siga
 * capturando igual que antes y nada se rompa, pero al ser un tipo propio se puede distinguir del
 * resto — que es justo el motivo de existir de esta clase.
 *
 * Antes no se podía: los tres `httpGet` de la app convertían CUALQUIER respuesta que no fuera 200 en
 * una `IOException` genérica, y arriba se traducía toda a "no se ha podido buscar" (o, en Genius, a
 * un `null` silencioso). Con eso, el día que una API nos limite, la aplicación se degrada sin que se
 * entere nadie: ni el usuario, que ve un error de conexión que no es tal, ni el desarrollador.
 *
 * [retryAfterMs] es cuánto hay que esperar, sacado de la cabecera `Retry-After` si el servidor la
 * manda, o del backoff de [RateLimitGuard] si no.
 */
class ApiRateLimitException(
    val service: String,
    val retryAfterMs: Long,
    /** Duración TOTAL del castigo, no lo que queda de él ([retryAfterMs] baja con el tiempo): es lo
     *  que decide qué aviso se le enseña al usuario ("al menos 15 minutos" o "al menos una hora"). */
    val totalCooldownMs: Long = retryAfterMs
) : IOException("$service ha limitado las peticiones; hay que esperar ${retryAfterMs / 1000} s")

/** Los segundos que hay que esperar, para enseñárselos al usuario. Nunca 0: decir "espera 0
 *  segundos" cuando acabamos de negar la petición no tiene sentido, así que se redondea a 1. */
fun ApiRateLimitException.retryInSeconds(): Long = (retryAfterMs / 1000).coerceAtLeast(1L)

/**
 * Recuerda que una API nos ha limitado y, mientras dure el castigo, corta las siguientes peticiones
 * **sin llegar a abrir la conexión**. Hay uno por servicio (ver [GeniusApi], [ItunesApi],
 * [LrcLibApi]), porque el límite de cada uno va por su cuenta.
 *
 * Sin esto, recibir un 429 no cambiaría nada: la app seguiría lanzando peticiones al mismo ritmo,
 * que es exactamente lo que hace que un límite temporal se convierta en un bloqueo largo. Y es peor
 * en Genius, donde el token va incrustado en el APK y por tanto **todos los usuarios comparten
 * cuota**: ahí cada petición de más la pagan todos.
 *
 * El castigo es fijo ([cooldownMs]; antes se doblaba con cada 429 seguido, pero los reintentos los
 * provoca el usuario con un botón y en meses de uso nunca se llegó a un segundo 429, así que no
 * compensaba la complejidad) y se borra en cuanto una petición vuelve a salir bien ([onSuccess]).
 */
class RateLimitGuard(
    private val service: String,
    /** Castigo tras un 429, aunque el servidor mande un `Retry-After` más corto. Google Translate
     *  lo sube a 1 h: su bloqueo anti-bot se renueva con cada petición que le llega mientras dura,
     *  así que esperar de más es mejor que reintentar pronto. */
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {

    /** Instante (reloj del sistema) hasta el que no se puede pedir nada. 0 = vía libre. */
    @Volatile
    private var blockedUntilMs = 0L

    /** Duración total del último castigo, para [ApiRateLimitException.totalCooldownMs]. */
    @Volatile
    private var lastCooldownMs = cooldownMs

    /** Cuánto queda de castigo, o 0 si no lo hay. Lo usa la interfaz para decir cuánto esperar. */
    fun remainingMs(): Long = (blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Lanza [ApiRateLimitException] si todavía estamos castigados. Se llama ANTES de abrir la
     * conexión: la gracia es no gastar la petición (ni el tiempo del usuario esperando un timeout)
     * cuando ya sabemos que la respuesta va a ser otro 429.
     */
    fun ensureNotBlocked() {
        val remaining = remainingMs()
        if (remaining > 0L) throw ApiRateLimitException(service, remaining, lastCooldownMs)
    }

    /** Una petición ha ido bien: se levanta el castigo. */
    fun onSuccess() {
        blockedUntilMs = 0L
    }

    /**
     * Nos acaban de limitar. Apunta hasta cuándo hay que estarse quieto y lanza la excepción, que
     * es siempre lo que quiere quien llama (de ahí el `Nothing`: no hay camino de vuelta).
     *
     * Si el servidor manda un `Retry-After` más largo que [cooldownMs] se le hace caso —él sabe
     * mejor que nosotros—, solo con un tope por si manda una barbaridad.
     */
    fun rateLimited(retryAfterHeader: String?): Nothing {
        val fromHeader = parseRetryAfterMs(retryAfterHeader)
        val cooldown = maxOf(cooldownMs, fromHeader?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: 0L)

        blockedUntilMs = System.currentTimeMillis() + cooldown
        lastCooldownMs = cooldown
        throw ApiRateLimitException(service, cooldown, cooldown)
    }

    /** `Retry-After` puede venir en segundos o como fecha HTTP. Solo se entiende el primer formato
     *  (que es el que mandan estas tres APIs); con el segundo se devuelve null y manda [cooldownMs]. */
    private fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { TimeUnit.SECONDS.toMillis(it) }

    private companion object {
        /** Espera por defecto tras un 429. */
        const val DEFAULT_COOLDOWN_MS = 15 * 60_000L

        /** Tope del `Retry-After` que mande el servidor. */
        const val MAX_RETRY_AFTER_MS = 60 * 60_000L
    }
}
