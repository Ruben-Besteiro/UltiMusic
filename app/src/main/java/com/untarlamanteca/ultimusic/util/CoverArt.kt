package com.untarlamanteca.ultimusic.util

import android.content.Context
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.untarlamanteca.ultimusic.model.Song
import okio.Buffer
import java.io.File

/**
 * Resolución de carátulas con la cadena de failsafe de UltiMusic:
 *
 *   imagen personalizada (Song/Album.imageName) → arte embebido del archivo → recuadro negro.
 *
 * De momento no existe un flujo de importación de imágenes personalizadas, así que el
 * primer eslabón siempre es null y se usa el arte embebido; si tampoco lo hay, Coil cae
 * en el drawable de error (recuadro negro) configurado en cada ImageView.
 */
object CoverArt {

    /** Dato que se pasa a Coil para cargar la carátula de una canción. */
    fun cover(song: Song): Any {
        // TODO: cuando exista imagen personalizada, devolver ese File aquí.
        return AudioCover(File(song.filePath))
    }
}

/** Envoltorio para indicarle a Coil que debe extraer el arte embebido de un archivo de audio. */
data class AudioCover(val file: File)

/** Fetcher de Coil que extrae la imagen embebida de un archivo de audio en segundo plano. */
class AudioCoverFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            SourceResult(
                source = ImageSource(
                    source = Buffer().apply { write(bytes) },
                    context = options.context
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioCoverFetcher(data.file, options)
    }
}

/** Provee un [ImageLoader] compartido con soporte para carátulas embebidas de audio. */
object CoverLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(AudioCoverFetcher.Factory()) }
                .build()
                .also { instance = it }
        }
}
