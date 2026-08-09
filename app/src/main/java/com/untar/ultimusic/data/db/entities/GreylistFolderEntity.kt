package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Fila de una subcarpeta de la lista gris (ajustes > Lista gris). [path] es la ruta absoluta de la
 * subcarpeta, elegida con el explorador propio de la app, y sirve de clave: no tiene sentido añadir
 * la misma carpeta dos veces.
 *
 * [excluded] es lo que marca el switch de esa fila: `true` (switch encendido) significa que la
 * carpeta está fuera de la biblioteca y sus canciones se ocultan (ver [SongEntity.hiddenByGreylist])
 * sin borrarse; `false` (switch apagado) significa que cuenta con normalidad. Nace en `false`: añadir
 * una carpeta no la oculta de golpe, el switch es para excluirla después.
 */
@Entity(tableName = "greylist_folders")
data class GreylistFolderEntity(
    @PrimaryKey val path: String,
    val excluded: Boolean = false
)
