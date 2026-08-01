package com.untar.ultimusic.util

import android.content.Context

/**
 * Preferencia de "Ajustes del reproductor de vídeo" del iPod (ver `IPodNanoDialogFragment`): qué se
 * ve al entrar en modo vídeo con la canción ya pausada. De momento es la única opción de ese diálogo.
 */
object VideoModeSettings {

    private const val PREFS_NAME = "video_mode_settings"
    private const val KEY_SHOW_THUMBNAIL_WHEN_PAUSED = "show_thumbnail_when_paused"

    /**
     * True (por defecto): se ve la miniatura oficial del vídeo, sin el HUD de YouTube. False: se ve
     * el propio vídeo congelado en el fotograma donde arranca, con su HUD (título, botón de
     * reanudar, logo…), tal como se veía antes de existir la miniatura.
     */
    fun showThumbnailWhenPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_THUMBNAIL_WHEN_PAUSED, true)

    fun setShowThumbnailWhenPaused(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_THUMBNAIL_WHEN_PAUSED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
