package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Última canción reproducida DENTRO de cada Lista, para el bloque "Posición actual"/REANUDAR de su
 * ficha (ver `CollectionDetailDialogFragment`). Solo para Listas: las Etiquetas son agrupaciones, no
 * algo pensado para reproducirse en secuencia, así que no llevan este recuerdo.
 *
 * Guarda una clave por nombre de lista, a propósito distinto del `SharedPreferences("playback_state")`
 * de [com.untar.ultimusic.playback.PlaybackService]: ese es un snapshot GLOBAL de la única cola que
 * suena (se sobreescribe entero en cada canción, sea cual sea su contexto), mientras que aquí hace
 * falta recordar una lista aparte por CADA lista, independientemente de cuál esté sonando ahora
 * mismo. Solo se guarda el id de la canción, no el milisegundo exacto: REANUDAR reinicia esa canción
 * desde 0:00, no continúa en el segundo donde se dejó.
 */
object PlaylistResumeStore {

    private const val PREFS_NAME = "playlist_resume"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de *Store. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Se llama solo mientras la cola suena en el contexto de [playlistName] (ver
     *  `PlaybackService.loadCurrent`): reproducir esa misma canción desde cualquier otro sitio no
     *  toca este valor. */
    fun setLastSong(playlistName: String, songId: Long) {
        prefs?.edit()?.putLong(key(playlistName), songId)?.apply()
    }

    /** Null si nunca se guardó nada para esta lista. */
    fun getLastSongId(playlistName: String): Long? {
        val p = prefs ?: return null
        val key = key(playlistName)
        if (!p.contains(key)) return null
        return p.getLong(key, -1L).takeIf { it >= 0 }
    }

    /** Sigue al renombrado de una lista (ver `PlaylistsViewModel.rename`): no-op si no había nada
     *  guardado para el nombre antiguo. */
    fun rename(oldName: String, newName: String) {
        val p = prefs ?: return
        val oldKey = key(oldName)
        if (!p.contains(oldKey)) return
        val songId = p.getLong(oldKey, -1L)
        p.edit().remove(oldKey).putLong(key(newName), songId).apply()
    }

    /** Se llama al borrar una lista (ver `PlaylistsViewModel.delete`): sin esto, un nombre reciclado
     *  para una lista nueva heredaría el valor de la borrada. */
    fun clear(playlistName: String) {
        prefs?.edit()?.remove(key(playlistName))?.apply()
    }

    private fun key(playlistName: String) = "song_$playlistName"
}
