package com.untar.ultimusic.model

import com.untar.ultimusic.util.SortableLibraryItem

/**
 * Resumen de una lista de reproducción para pintarla en la pestaña de Listas: su nombre y, como
 * información secundaria, cuántas canciones tiene y cuánto dura en total. No lleva las canciones en
 * sí (eso se resuelve al abrirla); solo lo justo para la fila de la lista.
 */
data class PlaylistSummary(
    val name: String,
    val songCount: Int,
    val totalDuration: Long
) : SortableLibraryItem {
    override val sortName: String get() = name
    override val sortDuration: Long get() = totalDuration
    override val sortYear: Int? get() = null
    override val sortPopularity: Long? get() = null
    override val sortSongCount: Int get() = songCount
}
