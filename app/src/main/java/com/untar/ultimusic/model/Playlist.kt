package com.untar.ultimusic.model

/**
 * Resumen de una lista de reproducción para pintarla en la pestaña de Playlists: su nombre y, como
 * información secundaria, cuántas canciones tiene y cuánto dura en total. No lleva las canciones en
 * sí (eso se resuelve al abrirla); solo lo justo para la fila de la lista.
 */
data class PlaylistSummary(
    val name: String,
    val songCount: Int,
    val totalDuration: Long
)
