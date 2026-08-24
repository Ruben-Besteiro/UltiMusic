package com.untar.ultimusic.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Descarga una imagen suelta de la red (la portada elegida en el autorrelleno de metadatos, ver
 * [com.untar.ultimusic.data.remote.ItunesApi]) a la caché de la app, y la deja lista como si
 * el usuario la hubiera elegido con el selector de fotos del sistema.
 *
 * A diferencia de [com.untar.ultimusic.data.LibraryRepository.downloadVideoThumbnail] (la
 * miniatura de YouTube), aquí no hace falta pasar por Coil ni recortar nada a cuadrado: las
 * carátulas de iTunes ya vienen cuadradas, así que basta con copiar los bytes tal cual.
 *
 * Se guarda en la caché (no directamente en `~/UltiMusic/images`) porque todavía no se sabe si el
 * usuario va a guardar el formulario: la importación "de verdad", con el nombre final basado en el
 * título, la sigue haciendo
 * [com.untar.ultimusic.data.LibraryRepository.importCoverImage] al guardar — exactamente igual que
 * con una foto elegida a mano. La `Uri` de un archivo (`Uri.fromFile`) la lee
 * `ContentResolver.openInputStream` sin más, sin necesitar ningún `FileProvider`: eso solo hace
 * falta para COMPARTIR un archivo con otra aplicación, no para que la propia app se lea sus
 * propios archivos.
 */
object NetworkImage {

    /** Mismo dato de contacto que mandan los clientes de API de la app (ver
     * [com.untar.ultimusic.data.remote.ItunesApi]): identificarse es buena práctica y no cuesta
     * nada, aunque descargar una imagen del CDN de Apple no lo exija. Se repite aquí en vez de
     * reutilizar la constante de `ItunesApi` para que los dos archivos sigan siendo
     * independientes entre sí. */
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    private const val TIMEOUT_MS = 8_000

    /** Prefijo de [File.createTempFile] para estos archivos: lo usa también [pruneOldTempFiles]
     * para reconocer cuáles son suyos y no tocar nada más de la caché. */
    private const val TEMP_PREFIX = "remote_cover_"

    /** Cuánto se deja vivir un temporal sin reclamar antes de considerarlo basura. Generoso a
     * propósito: una edición a medias puede sobrevivir un buen rato (la app en segundo plano, el
     * sistema matando el proceso y recreándolo al volver...) y no queremos borrar el archivo por
     * debajo de una sesión que sigue abierta. Ver [pruneOldTempFiles]. */
    private val STALE_AFTER_MS = TimeUnit.HOURS.toMillis(24)

    /**
     * Best-effort: si algo falla (sin red, la portada no está archivada y da 404...) devuelve
     * null y quien llame se queda sin portada nueva, sin romper nada — mismo patrón que
     * `downloadVideoThumbnail`.
     */
    suspend fun download(context: Context, url: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Descarga de portada respondió ${connection.responseCode}"
                }
                val file = File.createTempFile(TEMP_PREFIX, ".jpg", context.cacheDir)
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(file)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Poda por tiempo de los temporales de [download] que nadie reclamó: si el usuario previsualiza
     * una portada y cierra el editor sin guardar (o guarda, que también deja huérfano el temporal
     * una vez importado a su ubicación final en [CoverArt.imagesDir]), el archivo se queda en
     * [Context.cacheDir] para siempre, porque nada más lo borra. Mismo patrón que
     * [com.untar.ultimusic.data.remote.ApiCache.pruneExpired]: la llama
     * [com.untar.ultimusic.UltiMusicApp] una vez por arranque, en segundo plano.
     */
    fun pruneOldTempFiles(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
            context.cacheDir.listFiles { file -> file.name.startsWith(TEMP_PREFIX) }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }
}
