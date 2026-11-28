package com.untar.ultimusic.model

/** Una carpeta raíz de la fonoteca (ajustes > Carpetas de la fonoteca), ver
 *  [com.untar.ultimusic.data.db.entities.LibraryRootEntity]. Desde que los datos de la app viven en
 *  almacenamiento interno, la antigua carpeta `UltiMusic` es una más, sin trato especial. */
data class LibraryRoot(
    val path: String
)
