package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Fila de una carpeta raíz adicional de la fonoteca (ajustes > Carpetas de la fonoteca). [path] es
 * la ruta absoluta de la carpeta, elegida con el explorador propio de la app desde el almacenamiento
 * del dispositivo (no solo dentro de `UltiMusic`), y sirve de clave: no tiene sentido añadir la misma
 * carpeta dos veces.
 *
 * A diferencia de [GreylistFolderEntity], no tiene un `excluded`: una carpeta raíz está dentro de la
 * biblioteca o no lo está, no hay estado intermedio. `UltiMusic` sigue siendo la raíz por defecto y
 * no tiene fila aquí: esta tabla solo guarda las raíces ADICIONALES (ver
 * [com.untar.ultimusic.data.scan.MusicScanner.scanRoots]).
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey val path: String
)
