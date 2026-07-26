package com.untarlamanteca.ultimusic.util

/** Formateo de tiempos de reproducción. */
object TimeFormat {

    /** Convierte milisegundos a texto `m:ss` (p.ej. 204000 -> "3:24"). */
    fun mmss(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Igual que [mmss], pero añadiendo las horas cuando las hay (`1:04:22`). Se usa para duraciones
     * ACUMULADAS —lo que dura un álbum entero o toda la discografía de un artista—, donde pasar de
     * una hora es lo normal y "78:15" no se entendería de un vistazo.
     */
    fun hhmmss(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}
