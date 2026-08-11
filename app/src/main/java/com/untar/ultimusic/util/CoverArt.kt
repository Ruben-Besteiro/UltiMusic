package com.untar.ultimusic.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.untar.ultimusic.model.Song
import okio.Buffer
import java.io.File

/**
 * Resolución de carátulas con la cadena de failsafe de UltiMusic:
 *
 *   imagen personalizada (Song/Album.imageName) → arte embebido del archivo → miniatura de
 *   YouTube cacheada (Song.videoThumbnailName) → recuadro negro.
 *
 * El primer eslabón lo rellena el editor de metadatos: cuando el usuario elige una portada, el
 * archivo se copia a [CoverArt.imagesDir] con un nombre derivado del título/nombre de la
 * canción/álbum/artista/productor al que pertenece, y ese nombre queda en `imageName`. El tercer
 * eslabón lo rellena el mismo editor al guardar un enlace de YouTube: se descarga y recorta la
 * miniatura del vídeo (ver `LibraryRepository.downloadVideoThumbnail`) y su nombre queda en
 * `videoThumbnailName`, en la misma carpeta. Si no hay imagen personalizada se extrae el arte
 * embebido del propio archivo de audio; si tampoco lo hay, se prueba la miniatura; si tampoco,
 * Coil cae en el drawable de error (recuadro negro) configurado en cada ImageView.
 *
 * Para un álbum, artista o productor la cadena tiene un eslabón más, porque ellos no son un archivo
 * de audio: imagen propia → imagen personalizada de una de sus canciones → arte embebido de una de
 * sus canciones → miniatura de YouTube de una de sus canciones → recuadro negro. Eso es lo que
 * describe un [CoverRef].
 */
object CoverArt {

    /**
     * Carpeta `~/UltiMusic/images`, donde viven TODAS las imágenes de la app: las portadas
     * importadas por el usuario y las miniaturas de YouTube cacheadas. Mismo patrón que
     * `~/UltiMusic/Playlists` o `~/UltiMusic/databases` (ver [PlaylistRepository][com.untar.ultimusic.data.playlist.PlaylistRepository]):
     * una carpeta visible del almacenamiento del propio dispositivo, no privada de la app, así que
     * sobrevive a un desinstalar/reinstalar.
     */
    fun imagesDir(context: Context): File =
        File(Environment.getExternalStorageDirectory(), "UltiMusic/images").apply { mkdirs() }

    /** Quita del nombre los caracteres que ningún sistema de archivos admite, para poder nombrar
     * una imagen igual que el título/nombre al que pertenece. */
    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()

    /**
     * Busca un nombre de archivo libre en [dir] para `baseName.ext`.
     *
     * Si ese nombre ya lo tiene ESTA MISMA imagen ([currentName], la que ya tenía la
     * canción/álbum/persona antes de guardar), se reutiliza tal cual: así renombrar no dispara una
     * colisión consigo mismo. Si el nombre lo tiene una imagen de otra cosa, se prueba
     * `baseName (2).ext`, `baseName (3).ext`... hasta encontrar uno libre.
     *
     * Es una comprobación de disco, no de la base de datos: para una librería personal (sin
     * escrituras concurrentes) es suficiente y no hace falta ir a consultar quién es cada dueño.
     */
    fun reserveFileName(dir: File, baseName: String, ext: String, currentName: String?): String {
        var candidate = "$baseName.$ext"
        if (candidate == currentName || !File(dir, candidate).exists()) return candidate
        var suffix = 2
        while (true) {
            candidate = "$baseName ($suffix).$ext"
            if (candidate == currentName || !File(dir, candidate).exists()) return candidate
            suffix++
        }
    }

    /**
     * Dato que se pasa a Coil para cargar la carátula de una canción.
     *
     * Con el ajuste "Usar carátula del álbum siempre" activo (ver [DisplaySettings]) y la canción
     * perteneciendo a un álbum, la cadena tira primero de la imagen propia del ÁLBUM en vez de la de
     * la canción; el resto de eslabones (imagen propia de la canción, arte embebido, miniatura de
     * YouTube) se mantienen como reserva, así que una canción sin nada propio de todos modos no
     * queda en negro. Como [com.untar.ultimusic.playback.PlaybackService.updateAccent] calcula el
     * color dinámico a partir de este mismo dato, activar el ajuste también hace que el color
     * dinámico salga de la carátula del álbum en vez de la de la canción.
     */
    fun cover(context: Context, song: Song): Any {
        val album = song.albums.firstOrNull()
        val ref = if (album != null && DisplaySettings.useAlbumCoverAlways) {
            CoverRef(album.imageName, song.imageName, song.filePath, song.videoThumbnailName)
        } else {
            CoverRef(song.imageName, null, song.filePath, song.videoThumbnailName)
        }
        return cover(context, ref)
    }

    /**
     * Ídem para un álbum/artista/productor. Devuelve el primer eslabón disponible de la cadena; el
     * último (el recuadro negro) lo pone Coil como `error(...)` en cada ImageView, porque solo al
     * intentar leer el archivo de audio se sabe si tiene arte embebido o no.
     */
    fun cover(context: Context, ref: CoverRef): Any {
        val dir = imagesDir(context)
        for (name in listOfNotNull(ref.ownImage, ref.songImage)) {
            val file = File(dir, name)
            if (file.exists()) return file
        }
        val thumbnail = ref.videoThumbnail?.let { File(dir, it) }
        return AudioCover(File(ref.songPath.orEmpty()), thumbnail)
    }
}

/**
 * Los cuatro eslabones "de datos" de la cadena de carátulas, para poder resolverla igual sea de una
 * canción, un álbum, un artista o un productor.
 *
 * @param ownImage nombre de la imagen personalizada del propio elemento, si la tiene.
 * @param songImage nombre de la imagen personalizada de una de sus canciones (solo álbum/persona).
 * @param songPath ruta de un archivo de audio suyo, del que extraer el arte embebido.
 * @param videoThumbnail nombre de la miniatura de YouTube de una de sus canciones (o de la propia
 *   canción, si es un [CoverRef] de canción), por si no hay arte embebido.
 */
data class CoverRef(
    val ownImage: String?,
    val songImage: String?,
    val songPath: String?,
    val videoThumbnail: String? = null
)

/** Envoltorio para indicarle a Coil que debe extraer el arte embebido de un archivo de audio, con
 * la miniatura de YouTube como reserva si ese archivo no tiene arte embebido. */
data class AudioCover(val file: File, val fallbackThumbnail: File?)

/** Fetcher de Coil que extrae la imagen embebida de un archivo de audio en segundo plano, y si no
 * hay, cae en la miniatura de YouTube cacheada (cuando existe). */
class AudioCoverFetcher(
    private val file: File,
    private val fallbackThumbnail: File?,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()

        val bytes = embedded ?: fallbackThumbnail?.takeIf { it.exists() }?.readBytes() ?: return null
        return SourceResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                context = options.context
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioCoverFetcher(data.file, data.fallbackThumbnail, options)
    }
}

/**
 * Claves de caché de Coil que incluyen la fecha de modificación del archivo.
 *
 * El editor de metadatos reutiliza el MISMO nombre de archivo al reemplazar una carátula (ver
 * [LibraryRepository.importCoverImage][com.untar.ultimusic.data.LibraryRepository.importCoverImage]):
 * si el título no cambia, el archivo viejo se borra y el nuevo se importa con el mismo nombre. Sin
 * esto, la clave de caché de Coil (por defecto, la ruta del archivo) sería idéntica antes y después,
 * así que la caché en memoria seguiría devolviendo el bitmap viejo en todos los sitios menos en la
 * vista previa del propio editor (que carga el URI recién elegido directamente, sin pasar por Coil).
 */
class CoverFileKeyer : Keyer<File> {
    override fun key(data: File, options: Options): String = "${data.absolutePath}:${data.lastModified()}"
}

/** Ídem que [CoverFileKeyer] pero para [AudioCover]: incluye la fecha de modificación tanto del
 * archivo de audio (de donde sale el arte embebido) como de la miniatura de reserva. */
class AudioCoverKeyer : Keyer<AudioCover> {
    override fun key(data: AudioCover, options: Options): String {
        val thumbnail = data.fallbackThumbnail
        return "${data.file.absolutePath}:${data.file.lastModified()}:" +
            "${thumbnail?.absolutePath}:${thumbnail?.lastModified() ?: 0}"
    }
}

/** Provee un [ImageLoader] compartido con soporte para carátulas embebidas de audio. */
object CoverLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(AudioCoverFetcher.Factory())
                    add(CoverFileKeyer())
                    add(AudioCoverKeyer())
                }
                .build()
                .also { instance = it }
        }
}
