package com.untar.ultimusic.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga una imagen suelta de la red (la portada elegida en el autorrelleno de metadatos, ver
 * [com.untar.ultimusic.data.remote.MusicBrainzApi]) a la caché de la app, y la deja lista como si
 * el usuario la hubiera elegido con el selector de fotos del sistema.
 *
 * A diferencia de [com.untar.ultimusic.data.LibraryRepository.downloadVideoThumbnail] (la
 * miniatura de YouTube), aquí no hace falta pasar por Coil ni recortar nada a cuadrado: las
 * portadas de Cover Art Archive en tamaño "front-250" ya vienen cuadradas, así que basta con
 * copiar los bytes tal cual.
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

    /** Mismo dato de contacto que exige la política de uso de MusicBrainz para su API (ver
     * [com.untar.ultimusic.data.remote.MusicBrainzApi]); Cover Art Archive es un servicio
     * hermano, no MusicBrainz en sí, pero identificarse igual de bien es buena práctica y no
     * cuesta nada. Se repite aquí en vez de reutilizar la constante de `MusicBrainzApi` para que
     * los dos archivos sigan siendo independientes entre sí. */
    private const val USER_AGENT = "UltiMusic/1.0 ( rbesteiro@proton.me )"

    private const val TIMEOUT_MS = 8_000

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
                val file = File.createTempFile("mb_cover_", ".jpg", context.cacheDir)
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(file)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
