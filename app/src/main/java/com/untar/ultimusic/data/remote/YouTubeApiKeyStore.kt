package com.untar.ultimusic.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.untar.ultimusic.BuildConfig

/**
 * Dónde vive la clave de la YouTube Data API v3, que a partir de ahora **pone cada usuario**, no la
 * compilación (ver [com.untar.ultimusic.ui.sort.YouTubeApiKeyDialogFragment], que es quien la pide).
 * Es el gemelo exacto de [GeniusTokenStore] —mismo problema de cuota compartida por clave, mismo
 * riesgo de clave extraíble del APK, misma solución—, así que ver allí la explicación larga del
 * porqué; aquí solo se repite lo que cambia.
 *
 * ## El respaldo de BuildConfig es SOLO de desarrollo
 *
 * Igual que en [GeniusTokenStore.developmentToken]: si no hay clave guardada se usa la de
 * `local.properties` (`youtube.api_key=...`), pero únicamente en compilaciones de depuración. En
 * release el campo llega vacío desde `app/build.gradle.kts`, y [shouldOfferSetup] se apoya en eso
 * para decidir si enseñar el diálogo, así que una clave incrustada en el APK publicado haría que no
 * le saliera a nadie.
 */
object YouTubeApiKeyStore {

    private const val PREFS_NAME = "youtube_api_key"
    private const val KEY_TOKEN = "api_key"
    private const val KEY_DECLINED = "setup_declined"

    /**
     * Las claves de la YouTube Data API v3 son 39 caracteres de `[A-Za-z0-9_-]` (siempre empiezan
     * por "AIza", pero eso no se exige aquí por si Google cambia el prefijo). El rango se deja
     * holgado, igual que en [GeniusTokenStore.TOKEN_SHAPE], por si cambia la longitud algún día: esto
     * solo descarta que lo pegado sea texto cualquiera, no verifica nada de verdad — eso lo hace
     * [YouTubeStatsApi.validateKey].
     */
    private val KEY_SHAPE = Regex("[A-Za-z0-9_-]{30,50}")

    @Volatile
    private var prefs: SharedPreferences? = null

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que a [GeniusTokenStore]. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** La clave a usar: la que puso el usuario o, si no hay, la de la compilación. Cadena vacía si no
     *  hay ninguna de las dos, que es lo que hace que [YouTubeStatsApi.isConfigured] valga false. */
    val key: String
        get() = sanitize(prefs?.getString(KEY_TOKEN, null)).ifEmpty { developmentKey() }

    private fun developmentKey(): String =
        if (BuildConfig.DEBUG) sanitize(BuildConfig.YOUTUBE_API_KEY) else ""

    /** Si el usuario ya puso su propia clave. Se mira ANTES de ofrecer el diálogo de configuración
     *  para no enseñárselo dos veces; distinto de [key], que incluye el respaldo de BuildConfig. */
    val hasUserKey: Boolean
        get() = sanitize(prefs?.getString(KEY_TOKEN, null)).isNotEmpty()

    /** Si el usuario ha rechazado expresamente configurar YouTube (botón "Salir" del diálogo). Ver
     *  [GeniusTokenStore.setupDeclined]: mismo motivo, la popularidad se queda sin ese criterio de
     *  orden y no se le vuelve a insistir. */
    val setupDeclined: Boolean
        get() = prefs?.getBoolean(KEY_DECLINED, false) == true

    /** Si toca ofrecer el diálogo de configuración: ni hay clave utilizable ni el usuario ha dicho
     *  que no. La usa [com.untar.ultimusic.ui.sort.SortDialogFragment] al elegir "Popularidad". */
    val shouldOfferSetup: Boolean
        get() = key.isEmpty() && !setupDeclined

    fun save(key: String) {
        prefs?.edit()?.putString(KEY_TOKEN, key.trim())?.putBoolean(KEY_DECLINED, false)?.apply()
    }

    fun decline() {
        prefs?.edit()?.putBoolean(KEY_DECLINED, true)?.apply()
    }

    /** Recorta y descarta lo que no tenga forma de clave, por el mismo motivo que
     *  [GeniusTokenStore.sanitize]: que la cabecera/parámetro de la petición nunca pueda salir mal
     *  formado. */
    private fun sanitize(candidate: String?): String {
        val trimmed = candidate?.trim().orEmpty()
        return if (KEY_SHAPE.matches(trimmed)) trimmed else ""
    }

    /** Si [candidate] tiene pinta de clave, para decidir si vale la pena validarla contra YouTube. */
    fun looksLikeKey(candidate: String?): Boolean {
        val trimmed = candidate?.trim().orEmpty()
        return KEY_SHAPE.matches(trimmed)
    }
}
