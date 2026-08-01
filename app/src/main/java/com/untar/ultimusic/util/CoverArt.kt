package com.untar.ultimusic.util

import android.content.Context
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.untar.ultimusic.model.Song
import okio.Buffer
import java.io.File

/**
 * Resolución de carátulas con la cadena de failsafe de UltiMusic:
 *
 *   imagen personalizada (Song/Album.imageName) → arte embebido del archivo → recuadro negro.
 *
 * El primer eslabón lo rellena el editor de metadatos: cuando el usuario elige una portada, el
 * archivo se copia a [CoverArt.coversDir] y su nombre queda en `imageName`. Si no hay imagen
 * personalizada se extrae el arte embebido del propio archivo de audio; si tampoco lo hay, Coil
 * cae en el drawable de error (recuadro negro) configurado en cada ImageView.
 *
 * Para un álbum, artista o productor la cadena tiene un eslabón más, porque ellos no son un archivo
 * de audio: imagen propia → imagen personalizada de una de sus canciones → arte embebido de una de
 * sus canciones → recuadro negro. Eso es lo que describe un [CoverRef].
 */
object CoverArt {

    /** Carpeta privada de la app donde viven las portadas importadas por el usuario. */
    fun coversDir(context: Context): File =
        File(context.applicationContext.filesDir, "covers").apply { mkdirs() }

    /** Dato que se pasa a Coil para cargar la carátula de una canción. */
    fun cover(context: Context, song: Song): Any =
        cover(context, CoverRef(song.imageName, null, song.filePath))

    /**
     * Ídem para un álbum/artista/productor. Devuelve el primer eslabón disponible de la cadena; el
     * último (el recuadro negro) lo pone Coil como `error(...)` en cada ImageView, porque solo al
     * intentar leer el archivo se sabe si tiene arte embebido o no.
     */
    fun cover(context: Context, ref: CoverRef): Any {
        val dir = coversDir(context)
        for (name in listOfNotNull(ref.ownImage, ref.songImage)) {
            val file = File(dir, name)
            if (file.exists()) return file
        }
        return AudioCover(File(ref.songPath.orEmpty()))
    }
}

/**
 * Los tres eslabones "de datos" de la cadena de carátulas, para poder resolverla igual sea de una
 * canción, un álbum, un artista o un productor.
 *
 * @param ownImage nombre de la imagen personalizada del propio elemento, si la tiene.
 * @param songImage nombre de la imagen personalizada de una de sus canciones (solo álbum/persona).
 * @param songPath ruta de un archivo de audio suyo, del que extraer el arte embebido.
 */
data class CoverRef(
    val ownImage: String?,
    val songImage: String?,
    val songPath: String?
)

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
