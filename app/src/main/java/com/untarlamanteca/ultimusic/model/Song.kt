package com.untarlamanteca.ultimusic.model

data class Song(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artists: List<Artist>,
    val albums: List<Album>,
    val duration: Long,
    val year: Int?,
    val genres: List<String>,
    val imageName: String?,
    val comment: String?,
    val producer: String?,
    
    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
