package com.untar.ultimusic.data.remote

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Cuándo tocó el último refresco de visitas de YouTube (ver [YouTubeStatsApi] y
 * [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue]), para no repetirlo más de
 * una vez al día aunque la aplicación se reabra varias veces en el mismo.
 *
 * Es un instante único por INSTALACIÓN, no uno por canción: guardar uno por canción sería más
 * preciso (una que estrena vídeo hoy no tendría que esperar al refresco de mañana), pero
 * multiplicaría las escrituras sin necesidad real, ya que las visitas de un vídeo no cambian tan
 * rápido como para que ese día de más se note.
 */
object YouTubeStatsRefresh {

    /**
     * Como mucho una vez al día, tal cual se pidió. Si la clave embebida algún día pasa a
     * compartirse entre todos los usuarios (ver [YouTubeApiKeyStore], hoy solo de desarrollo) y la
     * cuota empieza a notarse, basta con subir este número: no hace falta tocar nada más.
     */
    private val REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

    private const val PREFS_NAME = "youtube_stats"
    private const val KEY_LAST_REFRESH = "last_refresh_at"

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

    /** Si ha pasado el intervalo desde el último refresco, o si no se ha inicializado o refrescado
     *  todavía nunca (en cuyo caso también toca: es la primera vez que se abre la aplicación). */
    val isDue: Boolean
        get() = System.currentTimeMillis() - (prefs?.getLong(KEY_LAST_REFRESH, 0L) ?: 0L) >= REFRESH_INTERVAL_MS

    fun markRefreshed() {
        prefs?.edit()?.putLong(KEY_LAST_REFRESH, System.currentTimeMillis())?.apply()
    }
}
