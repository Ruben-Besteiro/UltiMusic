package com.untar.ultimusic.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.annotation.StringRes
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Recoge las descargas que dispara el WebView de una tienda (ver
 * [com.untar.ultimusic.ui.preview.StoreWebDialogFragment]) y las deja en la carpeta `UltiMusic`
 * concedida por el usuario (ver [SafStorage]). Un WebView no descarga nada por sí solo: sin un
 * `DownloadListener` que llegue hasta aquí, tocar "Descargar" en la tienda no haría absolutamente
 * nada.
 *
 * No hace falta avisar a la biblioteca: la reconciliación periódica (ver
 * `LibraryRepository.startWatchingLibraryChanges`) recoge la canción sola en cuanto el archivo
 * queda completo — de ahí que se escriba primero con un nombre oculto y sin extensión de audio
 * ([PART_SUFFIX]) y solo se renombre al terminar: así el escáner nunca ve un archivo a medias.
 *
 * Usa `HttpURLConnection` a mano (como el resto de la app, ver
 * [com.untar.ultimusic.data.remote.ItunesApi]) para la descarga, y `SafStorage`/`DocumentsContract`
 * para escribir (ni `DownloadManager` ni `File` directo sirven desde Android 10 sin
 * `MANAGE_EXTERNAL_STORAGE`, que esta app ya no pide). Por lo mismo se envían las cookies y el
 * `User-Agent` del WebView: las descargas de una compra van ligadas a la sesión.
 *
 * Corre en un ámbito propio ([scope]) y no en el del diálogo, para que cerrar el WebView no
 * cancele una descarga en curso. No hay servicio en primer plano: si Android mata la app en segundo
 * plano, la descarga se pierde. Las URLs `blob:` y `data:` (generadas por JavaScript en la propia
 * página) no se pueden recoger desde fuera del WebView y se avisan como fallo.
 *
 * Si lo descargado es un `.zip` (así entregan los álbumes Bandcamp, Amazon...), se descomprime en
 * una subcarpeta con su nombre quedándose solo con el audio, y el zip se borra.
 */
object StoreDownloader {

    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val PART_SUFFIX = "part"

    /** Firma de todo `.zip` ("PK"): se mira esto y no la extensión ni el tipo MIME,
     * que las tiendas ponen a menudo como `application/octet-stream` o con nombre genérico. */
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val UNSAFE_FILENAME_CHARS = Regex("""[\\/:*?"<>|]""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Lo llama el `DownloadListener` del WebView. Vuelve enseguida; el resultado se avisa con Toasts. */
    fun download(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookies: String?
    ) {
        val appContext = context.applicationContext
        val fileName = sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
        if (!url.startsWith("http")) {
            toast(appContext, R.string.store_download_failed, fileName)
            return
        }
        toast(appContext, R.string.store_download_started, fileName)
        scope.launch {
            val result = runCatching { fetch(appContext, url, userAgent, cookies, fileName) }
            toast(
                appContext,
                if (result.isSuccess) R.string.store_download_done else R.string.store_download_failed,
                fileName
            )
        }
    }

    private fun fetch(context: Context, url: String, userAgent: String?, cookies: String?, fileName: String) {
        SafStorage.ensureUltiMusicRegistered(context)
        val treeUri = SafStorage.ultiMusicTreeUri(context) ?: throw IOException("La carpeta UltiMusic no está concedida")
        val root = SafStorage.ultiMusicDocPath(context) ?: throw IOException("La carpeta UltiMusic no está concedida")

        val partName = ".$fileName.$PART_SUFFIX"
        var partDocPath = SafStorage.createFile(context, treeUri, root, partName, mimeTypeFor(fileName))
            ?: throw IOException("No se puede crear $partName")
        try {
            val connection = connect(url, userAgent, cookies)
            try {
                connection.inputStream.use { input ->
                    val output = SafStorage.openOutputStream(context, partDocPath)
                        ?: throw IOException("No se puede escribir $partDocPath")
                    output.use { input.copyTo(it) }
                }
            } finally {
                connection.disconnect()
            }

            if (isZip(context, partDocPath)) {
                extractAudio(context, treeUri, partDocPath, root, fileName.substringBeforeLast('.', fileName))
            } else {
                val finalName = uniqueName(context, treeUri, root, fileName)
                val renamed = SafStorage.renameTo(context, partDocPath, finalName)
                    ?: throw IOException("No se puede renombrar $partDocPath")
                partDocPath = renamed
            }
        } finally {
            // Si se ha renombrado (o extraído), ya no hay nada en esta ruta y esto no hace nada; tras
            // un fallo, borra lo que sobre.
            runCatching { if (SafStorage.exists(context, partDocPath)) SafStorage.deleteRecursively(context, partDocPath) }
        }
    }

    /**
     * Abre [startUrl] siguiendo las redirecciones a mano (hasta [MAX_REDIRECTS]) en vez de dejar que
     * lo haga `HttpURLConnection`: las cookies de la sesión solo deben ir al host original y no a la
     * CDN a la que la tienda redirija la descarga, y solo se aceptan destinos `http`/`https`.
     */
    private fun connect(startUrl: String, userAgent: String?, cookies: String?): HttpURLConnection {
        var current = URL(startUrl)
        val originHost = current.host
        repeat(MAX_REDIRECTS + 1) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            if (!cookies.isNullOrBlank() && current.host == originHost) {
                connection.setRequestProperty("Cookie", cookies)
            }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) throw IOException("Redirección sin destino ($code)")
                    val next = URL(current, location)
                    if (next.protocol != "http" && next.protocol != "https") {
                        throw IOException("Redirección a un esquema no permitido: ${next.protocol}")
                    }
                    current = next
                }
                else -> {
                    connection.disconnect()
                    throw IOException("La tienda respondió $code")
                }
            }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun isZip(context: Context, docPath: String): Boolean {
        val input = SafStorage.openInputStream(context, docPath) ?: return false
        return input.use {
            val header = ByteArray(ZIP_SIGNATURE.size)
            it.read(header) == header.size && header.contentEquals(ZIP_SIGNATURE)
        }
    }

    /**
     * Descomprime SOLO el audio de [zipDocPath] en una subcarpeta nueva de [root] llamada
     * [folderName]. Se aplana (se ignoran las carpetas internas) y se toma solo el nombre de cada
     * entrada, lo que de paso impide que una entrada con `../` escriba fuera de la carpeta
     * (zip-slip: [SafStorage.createFile] crea siempre DENTRO del documento padre que se le pasa, no
     * interpreta rutas). Un zip sin ningún audio (solo carátula o PDF) se da por fallido y no deja
     * carpeta. Cada archivo se escribe primero con un nombre oculto (`.part`) y solo se renombra al
     * terminar, mismo motivo que el `.part` de fuera: que el escáner nunca vea uno a medias.
     */
    private fun extractAudio(context: Context, treeUri: Uri, zipDocPath: String, root: String, folderName: String) {
        val destName = uniqueName(context, treeUri, root, sanitize(folderName))
        val destDocPath = SafStorage.createFile(context, treeUri, root, destName, DocumentsContract.Document.MIME_TYPE_DIR)
            ?: throw IOException("No se puede crear $destName")

        var extracted = 0
        val zipInput = SafStorage.openInputStream(context, zipDocPath) ?: throw IOException("No se puede leer $zipDocPath")
        ZipInputStream(zipInput.buffered()).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = sanitize(entry.name.substringAfterLast('/').substringAfterLast('\\'))
                val isAudio = name.substringAfterLast('.', "").lowercase() in MusicScanner.AUDIO_EXTENSIONS
                if (!entry.isDirectory && isAudio) {
                    val targetName = uniqueName(context, treeUri, destDocPath, name)
                    if (extractOne(context, treeUri, destDocPath, targetName, zipStream)) extracted++
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
        if (extracted == 0) {
            SafStorage.deleteRecursively(context, destDocPath)
            throw IOException("El zip no contiene audio")
        }
    }

    /** Un archivo de [zipStream] dentro de [parentDocPath]: escribe a `.nombre.part` y renombra al
     *  terminar. Devuelve si se ha conseguido extraer de verdad. */
    private fun extractOne(context: Context, treeUri: Uri, parentDocPath: String, targetName: String, zipStream: ZipInputStream): Boolean {
        val tempName = ".$targetName.$PART_SUFFIX"
        val tempDocPath = SafStorage.createFile(context, treeUri, parentDocPath, tempName, mimeTypeFor(targetName))
            ?: return false
        val written = runCatching {
            SafStorage.openOutputStream(context, tempDocPath)?.use { zipStream.copyTo(it) } != null
        }.getOrDefault(false)
        if (written && SafStorage.renameTo(context, tempDocPath, targetName) != null) return true
        SafStorage.deleteRecursively(context, tempDocPath)
        return false
    }

    /** [name] dentro de [parentDocPath], o "nombre (1).ext", "nombre (2).ext"... si ya existe algo así. */
    private fun uniqueName(context: Context, treeUri: Uri, parentDocPath: String, name: String): String {
        val existingNames = SafStorage.listChildren(context, treeUri, parentDocPath).mapTo(HashSet()) { it.name }
        if (name !in existingNames) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (extension.isEmpty()) "$base ($n)" else "$base ($n).$extension"
            if (candidate !in existingNames) return candidate
            n++
        }
    }

    /** Quita lo que no vale en un nombre de archivo y los puntos iniciales (dejarían el archivo oculto). */
    private fun sanitize(name: String): String =
        name.replace(UNSAFE_FILENAME_CHARS, "_").trim().trimStart('.').ifEmpty { "download" }

    private fun mimeTypeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun toast(context: Context, @StringRes message: Int, fileName: String) {
        mainHandler.post {
            Toast.makeText(context, context.getString(message, fileName), Toast.LENGTH_SHORT).show()
        }
    }
}
