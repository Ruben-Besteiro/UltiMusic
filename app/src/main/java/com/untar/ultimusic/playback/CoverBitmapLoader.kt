package com.untar.ultimusic.playback

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Carga la carátula que enseña la notificación de reproducción (y, con ella, la pantalla de
 * bloqueo). Media3 sabe pedir una imagen a partir de un [android.net.Uri], pero no sabe nada de
 * la cadena de resolución propia de UltiMusic (imagen personalizada → arte embebido del audio →
 * miniatura de YouTube → nada, ver [CoverArt]); por eso hace falta este [BitmapLoader] a medida en
 * vez del que trae Media3 de fábrica.
 *
 * El truco está en el propio URI: [PlaybackService] no le pone a cada canción un enlace de verdad,
 * sino uno inventado con su id (`ultimusic://cover/<id>`, ver `PlaybackService.coverUriFor`). Aquí
 * se deshace ese id, se busca la canción en el [PlaybackService] (que la tiene en memoria, en la
 * cola o sonando) y se le pide su carátula a [CoverArt.cover], la misma función que usa cualquier
 * `ImageView` del resto de la interfaz — así la notificación nunca desentona con lo que se ve
 * dentro de la aplicación.
 */
@Suppress("DEPRECATION") // androidx.media3.session.BitmapLoader está marcada @Deprecated en favor
// de androidx.media3.common.util.BitmapLoader, pero es la que pide MediaSession.Builder.setBitmapLoader.
class CoverBitmapLoader(private val service: PlaybackService) : androidx.media3.session.BitmapLoader {

    /** Un solo hilo de fondo basta: cargar una carátula es puntual, no hay que paralelizarlo. */
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = true

    /** UltiMusic nunca manda `artworkData` (bytes crudos) en la metadata, solo `artworkUri`: ver
     *  [androidx.media3.common.util.BitmapLoader.loadBitmapFromMetadata]. */
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        future.setException(UnsupportedOperationException("UltiMusic no usa artworkData"))
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val songId = uri.lastPathSegment?.toLongOrNull()
        val song = songId?.let(service::findSong)
        if (song == null) {
            future.setException(IllegalArgumentException("Canción desconocida para la notificación: $uri"))
            return future
        }
        scope.launch {
            try {
                val request = ImageRequest.Builder(service)
                    .data(CoverArt.cover(service, song))
                    .allowHardware(false) // la notificación necesita un Bitmap normal, no uno de GPU
                    .build()
                val result = CoverLoader.get(service).execute(request)
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalStateException("Sin carátula para «${song.title}»"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }
}
