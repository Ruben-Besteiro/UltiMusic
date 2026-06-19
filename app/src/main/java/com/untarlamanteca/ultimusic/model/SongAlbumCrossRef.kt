package com.untarlamanteca.ultimusic.model

data class SongAlbumCrossRef(
    val songId: Long,
    val albumId: Long,
    val trackNumber: Int?,
    val discNumber: Int?
)
