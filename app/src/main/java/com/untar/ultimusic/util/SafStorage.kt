package com.untar.ultimusic.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Acceso a almacenamiento vía Storage Access Framework (SAF), que sustituye a
 * `MANAGE_EXTERNAL_STORAGE`: el usuario concede cada carpeta raíz con el selector del sistema
 * (`ACTION_OPEN_DOCUMENT_TREE`) y la app guarda ese permiso, sin acceso libre al resto del
 * dispositivo (ver CLAUDE.md/memoria sobre por qué: Google Play rechaza ese permiso para un
 * reproductor).
 *
 * La clave de identidad de un archivo/carpeta en toda la app, de aquí en adelante, es su RUTA
 * RELATIVA AL VOLUMEN ("docPath"), p. ej. `"UltiMusic/Bootlegs/track.mp3"`: es justo la parte que
 * va detrás de `"primary:"` en el id de documento del proveedor estándar de almacenamiento externo
 * de Android (`com.android.externalstorage.documents`, el que usa prácticamente todo dispositivo).
 * Se usa esto en vez del `content://` en crudo por dos motivos:
 *  - Conserva los mismos separadores `/` de siempre, así que [com.untar.ultimusic.data.db.LibraryDao]
 *    puede seguir haciendo `substringAfterLast('/')`/`startsWith(prefix + "/")` (emparejar canciones
 *    movidas, prefijo de la lista gris) sin cambiar esa lógica: un `content://` en crudo NO sirve
 *    para esto, porque el id de documento va codificado como un único segmento opaco (`%2F` en vez
 *    de `/`).
 *  - Es comparable entre distintas concesiones: la misma canción tiene el mismo docPath la conceda
 *    el usuario como parte del árbol `UltiMusic` o como parte de uno más amplio, lo que hace posible
 *    el re-enlace por ruta relativa tras la migración (ver [docPathOf]).
 *
 * Solo se soporta el volumen de almacenamiento interno principal (`primary`), igual que el código
 * de antes de esta migración ([android.os.Environment.getExternalStorageDirectory] tampoco cubría
 * una tarjeta SD aparte): no es una limitación nueva, es la misma de siempre con otro mecanismo.
 */
object SafStorage {

    private const val PREFS_NAME = "saf_storage"
    private const val KEY_ULTIMUSIC_TREE_URI = "ultimusic_tree_uri"
    private const val KEY_OFFERED_LEGACY_RELINK = "offered_legacy_relink"
    private const val VOLUME_PRIMARY = "primary"
    private const val AUTHORITY_EXTERNAL = "com.android.externalstorage.documents"

    /** Una fila de [listChildren]: ya trae su propio docPath, no hace falta reconstruirlo. */
    data class SafEntry(val docPath: String, val name: String, val isDirectory: Boolean, val lastModified: Long)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- La carpeta UltiMusic: el único root que hace falta ANTES de que exista Room (ver
    // UltiMusicDatabase.restoreFromBackupIfNeeded), así que vive en SharedPreferences y no en la BD. ---

    fun ultiMusicTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_ULTIMUSIC_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * El docPath de la propia carpeta `UltiMusic` concedida (p. ej. `"UltiMusic"` en el caso normal,
     * pero puede ser cualquier otra cosa si el usuario eligió/creó una carpeta con otro nombre o en
     * otro sitio del selector: nunca se asume el literal `"UltiMusic/..."` en ningún sitio de la app,
     * siempre se compone a partir de esto). Null si todavía no se ha concedido ninguna.
     */
    fun ultiMusicDocPath(context: Context): String? = ultiMusicTreeUri(context)?.let { treeDocPath(it) }

    /** Concede permiso persistente sobre [uri] y la recuerda como raíz de `UltiMusic`. */
    fun setUltiMusicTreeUri(context: Context, uri: Uri) {
        takePersistablePermission(context, uri)
        prefs(context).edit().putString(KEY_ULTIMUSIC_TREE_URI, uri.toString()).apply()
        ensureUltiMusicRegistered(context)
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** Contraparte de [takePersistablePermission]: la llama `LibraryRepository.removeLibraryRoot` al
     *  quitar una raíz adicional. Android solo permite ~128 permisos persistidos por app a la vez;
     *  sin soltar los que ya no hacen falta, añadir y quitar carpetas repetidamente acabaría topando
     *  ese límite. */
    fun releasePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** ¿Sigue vigente el permiso concedido sobre [treeUri]? (el usuario puede haberlo revocado desde
     *  Ajustes del sistema, o el proceso puede haber sobrevivido a una reinstalación a medias). */
    fun isRootReadable(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** ¿Ya se le ha ofrecido al usuario, alguna vez, re-conceder Download/Music/sus raíces propias
     *  tras la migración a SAF (ver `MainActivity`)? Solo se ofrece una vez: quien lo salte siempre
     *  puede añadir una carpeta luego a mano desde Ajustes con el mismo selector. */
    fun hasOfferedLegacyRootsRelink(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OFFERED_LEGACY_RELINK, false)

    fun markOfferedLegacyRootsRelink(context: Context) {
        prefs(context).edit().putBoolean(KEY_OFFERED_LEGACY_RELINK, true).apply()
    }

    // --- Registro en memoria: prefijo de docPath ("UltiMusic", "Download"...) -> URI de árbol
    // concedida. Se reconstruye cada vez que cambian las raíces (ver LibraryRepository). ---

    @Volatile
    private var registry: Map<String, Uri> = emptyMap()

    /**
     * Reemplaza el registro entero. Solo debe llamarla quien conozca TODAS las raíces adicionales de
     * verdad ([com.untar.ultimusic.data.LibraryRepository], que las lee de `library_roots`): con
     * [extraRootUriStrings] vacío (el valor por defecto) borraría de un plumazo cualquier raíz extra
     * que otro consumidor hubiera registrado antes. Para solo asegurar que `UltiMusic` está en el
     * registro sin tocar lo demás, ver [ensureUltiMusicRegistered].
     */
    fun refreshRegistry(context: Context, extraRootUriStrings: List<String> = emptyList()) {
        val trees = listOfNotNull(ultiMusicTreeUri(context)) +
            extraRootUriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        registry = trees.associateBy { treeDocPath(it) }
    }

    /**
     * Versión ligera de [refreshRegistry] que solo (re)confirma la entrada de `UltiMusic` en el
     * registro, SIN tocar las raíces adicionales que ya hubiera. La llaman consumidores que solo
     * necesitan resolver rutas dentro de `UltiMusic` y no conocen las raíces adicionales de Room
     * ([com.untar.ultimusic.data.playlist.PlaylistRepository], [CoverArt], la copia de seguridad de
     * la BD): así funcionan aunque
     * [com.untar.ultimusic.data.LibraryRepository.reconcile] (quien sí hace el [refreshRegistry]
     * completo) todavía no haya llegado a ejecutarse en este arranque.
     */
    fun ensureUltiMusicRegistered(context: Context) {
        val treeUri = ultiMusicTreeUri(context) ?: return
        val docPath = treeDocPath(treeUri)
        if (registry[docPath] == treeUri) return
        registry = registry + (docPath to treeUri)
    }

    /** Las raíces concedidas ahora mismo, tal cual están en el registro (para escanear/vigilar). Hay
     *  que llamar a [refreshRegistry] antes si pudiera haber cambiado desde la última vez. */
    fun grantedRoots(): List<Pair<String, Uri>> = registry.toList()

    private fun treeDocPath(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return ""
        return docId.removePrefix("$VOLUME_PRIMARY:")
    }

    /** Versión pública de [treeDocPath], para mostrar un nombre legible de una raíz concedida (ver
     *  `LibraryRootAdapter`) o para detectar solapes al añadir una raíz nueva (ver
     *  `SettingsViewModel.tryAddLibraryRoot`). Null si [treeUri] no es del proveedor estándar. */
    fun docPathOfTree(treeUri: Uri): String? = treeDocPath(treeUri).takeIf { it.isNotEmpty() }

    /**
     * Ruta relativa al volumen (p. ej. `"UltiMusic/Bootlegs/track.mp3"`) a partir de un `content://`
     * del proveedor estándar de almacenamiento externo, sea de árbol o de documento suelto. Null si
     * el proveedor/volumen no es el estándar (tarjeta SD, proveedor de otra app...): esas rutas no se
     * pueden re-enlazar por este camino, ver la cabecera de este objeto.
     */
    fun docPathOf(uri: Uri): String? {
        if (uri.authority != AUTHORITY_EXTERNAL) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2 || !parts[0].equals(VOLUME_PRIMARY, ignoreCase = true)) return null
        return parts[1]
    }

    /**
     * Reconstruye un URI abrible a partir de una ruta relativa ya conocida (canción catalogada,
     * carátula...), buscando en el registro qué árbol concedido la cubre. Null si ninguno la cubre
     * todavía (carpeta pendiente de re-conceder tras la migración, o quitada por el usuario).
     */
    fun resolveUri(docPath: String): Uri? {
        val (prefix, treeUri) = registry.entries.firstOrNull { (prefix, _) ->
            docPath == prefix || docPath.startsWith("$prefix/")
        } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, "$VOLUME_PRIMARY:$docPath")
    }

    // --- Operaciones sobre un árbol concedido ---

    /** Hijos DIRECTOS de [parentDocPath] dentro de [treeUri]. Usa una sola consulta al proveedor
     *  (no un [android.provider.DocumentsContract] por hijo): es lo rápido de verdad, a diferencia de
     *  `DocumentFile.listFiles()`, que sí hace una consulta por cada hijo. */
    fun listChildren(context: Context, treeUri: Uri, parentDocPath: String): List<SafEntry> {
        val parentDocId = "$VOLUME_PRIMARY:$parentDocPath"
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<SafEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val modIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val docPath = docId.removePrefix("$VOLUME_PRIMARY:")
                    val name = cursor.getString(nameIdx) ?: docPath.substringAfterLast('/')
                    val modified = cursor.getLong(modIdx)
                    // Gratis: ya viene en la misma consulta. Si un archivo ya visto vuelve con otra
                    // fecha (p. ej. el usuario le ha cambiado la carátula embebida con otra app), se
                    // le sube la versión para que su carátula se recargue (ver [contentVersion]).
                    noteModified(docPath, modified)
                    out.add(SafEntry(docPath, name, cursor.getString(mimeIdx) == Document.MIME_TYPE_DIR, modified))
                }
            }
        }
        return out
    }

    /** Crea un documento nuevo dentro de [parentDocPath] y devuelve su docPath, o null si falla. */
    fun createFile(context: Context, treeUri: Uri, parentDocPath: String, displayName: String, mimeType: String): String? {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$VOLUME_PRIMARY:$parentDocPath")
        val newUri = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, displayName)
        }.getOrNull() ?: return null
        return docPathOf(newUri)?.also { bumpVersion(it) }
    }

    /**
     * Busca una subcarpeta por nombre dentro de [parentDocPath], SIN crearla si no existe (a
     * diferencia de [getOrCreateSubfolder]). Para quien solo necesita LEER (p. ej.
     * [com.untar.ultimusic.util.CoverArt.imagesDocPath] con `createIfMissing = false`): antes
     * reconstruía la ruta a mano (`"$parentDocPath/$name"`) confiando en que el proveedor resolviera
     * mayúsculas/minúsculas por su cuenta al abrirla -y ese comportamiento no está garantizado en
     * todos los pasos (ver el porqué en [getOrCreateSubfolder])-, así que ahora busca igual que la
     * versión que sí crea: sin distinguir mayúsculas.
     */
    fun findSubfolder(context: Context, treeUri: Uri, parentDocPath: String, name: String): String? =
        listChildren(context, treeUri, parentDocPath)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.docPath

    /**
     * Busca una subcarpeta por nombre dentro de [parentDocPath]; si no existe, la crea.
     *
     * La comparación es SIN distinguir mayúsculas/minúsculas a propósito: el volumen "emulated" de
     * Android (`/storage/emulated/0`, montado vía FUSE) puede tratar "images" e "Images" como el
     * MISMO nombre a la hora de evitar un choque al crear un documento, pero seguía dando por buena
     * una comparación de cadenas EXACTA aquí -distinta de la del sistema de archivos de verdad-. El
     * resultado, confirmado en un dispositivo real: si la carpeta ya existía con otra capitalización
     * (p. ej. creada como "Images" en algún momento), esta función nunca la encontraba y creaba una
     * "images" nueva en cada llamada; como el sistema de archivos SÍ detectaba el choque de nombre,
     * la iba renombrando sola a "images (1)", "images (2)"... una carpeta nueva -y una carátula
     * huérfana- por cada guardado. Comparar sin distinguir mayúsculas evita que vuelva a pasar.
     */
    fun getOrCreateSubfolder(context: Context, treeUri: Uri, parentDocPath: String, name: String): String? {
        findSubfolder(context, treeUri, parentDocPath, name)?.let { return it }
        return createFile(context, treeUri, parentDocPath, name, Document.MIME_TYPE_DIR)
    }

    /** Busca un documento (no carpeta) por nombre dentro de [parentDocPath]; si no existe, lo
     *  crea. Para un archivo que se sobrescribe entero cada vez (como las listas de
     *  `PlaylistRepository`), donde "crear" y "abrir para escribir" son dos pasos distintos y
     *  `createFile` sobre un nombre ya existente crearía un duplicado en vez de reutilizarlo. Sin
     *  distinguir mayúsculas por el mismo motivo que [getOrCreateSubfolder]. */
    fun getOrCreateFile(context: Context, treeUri: Uri, parentDocPath: String, name: String, mimeType: String): String? {
        listChildren(context, treeUri, parentDocPath)
            .firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.let { return it.docPath }
        return createFile(context, treeUri, parentDocPath, name, mimeType)
    }

    /** Renombra un documento ya conocido por su docPath; devuelve su docPath NUEVO (algunos
     *  proveedores cambian el id de documento al renombrar, así que no se puede asumir el de antes
     *  con el nombre cambiado). */
    fun renameTo(context: Context, docPath: String, newName: String): String? {
        val uri = resolveUri(docPath) ?: return null
        bumpVersion(docPath)
        val newUri = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, newName)
        }.getOrNull() ?: return null
        return (docPathOf(newUri) ?: docPath).also { bumpVersion(it) }
    }

    fun exists(context: Context, docPath: String): Boolean {
        val uri = resolveUri(docPath) ?: return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /**
     * Espera a que [docPath] sea legible DE VERDAD, con unos pocos reintentos cortos: abre el
     * documento tal cual lo abriría Coil ([android.content.ContentResolver.openInputStream]), no
     * solo comprueba que aparece en un `query()` (ver [exists]). En algunos dispositivos (visto en
     * HyperOS/MIUI) el proveedor de almacenamiento indexa un documento recién creado en DOS PASOS
     * que no van sincronizados entre sí: el `query()` de [exists] ya lo encuentra, pero la
     * comprobación interna de "es hijo del árbol concedido" que hace `openTypedAssetFile` (la que
     * usa una apertura de verdad) todavía falla con `FileNotFoundException` un rato más -confirmado
     * en logcat: `exists()` daba `true` y la carátula seguía sin poder leerse-. Por eso esta función
     * abre el archivo de verdad (y lo cierra sin leer nada, solo para comprobar) en vez de reusar
     * [exists]. Sin esto, [com.untar.ultimusic.data.LibraryRepository.importCoverImage] daba por
     * buena una carátula que Coil no podía leer todavía, y se quedaba así -el proveedor no vuelve a
     * avisar de que ya está lista, así que nada disparaba un reintento- hasta la próxima vez que algo
     * tocara esa fila.
     */
    suspend fun awaitReadable(context: Context, docPath: String, attempts: Int = 8, delayMs: Long = 200): Boolean {
        val uri = resolveUri(docPath) ?: return false
        repeat(attempts) { attempt ->
            val readable = runCatching { context.contentResolver.openInputStream(uri)?.use { } }.isSuccess
            if (readable) return true
            if (attempt < attempts - 1) kotlinx.coroutines.delay(delayMs)
        }
        return false
    }

    fun lastModified(context: Context, docPath: String): Long {
        val uri = resolveUri(docPath) ?: return 0L
        return lastModifiedOfUri(context, uri)
    }

    /** Igual que [lastModified] pero directamente sobre un [uri] ya conocido (sin pasar por el
     *  registro): lo usan las claves de caché de carátulas ([com.untar.ultimusic.util.CoverArt]),
     *  que ya tienen el `Uri` en la mano. `0` (sin romper nada) para un `Uri` que no soporte esta
     *  columna, p. ej. un `file://` de la caché privada de la app. */
    fun lastModifiedOfUri(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Versión EN MEMORIA del contenido de cada archivo, por docPath: sube cada vez que la propia app
     * lo escribe, crea, renombra o borra ([createFile]/[openOutputStream]/[renameTo]/
     * [deleteRecursively]), o cuando un escaneo ([listChildren]) ve que su fecha de modificación ha
     * cambiado desde la última vez que lo vio.
     *
     * Es lo que llevan las claves de caché de Coil ([com.untar.ultimusic.util.CoverFileKeyer]/
     * [com.untar.ultimusic.util.AudioCoverKeyer]) y de [DynamicColor] en vez de la fecha de
     * modificación del archivo: esas claves se calculan en el HILO PRINCIPAL en cada bind de cada fila
     * (Coil 2 resuelve la caché en memoria en `Dispatchers.Main.immediate`), y pedir la fecha era una
     * consulta IPC al proveedor de documentos por carátula y por bind -lo que bloqueaba el hilo
     * principal al hacer scroll, hasta el punto de que el temporizador de pulsación larga saltaba
     * antes de que la lista llegara a procesar el arrastre-. Leer de aquí no toca el disco nunca.
     *
     * Que viva solo en memoria no pierde nada: la caché de Coil (y la de acentos de [DynamicColor])
     * también vive solo en memoria y muere con el proceso, así que al arrancar de nuevo no hay nada
     * viejo que invalidar.
     */
    private val versions = ConcurrentHashMap<String, Int>()

    /** Última fecha de modificación vista por [listChildren], para detectar cambios hechos fuera de la app. */
    private val seenModified = ConcurrentHashMap<String, Long>()

    /** Ver [versions]. Sin ninguna consulta: apto para llamarse en el hilo principal en cada bind. */
    fun contentVersion(uri: Uri): Int = docPathOf(uri)?.let { versions[it] } ?: 0

    fun bumpVersion(docPath: String) {
        versions.merge(docPath, 1, Int::plus)
    }

    private fun noteModified(docPath: String, modified: Long) {
        val previous = seenModified.put(docPath, modified)
        if (previous != null && previous != modified) bumpVersion(docPath)
    }

    fun deleteRecursively(context: Context, docPath: String): Boolean {
        val uri = resolveUri(docPath) ?: return false
        bumpVersion(docPath)
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    }

    fun openInputStream(context: Context, docPath: String): InputStream? {
        val uri = resolveUri(docPath) ?: return null
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    fun openOutputStream(context: Context, docPath: String): OutputStream? {
        val uri = resolveUri(docPath) ?: return null
        val raw = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull() ?: return null
        // La versión (ver [contentVersion]) sube al CERRAR, cuando el archivo ya está escrito entero:
        // si subiera al abrir, una carga de Coil que se colara a mitad de la escritura cachearía la
        // imagen a medias con la clave nueva, y ya nada volvería a invalidarla.
        return object : OutputStream() {
            override fun write(b: Int) = raw.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)
            override fun flush() = raw.flush()
            override fun close() {
                try {
                    raw.close()
                } finally {
                    bumpVersion(docPath)
                }
            }
        }
    }

    fun uriOf(docPath: String): Uri? = resolveUri(docPath)

    /**
     * Resuelve cualquier `Song.filePath`/`ScannedSong.filePath` de la app al URI abrible
     * correspondiente, sea cual sea su forma:
     *  - Ya trae esquema (`content://`, `file://`): es una reproducción "Abrir con UltiMusic" de un
     *    archivo NO catalogado, ver `MainActivity.handleViewIntent` — se usa tal cual.
     *  - Empieza por `/`: fila TODAVÍA no re-enlazada tras la migración a SAF (ruta absoluta de antes
     *    de v30, ver `MIGRATION_29_30`). No se puede abrir sin `MANAGE_EXTERNAL_STORAGE`; se deja caer
     *    en el mismo camino de "archivo no encontrado" que ya existía para un archivo movido/borrado
     *    (ver `PlayerViewModel.missingFile`), sin tratamiento especial.
     *  - Cualquier otra cosa: es un docPath de una canción catalogada y re-enlazada, se resuelve por
     *    [resolveUri].
     */
    fun uriForDomainPath(path: String): Uri? = when {
        "://" in path -> runCatching { Uri.parse(path) }.getOrNull()
        path.startsWith("/") -> runCatching { Uri.fromFile(java.io.File(path)) }.getOrNull()
        else -> resolveUri(path)
    }
}
