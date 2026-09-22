package com.untar.ultimusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Umbral de canciones para agrupar artistas pequeños bajo un perfil "Otros" (ajustes visuales, ver
 * `SettingsDialogFragment`): los artistas con MENOS canciones que este número dejan de listarse
 * sueltos en la pestaña Artistas, y sus canciones se cuentan dentro de "Otros" (ver
 * [com.untar.ultimusic.data.LibraryRepository.artists]).
 *
 * 0 lo desactiva -nadie se agrupa-, y es el valor por defecto: hay que escribir un número mayor en
 * Ajustes para activarlo, como pidió el usuario.
 *
 * [threshold] es un `StateFlow`, no una simple propiedad leída al vuelo, porque
 * [com.untar.ultimusic.data.LibraryRepository.artists] tiene que reaccionar a que el usuario cambie
 * el número EN CALIENTE (regla del proyecto: una edición se ve al instante en toda la aplicación),
 * no solo la próxima vez que se entre en la pestaña.
 */
object ArtistGroupingPreferences {

    private const val PREFS_NAME = "artist_grouping"
    private const val KEY_THRESHOLD = "threshold"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _threshold = MutableStateFlow(0)
    val threshold: StateFlow<Int> = _threshold

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de stores y preferences. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _threshold.value = p.getInt(KEY_THRESHOLD, 0).coerceAtLeast(0)
        }
    }

    fun set(value: Int) {
        val clamped = value.coerceAtLeast(0)
        _threshold.value = clamped
        prefs?.edit()?.putInt(KEY_THRESHOLD, clamped)?.apply()
    }
}
