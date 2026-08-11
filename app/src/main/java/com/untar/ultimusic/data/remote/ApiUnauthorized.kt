package com.untar.ultimusic.data.remote

import java.io.IOException

/**
 * La API ha respondido **401**: no nos ha autorizado. Como [ApiRateLimitException], es una
 * [IOException] para que el `runCatching` de quien llama la siga capturando igual, pero con tipo
 * propio para poder distinguirla.
 *
 * Un 401 tiene dos causas muy distintas (RFC 6750), y confundirlas sale caro:
 * - [invalidToken] (`error="invalid_token"`): el token en sí ha dejado de valer — el usuario ha
 *   borrado o regenerado su API Client en genius.com, o le han suspendido la cuenta. La única salida
 *   es que se saque otro, así que hay que **borrar el guardado y avisarle** (ver
 *   [GeniusApi.detailsFor]).
 * - Cabecera mal formada (`error="invalid_request"`): el token podría ser perfectamente bueno y el
 *   fallo estar en cómo se mandó. Borrarlo aquí sería destruir un token válido por un error nuestro.
 *
 * En UltiMusic el segundo caso **no debería poder ocurrir**: [GeniusTokenStore.token] descarta todo
 * lo que no tenga forma de token, así que la cabecera siempre sale limpia. Se distingue igualmente
 * porque la diferencia entre "borro el token del usuario" y "no lo borro" es demasiado grande como
 * para dejarla a una suposición.
 */
class ApiUnauthorizedException(
    val service: String,
    /** El `error=` de la cabecera `WWW-Authenticate`, o null si la respuesta no la traía. */
    val error: String?
) : IOException("$service ha rechazado la autorización (${error ?: "sin detalle"})") {

    /**
     * Si el problema es el token y no la petición. Ante la duda (sin cabecera `WWW-Authenticate`,
     * que es lo que hace Genius en algunas respuestas) se asume que **sí**: con la cabecera
     * garantizada limpia por [GeniusTokenStore], un 401 sin más detalle es casi con seguridad un
     * token que ha dejado de valer, y dejarlo pasar condenaría al usuario a un fallo silencioso
     * permanente — que es justo lo que no queremos.
     */
    val invalidToken: Boolean
        get() = error == null || error.equals("invalid_token", ignoreCase = true)
}
