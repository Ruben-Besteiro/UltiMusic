package com.untarlamanteca.ultimusic.model

data class Album(
    val id: Long = 0,
    val title: String,
    val artists: List<Artist>,
    val year: Int?,
    val genres: List<String>,
    val imageName: String?
)