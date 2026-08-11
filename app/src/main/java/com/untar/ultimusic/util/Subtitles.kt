package com.untar.ultimusic.util

/**
 * Une trozos de un subtítulo tipo "Artista | Álbum" saltándose los que estén vacíos, para que una
 * canción sin artista o sin álbum (ver [com.untar.ultimusic.data.scan.MusicScanner.UNKNOWN_ARTIST])
 * no deje un separador colgando (" | Álbum" en vez de "Álbum").
 */
fun joinNonBlank(vararg parts: String?, separator: String = " | "): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(separator)
