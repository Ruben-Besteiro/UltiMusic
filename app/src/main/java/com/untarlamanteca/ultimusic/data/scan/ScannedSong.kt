package com.untarlamanteca.ultimusic.data.scan

/**
 * Resultado crudo de escanear un archivo de audio: solo las etiquetas leídas, sin IDs ni
 * entidades. Es lo que el [MusicScanner] entrega a la capa de persistencia para reconciliar.
 * Los campos de artista/álbum son anulables (pueden faltar en las etiquetas); la capa de datos
 * decide el valor por defecto ("desconocido") al crear las entidades.
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
