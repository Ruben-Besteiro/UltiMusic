package com.untar.ultimusic.util

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import android.widget.Toast
import androidx.annotation.StringRes
import com.untar.ultimusic.R
import com.untar.ultimusic.data.scan.MusicScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Recoge las descargas que dispara el WebView de una tienda (ver
 * [com.untar.ultimusic.ui.preview.StoreWebDialogFragment]) y las deja en la carpeta `UltiMusic/`.
 * Un WebView no descarga nada por sí solo: sin un `DownloadListener` que llegue hasta aquí, tocar
 * "Descargar" en la tienda no haría absolutamente nada.
 *
 * No hace falta avisar a la biblioteca: `MusicLibraryObserver` ya vigila `UltiMusic/` de forma
 * recursiva (ver `LibraryRepository.startWatchingLibraryChanges`), así que la canción aparece sola
 * en cuanto el archivo queda completo — de ahí que se escriba primero con un nombre oculto y sin
 * extensión de audio ([PART_EXTENSION]) y solo se renombre al terminar: así el escáner nunca ve un
 * archivo a medias.
 *
 * Usa `HttpURLConnection` a mano (como el resto de la app, ver
 * [com.untar.ultimusic.data.remote.ItunesApi]) en vez de `DownloadManager`: este último no deja
 * escribir en cualquier carpeta del almacenamiento compartido desde Android 10, y aquí el destino
 * es `UltiMusic/` (la app ya tiene `MANAGE_EXTERNAL_STORAGE`). Por lo mismo se envían las cookies y
 * el `User-Agent` del WebView: las descargas de una compra van ligadas a la sesión.
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

    private const val LIBRARY_FOLDER = "UltiMusic"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val PART_EXTENSION = "part"

    /** Firma de todo `.zip` ("PK"): se mira esto y no la extensión ni el tipo MIME,
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
        val fileName = sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
        if (!url.startsWith("http")) {
            toast(context, R.string.store_download_failed, fileName)
            return
        }
        toast(context, R.string.store_download_started, fileName)
        scope.launch {
            val result = runCatching { fetch(url, userAgent, cookies, fileName) }
            toast(
                context,
                if (result.isSuccess) R.string.store_download_done else R.string.store_download_failed,
                fileName
            )
        }
    }

    private fun fetch(url: String, userAgent: String?, cookies: String?, fileName: String) {
        val root = File(Environment.getExternalStorageDirectory(), LIBRARY_FOLDER)
        if (!root.exists() && !root.mkdirs()) throw IOException("No se puede crear $root")

        val part = File(root, ".$fileName.$PART_EXTENSION")
        try {
            val connection = connect(url, userAgent, cookies)
            try {
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }

            if (isZip(part)) {
                extractAudio(part, root, fileName.substringBeforeLast('.', fileName))
            } else if (!part.renameTo(uniqueFile(root, fileName))) {
                throw IOException("No se puede renombrar $part")
            }
        } finally {
            part.delete() // ya renombrado no existe; tras un fallo o un zip, borra lo que sobre
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

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(ZIP_SIGNATURE.size)
        input.read(header) == header.size && header.contentEquals(ZIP_SIGNATURE)
    }

    /**
     * Descomprime SOLO el audio de [zip] en una subcarpeta nueva de [root] llamada [folderName].
     * Se aplana (se ignoran las carpetas internas) y se toma solo el nombre de cada entrada, lo que
     * de paso impide que una entrada con `../` escriba fuera de la carpeta (zip-slip). Un zip sin
     * ningún audio (solo carátula o PDF) se da por fallido y no deja carpeta.
     */
    private fun extractAudio(zip: File, root: File, folderName: String) {
        val destination = uniqueFile(root, sanitize(folderName))
        if (!destination.mkdirs()) throw IOException("No se puede crear $destination")

        var extracted = 0
        ZipInputStream(zip.inputStream().buffered()).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = sanitize(entry.name.substringAfterLast('/').substringAfterLast('\\'))
                val isAudio = name.substringAfterLast('.', "").lowercase() in MusicScanner.AUDIO_EXTENSIONS
                if (!entry.isDirectory && isAudio) {
                    val target = uniqueFile(destination, name)
                    val temp = File(destination, ".${target.name}.$PART_EXTENSION")
                    temp.outputStream().use { zipStream.copyTo(it) }
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        throw IOException("No se puede renombrar $temp")
                    }
                    extracted++
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
        if (extracted == 0) {
            destination.deleteRecursively()
            throw IOException("El zip no contiene audio")
        }
    }

    /** [name] dentro de [dir], o "nombre (1).ext", "nombre (2).ext"... si ya existe algo así. */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var n = 1
        do {
            candidate = File(dir, if (extension.isEmpty()) "$base ($n)" else "$base ($n).$extension")
            n++
        } while (candidate.exists())
        return candidate
    }

    /** Quita lo que no vale en un nombre de archivo y los puntos iniciales (dejarían el archivo oculto). */
    private fun sanitize(name: String): String =
        name.replace(UNSAFE_FILENAME_CHARS, "_").trim().trimStart('.').ifEmpty { "download" }

    private fun toast(context: Context, @StringRes message: Int, fileName: String) {
        mainHandler.post {
            Toast.makeText(context, context.getString(message, fileName), Toast.LENGTH_SHORT).show()
        }
    }
}
