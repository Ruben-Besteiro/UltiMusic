package com.untar.ultimusic.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.untar.ultimusic.BuildConfig

/**
 * Dónde vive el *client access token* de Genius, que a partir de ahora **pone cada usuario**, no la
 * compilación (ver [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment], que es quien lo pide).
 *
 * ## Por qué lo pone el usuario
 *
 * Antes el token salía de `local.properties` y viajaba dentro del APK vía `BuildConfig`. Eso tenía
 * dos problemas que no se arreglan con código:
 * - La cuota de Genius va **por token**, no por IP, así que todos los usuarios compartían una sola.
 *   Bastaba que la app creciera para que empezaran los 429 a la vez para todo el mundo.
 * - El token es extraíble del APK con `strings` en un minuto, y la cuenta sancionada por el abuso
 *   sería la del desarrollador, no la de quien abusara.
 *
 * Con un token por usuario los dos desaparecen: cada uno gasta de su cuota y responde de su cuenta.
 * La otra salida era montar un proxy propio que guardara el token, pero eso es infraestructura que
 * mantener, un punto único de fallo y un servidor que ve lo que consulta cada usuario.
 *
 * ## El respaldo de BuildConfig es SOLO de desarrollo
 *
 * Si no hay token guardado se usa el de `local.properties`, pero **únicamente en compilaciones de
 * depuración** (ver [developmentToken]). Es una comodidad para no pasar por el diálogo en cada
 * instalación limpia mientras se prueba la aplicación.
 *
 * En release no existe, y no es un detalle: [shouldOfferSetup] decide si enseñar el diálogo mirando
 * si hay token utilizable, así que un token incrustado en el APK publicado haría que **el diálogo no
 * le saliera a nadie** y que todos los usuarios volvieran a compartir la cuota del desarrollador —
 * exactamente el problema que esta clase existe para resolver. De ahí que se corte por dos sitios a
 * la vez: en `app/build.gradle.kts` (donde el campo se declara vacío salvo en debug) y aquí.
 */
object GeniusTokenStore {

    private const val PREFS_NAME = "genius"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_DECLINED = "setup_declined"

    /**
     * Los client access token de Genius son 64 caracteres de `[A-Za-z0-9_-]`. El rango se deja
     * holgado (no exactamente 64) por si Genius cambia la longitud algún día: esto solo sirve para
     * descartar que lo que hay en el portapapeles sea texto cualquiera antes de molestar al usuario
     * con una validación contra la red, no para verificar nada. Quien decide de verdad si el token
     * vale es [GeniusApi.validateToken].
     */
    private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{40,128}")

    @Volatile
    private var prefs: SharedPreferences? = null

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que a [ApiCache] y por el mismo motivo. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * El token a usar: el que puso el usuario o, si no hay, el de la compilación. Cadena vacía si no
     * hay ninguno de los dos, que es lo que hace que [GeniusApi.isConfigured] valga false.
     *
     * **Aquí es donde se garantiza que la cabecera `Authorization` nunca puede salir mal formada.**
     * No basta con recortar espacios: un token con un salto de línea dentro partiría la cabecera en
     * dos, y uno con caracteres raros haría que Genius devolviera 401 `invalid_request` — un fallo
     * que se parece mucho a "tu token no vale" sin serlo (ver [HttpJson]). Así que lo que no encaje
     * en [TOKEN_SHAPE] después de recortarlo se trata como **si no hubiera token en absoluto**: se
     * devuelve cadena vacía, [GeniusApi.isConfigured] queda en false y no se llega a hacer la
     * petición. Mejor quedarse sin los extras de Genius que mandar basura en una cabecera.
     *
     * El camino del usuario ya está limpio de por sí ([save] recorta y el diálogo solo guarda lo que
     * pasa [looksLikeToken] y además valida contra Genius), pero el de `BuildConfig` no: ahí el valor
     * sale de `local.properties`, y `java.util.Properties` conserva los espacios finales, así que un
     * `genius.token=abc… ` con un espacio detrás llegaría entero hasta la cabecera.
     */
    val token: String
        get() = sanitize(prefs?.getString(KEY_TOKEN, null)).ifEmpty { developmentToken() }

    /**
     * El token de `local.properties`, y **solo en compilaciones de depuración**.
     *
     * `app/build.gradle.kts` ya se encarga de que en release valga cadena vacía (allí está explicado
     * el porqué), así que esto es una segunda barrera: si algún día alguien vuelve a declarar
     * `buildConfigField` en `defaultConfig` sin darse cuenta de lo que implica, el token seguiría sin
     * usarse en el APK publicado. Que un descuido en el fichero de compilación reabra el agujero de
     * "todos los usuarios comparten el token del desarrollador" sale demasiado caro como para dejarlo
     * en manos de un único punto de control.
     */
    private fun developmentToken(): String =
        if (BuildConfig.DEBUG) sanitize(BuildConfig.GENIUS_TOKEN) else ""

    /** Si el usuario ya pasó por el diálogo de configuración. Se mira ANTES de abrir el autorrelleno
     *  para no enseñárselo dos veces; distinto de [token], que incluye el respaldo de BuildConfig. */
    val hasUserToken: Boolean
        get() = sanitize(prefs?.getString(KEY_TOKEN, null)).isNotEmpty()

    /**
     * Si el usuario ha rechazado expresamente configurar Genius (botón "Salir" del diálogo). No es lo
     * mismo que "no tiene token": puede estar baneado de Genius, o sencillamente no querer pasar por
     * el proceso, y en ese caso hay que dejarle usar el autorrelleno con iTunes y **no volver a
     * insistir**. Se guarda en disco porque la decisión tiene que sobrevivir a cerrar la aplicación.
     */
    val setupDeclined: Boolean
        get() = prefs?.getBoolean(KEY_DECLINED, false) == true

    /** Si toca ofrecer el diálogo de configuración: ni hay token utilizable ni el usuario ha dicho
     *  que no quiere. */
    val shouldOfferSetup: Boolean
        get() = token.isEmpty() && !setupDeclined

    fun save(token: String) {
        // Guardar recortado y, de paso, olvidar cualquier rechazo anterior: si el usuario acaba de
        // configurarlo, es que ya no lo rechaza.
        prefs?.edit()?.putString(KEY_TOKEN, token.trim())?.putBoolean(KEY_DECLINED, false)?.apply()
    }

    fun decline() {
        prefs?.edit()?.putBoolean(KEY_DECLINED, true)?.apply()
    }

    /** Borra el token guardado **sin** tocar el rechazo: la usa [GeniusApi.detailsFor] cuando Genius
     *  responde que el token ha dejado de ser válido (API Client borrado o regenerado). */
    fun clear() {
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
    }

    /** Recorta y descarta lo que no tenga forma de token. Ver [token] sobre por qué esto es lo que
     *  impide que la cabecera `Authorization` salga mal formada. */
    private fun sanitize(candidate: String?): String {
        val trimmed = candidate?.trim().orEmpty()
        return if (TOKEN_SHAPE.matches(trimmed)) trimmed else ""
    }

    /** Si [candidate] tiene pinta de token, para decidir si vale la pena validarlo contra Genius.
     *  Ver [TOKEN_SHAPE] sobre por qué esto no verifica nada. */
    fun looksLikeToken(candidate: String?): Boolean {
        val trimmed = candidate?.trim().orEmpty()
        return TOKEN_SHAPE.matches(trimmed)
    }
}
