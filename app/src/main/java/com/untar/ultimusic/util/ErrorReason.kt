package com.untar.ultimusic.util

import android.content.Context
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.ApiRateLimitException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** El servidor respondió con un código HTTP que no es 200 (ni 429, que es [ApiRateLimitException]). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** No había nada que procesar (p. ej. una letra sin ninguna línea con texto). */
class NoContentException : Exception("Sin contenido")

/**
 * Convierte una excepción en el MOTIVO, en una frase corta y en el idioma de la app, para que los
 * toasts de error digan qué ha pasado en vez de un "No se ha podido hacer X" a secas.
 */
fun Throwable.reason(context: Context): String = when (this) {
    // Sin segundos exactos: el servidor no dice cuánto dura el bloqueo real (ver RateLimitGuard), así
    // que se promete un mínimo ("al menos") según el castigo total, no según lo que queda de él.
    is ApiRateLimitException -> context.getString(
        if (totalCooldownMs >= 60 * 60_000L) R.string.error_reason_rate_limited_hour
        else R.string.error_reason_rate_limited
    )
    is UnknownHostException, is ConnectException ->
        context.getString(R.string.error_reason_no_connection)
    is SocketTimeoutException -> context.getString(R.string.error_reason_timeout)
    is HttpStatusException -> context.getString(R.string.error_reason_http, code)
    is JSONException -> context.getString(R.string.error_reason_bad_response)
    is NoContentException -> context.getString(R.string.error_reason_no_content)
    is IOException -> message?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.error_reason_other, it) }
        ?: context.getString(R.string.error_reason_bad_response)
    else -> context.getString(R.string.error_reason_other, message ?: javaClass.simpleName)
}

/** "No se ha podido traducir la letra: sin conexión a internet". */
fun Context.errorWithReason(messageRes: Int, error: Throwable): String =
    // El límite de peticiones va solo ("Has alcanzado el límite..."): con el prefijo el toast se cortaba.
    if (error is ApiRateLimitException) error.reason(this)
    else getString(R.string.error_with_reason, getString(messageRes), error.reason(this))
