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
}
