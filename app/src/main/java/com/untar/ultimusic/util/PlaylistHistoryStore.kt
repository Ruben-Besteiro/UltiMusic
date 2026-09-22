package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Canciones ya reproducidas dentro de cada Lista, para el ciclo "sin repeticiones" descrito en su
 * placeholder (ver `R.string.no_playlists` y `PlaybackService.buildListaQueue`): mientras suena una
 * Lista, esta se acuerda de cuáles ya sonaron esta vuelta, sin duplicados, y se vacía sola en cuanto
 * ha sonado ya toda la Lista (ver [recordPlayed]).
 *
 * Calcado de [PlaylistResumeStore] -mismo patrón exacto: `SharedPreferences` propio, una clave por
 * nombre de lista, ganchos de `rename`/`clear`-, pero a propósito en un fichero de preferencias
 * aparte: es un dato de naturaleza distinta (un historial que crece y se recicla, no un único valor
 * que se sobrescribe) y conviene poder borrar uno sin arrastrar el otro.
 *
 * Se graba desde `PlaybackService.loadCurrent`, el único punto por el que pasa CUALQUIER cambio de
 * canción actual: cubre tanto tocar una canción a mano (ficha de la lista o cola del iPod) como el
 * avance automático (`next()`/`skipToNext`), así que el historial refleja de verdad toda una vuelta de
 * escucha, no solo los toques manuales.
 */
object PlaylistHistoryStore {

    private const val PREFS_NAME = "playlist_history"

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

    /** Ids ya reproducidos por toque en [playlistName] esta vuelta, en orden (el más antiguo primero),
     *  sin duplicados. Vacío si nunca se guardó nada, o si la vuelta ya se completó y se reinició. */
    fun getHistory(playlistName: String): List<Long> {
        val raw = prefs?.getString(key(playlistName), null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    /**
     * Anota que [songId] se acaba de tocar dentro de [playlistName] (que tiene [totalSongs] canciones
     * en total): si ya estaba en el historial, la mueve al final en vez de duplicarla; si no, la añade
     * al final. Si al hacerlo el historial llega a tener tantas canciones como la lista, se vacía
     * entero (empieza una vuelta nueva).
     */
    fun recordPlayed(playlistName: String, songId: Long, totalSongs: Int) {
        val history = getHistory(playlistName).toMutableList()
        history.remove(songId)
        history.add(songId)
        setHistory(playlistName, if (history.size >= totalSongs) emptyList() else history)
    }

    /** Sigue al renombrado de una lista (ver `PlaylistsViewModel.rename`): no-op si no había nada
     *  guardado para el nombre antiguo. */
    fun rename(oldName: String, newName: String) {
        val p = prefs ?: return
        val oldKey = key(oldName)
        val raw = p.getString(oldKey, null) ?: return
        p.edit().remove(oldKey).putString(key(newName), raw).apply()
    }

    /** Se llama al borrar una lista (ver `PlaylistsViewModel.delete`): sin esto, un nombre reciclado
     *  para una lista nueva heredaría el historial de la borrada. */
    fun clear(playlistName: String) {
        prefs?.edit()?.remove(key(playlistName))?.apply()
    }

    private fun setHistory(playlistName: String, ids: List<Long>) {
        prefs?.edit()?.putString(key(playlistName), ids.joinToString(","))?.apply()
    }

    private fun key(playlistName: String) = "history_$playlistName"
}
