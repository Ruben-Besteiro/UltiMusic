package com.untar.ultimusic.model

/**
 * Un candidato encontrado en lrclib.net para el buscador de letras del editor de metadatos (ver
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]). A diferencia de
 * [MetadataSuggestion] (que busca en iTunes título/artista/álbum/año/género/portada), aquí lo que
 * se busca es el texto de la letra en sí, no los datos de la publicación.
 */
data class LyricsSuggestion(
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationMs: Long?,

    /** Si esta grabación dura lo mismo que el archivo que se está editando (ver
     * [com.untar.ultimusic.data.remote.LrcLibApi.search], que es quien lo decide). Cuando NO
     * coincide, el audio del usuario viene de otra fuente y lo más probable es que la letra
     * sincronizada vaya desfasada, así que estos candidatos se marcan y suben en la lista. */
    val durationMatches: Boolean,

    /** Letra con marcas de tiempo `[mm:ss.xx]`, lista para guardarse tal cual en `Song.lyrics`. Null
     * si lrclib.net no tiene una versión sincronizada de esta grabación (puede que solo tenga la
     * letra plana, o ninguna). */
    val syncedLyrics: String?,

    /** Letra sin sincronizar, de respaldo cuando no hay [syncedLyrics]. */
    val plainLyrics: String?
)
