package com.untar.ultimusic.model

/** Una carpeta raíz adicional de la fonoteca (ajustes > Carpetas de la fonoteca), ver [com.untar.ultimusic.data.db.entities.LibraryRootEntity]. */
data class LibraryRoot(
    val path: String,
    /** La carpeta UltiMusic: siempre se lee, así que aparece en la lista pero no se puede quitar. */
    val isMain: Boolean = false
)
