package com.untar.ultimusic.model

/**
 * Un candidato encontrado en lrclib.net para el buscador de letras del editor de metadatos (ver
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]). A diferencia de
 * [MetadataSuggestion] (que busca en MusicBrainz solo título/artista/álbum/año/portada), aquí la
 * búsqueda YA trae la letra completa: no hace falta una segunda petición al elegir un candidato.
 */
data class LyricsSuggestion(
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationMs: Long?,

    /** Letra con marcas de tiempo `[mm:ss.xx]`, lista para guardarse tal cual en `Song.lyrics`. Null
     * si lrclib.net no tiene una versión sincronizada de esta grabación (puede que solo tenga la
     * letra plana, o ninguna). */
    val syncedLyrics: String?,

    /** Letra sin sincronizar, de respaldo cuando no hay [syncedLyrics]. */
    val plainLyrics: String?
)
