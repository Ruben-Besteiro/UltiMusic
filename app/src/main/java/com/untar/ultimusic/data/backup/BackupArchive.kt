package com.untar.ultimusic.data.backup

import android.content.Context
import android.net.Uri
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.util.AppStorage
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Ajustes > Copia de seguridad: un `.zip` con TODOS los datos de la app, carátulas incluidas (la
 * copia automática de Android no las lleva, ver `res/xml/data_extraction_rules.xml`).
 *
 * Contenido:
 *  - `manifest.json`: formato y versión del esquema de la BD, para rechazar una copia de una
 *    versión FUTURA de la app (Room la abriría con `fallbackToDestructiveMigration` y vaciaría la
 *    biblioteca). Una de una versión anterior sí vale: Room la migra al abrirla.
 *  - `databases/ultimusic.db`, tras pasar el WAL al archivo principal.
 *  - `files/<carpeta>/...` de [AppStorage.ARCHIVED_FOLDERS].
 *  - `prefs.json`: todas las SharedPreferences salvo [SafStorage.PREFS_NAME] (permisos de carpeta y
 *    marcas que solo valen en esta instalación), con el tipo de cada valor.
 *
 * Importar SUSTITUYE todo y reinicia la app: la BD entra como
 * [UltiMusicDatabase.STAGED_RESTORE_NAME] en el siguiente arranque (la actual está abierta), y las
 * carpetas que traiga a medias no existen: una carpeta que no venga en el `.zip` queda vacía.
 * Las carpetas de música no van dentro (son del usuario, no de la app): tras importar en otro
 * móvil hay que volver a elegirlas, y sus canciones se vuelven a enlazar solas.
 */
object BackupArchive {

    private const val FORMAT = 1
    private const val MANIFEST = "manifest.json"
    private const val PREFS = "prefs.json"
    private const val DB_ENTRY = "databases/${UltiMusicDatabase.DB_NAME}"
    private const val FILES_PREFIX = "files/"

    enum class ImportResult { OK, INVALID, TOO_NEW }

    /** Nombre propuesto para el archivo, p. ej. `UltiMusic-2026-11-27.zip`. */
    fun suggestedName(): String =
        "UltiMusic-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    // NonCancellable: cerrar Ajustes a mitad no puede dejar un .zip cortado ni una importación aplicada
    // a medias.
    suspend fun export(context: Context, target: Uri): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val app = context.applicationContext
        runCatching {
            val db = UltiMusicDatabase.get(app)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val dbVersion = db.openHelper.readableDatabase.version
            val dbFile = app.getDatabasePath(UltiMusicDatabase.DB_NAME)

            val out = runCatching { app.contentResolver.openOutputStream(target, "wt") }.getOrNull()
                ?: app.contentResolver.openOutputStream(target)
                ?: error("No se puede escribir en el destino")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest(app, dbVersion).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(DB_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                for (folder in AppStorage.ARCHIVED_FOLDERS) {
                    val dir = File(app.filesDir, folder)
                    dir.walkTopDown().filter { it.isFile && !it.name.endsWith(AppStorage.TEMP_SUFFIX) }.forEach { file ->
                        val relative = file.relativeTo(app.filesDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(FILES_PREFIX + relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                zip.putNextEntry(ZipEntry(PREFS))
                zip.write(exportPrefs(app).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.isSuccess
    }

    /**
     * Valida y aplica el `.zip`. Con [ImportResult.OK] quien llama debe reiniciar el proceso cuanto
     * antes: la BD nueva solo entra en el siguiente arranque, y hasta entonces la app enseña la vieja
     * con las carátulas nuevas.
     */
    suspend fun import(context: Context, source: Uri): ImportResult = withContext(Dispatchers.IO + NonCancellable) {
        val app = context.applicationContext
        val staging = File(app.cacheDir, "backup-import").apply { deleteRecursively(); mkdirs() }
        try {
            // 1. Extraer entero a un temporal, sin tocar nada todavía.
            val extracted = runCatching {
                val input = app.contentResolver.openInputStream(source) ?: error("ilegible")
                ZipInputStream(input.buffered()).use { zip ->
                    val root = staging.canonicalPath + File.separator
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val dest = File(staging, entry.name)
                        // Zip-slip: nada puede salir de la carpeta temporal ("../../algo").
                        if (!dest.canonicalPath.startsWith(root)) error("ruta no válida: ${entry.name}")
                        if (entry.isDirectory) { dest.mkdirs(); continue }
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                    }
                }
            }.isSuccess
            if (!extracted) return@withContext ImportResult.INVALID

            // 2. Validar: manifiesto conocido y BD de esta versión de la app o de una anterior.
            val manifest = runCatching { JSONObject(File(staging, MANIFEST).readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@withContext ImportResult.INVALID
            if (manifest.optInt("format", -1) != FORMAT) return@withContext ImportResult.INVALID
            val stagedDb = File(staging, DB_ENTRY)
            if (!stagedDb.isFile) return@withContext ImportResult.INVALID
            val backupVersion = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    stagedDb.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { it.version }
            }.getOrNull() ?: return@withContext ImportResult.INVALID
            val liveVersion = UltiMusicDatabase.get(app).openHelper.readableDatabase.version
            if (backupVersion > liveVersion) return@withContext ImportResult.TOO_NEW
            val prefs = File(staging, PREFS).takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull() }

            // 3. Aplicar. La BD, preparada para el siguiente arranque (ver UltiMusicDatabase).
            val dbFile = app.getDatabasePath(UltiMusicDatabase.DB_NAME)
            val restore = File(dbFile.parentFile, UltiMusicDatabase.STAGED_RESTORE_NAME)
            restore.delete()
            if (!stagedDb.renameTo(restore)) {
                stagedDb.copyTo(restore, overwrite = true)
            }
            // Las carpetas, sustituidas enteras.
            for (folder in AppStorage.ARCHIVED_FOLDERS) {
                val live = File(app.filesDir, folder)
                live.deleteRecursively()
                val incoming = File(staging, FILES_PREFIX + folder)
                if (incoming.isDirectory && !incoming.renameTo(live)) incoming.copyRecursively(live, overwrite = true)
            }
            prefs?.let { importPrefs(app, it) }
            ImportResult.OK
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun manifest(context: Context, dbVersion: Int): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("dbVersion", dbVersion)
        put("createdAt", System.currentTimeMillis())
        runCatching {
            put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        }
    }

    // --- SharedPreferences, con su tipo (JSON no distingue Int de Long ni de Float) ---

    /** Los archivos de prefs de la propia app. Fuera: [SafStorage.PREFS_NAME] (ver la cabecera) y
     *  los de librerías (`androidx.*`, `com.google.*`), que no son datos del usuario. */
    private fun prefsNames(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { it != SafStorage.PREFS_NAME && !it.startsWith("androidx.") && !it.startsWith("com.google.") }
    }

    private fun exportPrefs(context: Context): JSONObject {
        val all = JSONObject()
        for (name in prefsNames(context)) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val typed = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                values.put(key, typed)
            }
            all.put(name, values)
        }
        return all
    }

    /** Sustituye cada archivo de prefs que venga en [all] por su contenido. `commit` (no `apply`):
     *  justo después se mata el proceso. */
    private fun importPrefs(context: Context, all: JSONObject) {
        for (name in all.keys()) {
            if (name == SafStorage.PREFS_NAME) continue
            val values = all.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) {
                val typed = values.optJSONObject(key) ?: continue
                when (typed.optString("t")) {
                    "s" -> editor.putString(key, typed.optString("v"))
                    "i" -> editor.putInt(key, typed.optInt("v"))
                    "l" -> editor.putLong(key, typed.optLong("v"))
                    "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                    "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                    "ss" -> {
                        val array = typed.optJSONArray("v") ?: JSONArray()
                        editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                    }
                }
            }
            editor.commit()
        }
    }
}
