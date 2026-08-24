package com.untar.ultimusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ajustes "visuales" que son un simple interruptor on/off, sin ficha propia como las carpetas de la
 * fonoteca o la lista gris (ver SettingsDialogFragment). A diferencia de [SortPreferences] -que solo
 * se LEE al construir cada ViewModel, porque nada más lo necesita mientras esa pantalla está abierta-
 * aquí hace falta un [StateFlow] de verdad: la pestaña Canciones tiene que enterarse al instante de
 * un cambio hecho en Ajustes sin cerrar y reabrir nada (ver CLAUDE.md).
 */
object VisualPreferences {

    private const val PREFS_NAME = "visual_settings"
    private const val KEY_SHOW_SONG_TAGS = "show_song_tags"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _showSongTags = MutableStateFlow(false)

    /** "Ver etiquetas en pestaña Canciones" (ver dialog_settings.xml). Desactivada por defecto. */
    val showSongTags: StateFlow<Boolean> = _showSongTags.asStateFlow()

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que el resto de *Store y *Preferences. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _showSongTags.value = p.getBoolean(KEY_SHOW_SONG_TAGS, false)
        }
    }

    fun setShowSongTags(enabled: Boolean) {
        _showSongTags.value = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_SONG_TAGS, enabled)?.apply()
    }
}
