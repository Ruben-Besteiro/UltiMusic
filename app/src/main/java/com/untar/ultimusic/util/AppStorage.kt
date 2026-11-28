package com.untar.ultimusic.util

import android.content.Context
import java.io.File

/**
 * Carpetas de datos propios de la app, todas en el almacenamiento INTERNO (`/data/data/<paquete>/
 * files/...`): no necesitan ningún permiso, así que el primer arranque ya no tiene que pedir una
 * carpeta `UltiMusic` (antes todo esto vivía en `~/UltiMusic` vía SAF, ver [SafStorage]).
 *
 * Se borran al desinstalar. Lo que sobrevive a una reinstalación o un cambio de móvil es:
 *  - la copia automática de Android (Auto Backup, ver `res/xml/data_extraction_rules.xml`): todo
 *    salvo [IMAGES], [BACKGROUNDS] y [CRASH_LOGS] (el límite de la nube es de 25 MB);
 *  - el `.zip` de Ajustes > Copia de seguridad (ver `BackupArchive`), con todo.
 *
 * Los nombres de carpeta son los mismos que en `~/UltiMusic`, así que la migración de quien
 * actualiza (ver `LibraryRepository.migrateLegacyUltiMusicFolder`) es copiar carpeta a carpeta.
 */
object AppStorage {

    const val IMAGES = "images"
    const val BACKGROUNDS = "backgrounds"
    const val PLAYLISTS = "playlists"
    const val CONVERSIONS = "conversions"
    const val TRANSLATIONS = "translations"
    const val CRASH_LOGS = "crash-logs"

    /** Carpetas que copia la migración desde `~/UltiMusic` (los fondos ya vivían en `files/`). */
    val LEGACY_FOLDERS = listOf(IMAGES, PLAYLISTS, CONVERSIONS, TRANSLATIONS)

    /** Carpetas que van en el `.zip` de exportar/importar. */
    val ARCHIVED_FOLDERS = listOf(IMAGES, BACKGROUNDS, PLAYLISTS, CONVERSIONS, TRANSLATIONS, CRASH_LOGS)

    /** `files/<name>`, creada si no existía. */
    fun dir(context: Context, name: String): File =
        File(context.applicationContext.filesDir, name).apply { if (!isDirectory) mkdirs() }

    fun imagesDir(context: Context): File = dir(context, IMAGES)

    /** Sufijo de los temporales de [tempFileIn]: `BackupArchive` no los mete en el `.zip`. */
    const val TEMP_SUFFIX = ".tmp"

    /**
     * Un temporal en [dir] para escribir y luego renombrar al nombre bueno. Con nombre CORTO propio,
     * no `"<nombre>.tmp"`: Android limita cada nombre de archivo a 255 bytes, y un nombre largo de
     * canción (p. ej. una conversión de letra con muchos "feat.") que cabe justo dejaría de caber
     * con el sufijo, y la escritura fallaría siempre.
     */
    fun tempFileIn(dir: File): File = File(dir, "${java.util.UUID.randomUUID()}$TEMP_SUFFIX")

    /** Borra los temporales que dejara un proceso muerto a mitad de escritura (ver [tempFileIn]). */
    fun deleteStaleTempFiles(context: Context) {
        for (name in ARCHIVED_FOLDERS) {
            File(context.applicationContext.filesDir, name).listFiles()
                ?.filter { it.isFile && (it.name.endsWith(TEMP_SUFFIX) || it.name.endsWith(".migrating")) }
                ?.forEach { it.delete() }
        }
    }
}
