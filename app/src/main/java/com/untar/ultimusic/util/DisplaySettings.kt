package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ajustes "visuales" de la aplicación (ver `SettingsDialogFragment`), es decir, los que no son del
 * amplificador ni del ecualizador. De momento solo hay uno: si las carátulas (y el color dinámico
 * que sale de ellas) deben tirar siempre de la del ÁLBUM en vez de la de cada canción.
 *
 * Se inicializa desde [com.untar.ultimusic.UltiMusicApp], igual que [com.untar.ultimusic.data.remote.GeniusTokenStore],
 * porque [com.untar.ultimusic.util.CoverArt.cover] lo consulta de forma síncrona en cada carga de
 * carátula (viene del hilo de Coil, no puede esperar a una `Activity`).
 *
 * [useAlbumCoverAlways] se expone también como [StateFlow] para que la casilla de ajustes se pinte
 * ya marcada al abrir la pantalla sin tener que leer disco desde la vista.
 */
object DisplaySettings {

    private const val PREFS_NAME = "display_settings"
    private const val KEY_USE_ALBUM_COVER_ALWAYS = "use_album_cover_always"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _useAlbumCoverAlways = MutableStateFlow(false)
    val useAlbumCoverAlwaysFlow: StateFlow<Boolean> = _useAlbumCoverAlways

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que a [com.untar.ultimusic.data.remote.GeniusTokenStore] y por el mismo motivo. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _useAlbumCoverAlways.value = p.getBoolean(KEY_USE_ALBUM_COVER_ALWAYS, false)
        }
    }

    /** Lectura síncrona: la usa [com.untar.ultimusic.util.CoverArt.cover] en cada carátula que resuelve. */
    val useAlbumCoverAlways: Boolean
        get() = _useAlbumCoverAlways.value

    fun setUseAlbumCoverAlways(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_USE_ALBUM_COVER_ALWAYS, enabled)?.apply()
        _useAlbumCoverAlways.value = enabled
    }
}
