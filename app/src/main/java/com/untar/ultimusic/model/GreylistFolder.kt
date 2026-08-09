package com.untar.ultimusic.model

/** Una subcarpeta de la lista gris. [excluded] es el estado del switch: ver [com.untar.ultimusic.data.db.entities.GreylistFolderEntity]. */
data class GreylistFolder(
    val path: String,
    val excluded: Boolean
)
