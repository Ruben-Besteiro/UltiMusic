package com.untar.ultimusic.model

data class Song(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artists: List<Artist>,
    val albums: List<Album>,
    val producers: List<Producer>,
    val duration: Long,
    val year: Int?,
    val genres: List<String>,
    val lyrics: String?,
    val language: String?,
    val imageName: String?,
    val comment: String?,

    /** Enlace de YouTube del videoclip, siempre puesto por el usuario. Null si aún no tiene. */
    val videoUrl: String?,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
