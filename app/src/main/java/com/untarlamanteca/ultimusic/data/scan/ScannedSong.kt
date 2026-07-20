package com.untarlamanteca.ultimusic.data.scan

/**
 * Estos son los metadatos que el [MusicScanner] lee y entrega a la capa de persistencia para reconciliar.
 * Los campos de artista/álbum son anulables (pueden faltar en las etiquetas); la capa de datos
 * decide el valor por defecto ("desconocido") al crear las entidades.
 *
 * Esto NO ES MEDIASTORE. Esto pertenece al archivo, mientras que MediaStore es una "copia" que pertenece al SO
 */

data class ScannedSong(
    val filePath: String,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genres: List<String>,
    val year: Int?,
    val duration: Long,
    val producer: String?,
    val trackNumber: Int?,
    val discNumber: Int?
)
