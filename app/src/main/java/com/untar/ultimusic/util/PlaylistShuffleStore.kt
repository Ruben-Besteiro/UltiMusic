package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Si el modo aleatorio está activado para cada Lista (ver el botón nuevo de la barra de
 * `CollectionDetailDialogFragment` y `PlaybackService.buildListaQueue`, que lo lee para decidir el
 * orden base de la cola: el orden normal de la lista si está desactivado, recién barajado si está
 * activado).
 *
 * Calcado de [PlaylistResumeStore]/[PlaylistHistoryStore] -mismo patrón exacto: `SharedPreferences`
 * propio, una clave por nombre de lista, ganchos de `rename`/`clear`-, en su propio fichero de
 * preferencias porque es un dato de naturaleza distinta (un booleano que el usuario fija a mano, no
 * un valor ni un historial que la propia reproducción va actualizando sola).
 */
object PlaylistShuffleStore {

    private const val PREFS_NAME = "playlist_shuffle"

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

    /** Falso (orden normal de la lista) si nunca se tocó el toggle para [playlistName]. */
    fun isShuffleOn(playlistName: String): Boolean = prefs?.getBoolean(key(playlistName), false) ?: false

    fun setShuffleOn(playlistName: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(key(playlistName), enabled)?.apply()
    }

    /** Sigue al renombrado de una lista (ver `PlaylistsViewModel.rename`): no-op si no había nada
     *  guardado para el nombre antiguo. */
    fun rename(oldName: String, newName: String) {
        val p = prefs ?: return
        val oldKey = key(oldName)
        if (!p.contains(oldKey)) return
        val value = p.getBoolean(oldKey, false)
        p.edit().remove(oldKey).putBoolean(key(newName), value).apply()
    }

    /** Se llama al borrar una lista (ver `PlaylistsViewModel.delete`): sin esto, un nombre reciclado
     *  para una lista nueva heredaría el estado de barajado de la borrada. */
    fun clear(playlistName: String) {
        prefs?.edit()?.remove(key(playlistName))?.apply()
    }

    private fun key(playlistName: String) = "shuffle_$playlistName"
}
