package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Fila de una carpeta raíz adicional de la fonoteca (ajustes > Carpetas de la fonoteca). Desde la
 * migración a Storage Access Framework (ver [com.untar.ultimusic.util.SafStorage] y
 * `MIGRATION_29_30` en `Migrations.kt`), [path] YA NO es una ruta absoluta: es el `content://` de
 * árbol que el usuario concedió con el selector del sistema (`ACTION_OPEN_DOCUMENT_TREE`), en texto
 * (`Uri.toString()`). Sirve de clave igual que antes: no tiene sentido conceder la misma carpeta dos
 * veces. Elegir una carpeta NUEVA siempre pasa por ese selector — no hay forma de "sembrar" una fila
 * aquí sin que el usuario interactúe con él, a diferencia del viejo `seedDefaultLibraryRoots`.
 *
 * A diferencia de [GreylistFolderEntity], no tiene un `excluded`: una carpeta raíz está dentro de la
 * biblioteca o no lo está, no hay estado intermedio. `UltiMusic` sigue siendo la raíz por defecto y
 * no tiene fila aquí: su URI de árbol vive aparte, en [com.untar.ultimusic.util.SafStorage] (hace
 * falta antes de que exista Room, ver `UltiMusicDatabase.restoreFromBackupIfNeeded`). Esta tabla solo
 * guarda las raíces ADICIONALES (ver [com.untar.ultimusic.data.scan.MusicScanner]).
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey val path: String
)
