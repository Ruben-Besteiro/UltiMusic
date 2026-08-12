package com.untar.ultimusic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.os.Environment
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.data.db.relations.CollageCandidateRow
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Buffer
import java.io.File
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.sqrt

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
 * de audio: imagen propia → COLLAGE con las carátulas de varias de sus canciones (si hay al menos
 * 4 distintas) → carátula de la primera de esas canciones que tenga alguna → recuadro negro. El
 * collage y ese último eslabón los resuelve [GroupCoverFetcher] bajo demanda (consulta la base de
 * datos y lee los archivos, así que no puede hacerse aquí, que es síncrono); [cover] solo entrega
 * el [GroupCoverSource] que le indica qué álbum/artista/productor resolver. Eso es lo que describe
 * un [CoverRef].
 */
object CoverArt {

    /**
     * Sube cada vez que un editor de metadatos (canción o álbum) termina de guardar una carátula.
     *
     * Hace falta porque las listas de la app (Canciones, Álbumes, Artistas/Productores, la ficha de
     * detalle, el buscador...) salen de Room a través de un `StateFlow`, y un `StateFlow` NUNCA
     * reemite un valor estructuralmente IGUAL al anterior. Si la carátula se reemplaza reutilizando
     * el mismo nombre de archivo (el caso normal: ver [reserveFileName]), el `Song`/`Album` que sale
     * de la base de datos es idéntico campo a campo al de antes -solo ha cambiado el CONTENIDO del
     * archivo en disco-, así que ese `StateFlow` se queda callado y ninguna lista se repinta.
     *
     * Quien pinte una de esas listas debe combinar su flujo con [revision] (ver
     * [com.untar.ultimusic.ui.songs.SongsFragment] para el patrón) para forzar ese repintado igual
     * si la lista en sí no ha cambiado: como los nombres de archivo de las carátulas ya llevan la
     * fecha de modificación en la clave de caché de Coil (ver [CoverFileKeyer]/[AudioCoverKeyer]),
     * un repintado de más solo cuesta un `notifyDataSetChanged` y una consulta a la caché en
     * memoria, nunca releer el archivo entero de disco.
     *
     * Es el mismo truco que ya usaba
     * [PlaybackService.coverRevision][com.untar.ultimusic.playback.PlaybackService.coverRevision]
     * para el mini-reproductor y el iPod; este es su equivalente para el resto de la app.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Ver [revision]. Lo llama cada editor justo después de guardar. */
    fun touch() {
        _revision.value++
    }

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

    /** Dato que se pasa a Coil para cargar la carátula de una canción. */
    fun cover(context: Context, song: Song): Any {
        val ref = CoverRef(song.imageName, null, song.filePath, song.videoThumbnailName)
        return cover(context, ref)
    }

    /**
     * Ídem para un álbum/artista/productor. Devuelve el primer eslabón disponible de la cadena; el
     * último (el recuadro negro) lo pone Coil como `error(...)` en cada ImageView, porque solo al
     * intentar leer el archivo de audio se sabe si tiene arte embebido o no.
     *
     * Con [CoverRef.group] (siempre presente salvo en el [CoverRef] de una canción suelta), si no
     * hay imagen propia se entrega el propio [GroupCoverSource]: es [GroupCoverFetcher] quien
     * decide, ya de forma asíncrona, si hay collage o toca caer a la carátula de una sola canción.
     */
    fun cover(context: Context, ref: CoverRef): Any {
        val dir = imagesDir(context)
        ref.ownImage?.let { name ->
            val file = File(dir, name)
            if (file.exists()) return file
        }
        ref.group?.let { return it }
        ref.songImage?.let { name ->
            val file = File(dir, name)
            if (file.exists()) return file
        }
        val thumbnail = ref.videoThumbnail?.let { File(dir, it) }
        return AudioCover(File(ref.songPath.orEmpty()), thumbnail)
    }
}

/**
 * Los eslabones "de datos" de la cadena de carátulas, para poder resolverla igual sea de una
 * canción, un álbum, un artista o un productor.
 *
 * @param ownImage nombre de la imagen personalizada del propio elemento, si la tiene.
 * @param songImage nombre de la imagen personalizada de una de sus canciones (solo álbum/persona,
 *   y solo si [group] es null: hoy no se usa, ver [GroupCoverFetcher]).
 * @param songPath ruta de un archivo de audio suyo, del que extraer el arte embebido (solo si
 *   [group] es null).
 * @param videoThumbnail nombre de la miniatura de YouTube de una de sus canciones (o de la propia
 *   canción, si es un [CoverRef] de canción), por si no hay arte embebido (solo si [group] es
 *   null).
 * @param group presente SOLO en un [CoverRef] de álbum/artista/productor (nunca en el de una
 *   canción): qué collage resolver si no hay imagen propia. Ver [GroupCoverSource].
 */
data class CoverRef(
    val ownImage: String?,
    val songImage: String? = null,
    val songPath: String? = null,
    val videoThumbnail: String? = null,
    val group: GroupCoverSource? = null
)

/** Qué álbum/artista/productor resolver en [GroupCoverFetcher]: sus canciones salen de una tabla
 * de cruce distinta según [kind] (ver `LibraryDao.collageCandidatesForAlbum`/`...ForArtist`/
 * `...ForProducer`). Artista y productor se tratan igual, como manda el proyecto; solo cambia a
 * qué tabla de cruce se pregunta. */
data class GroupCoverSource(val kind: GroupKind, val id: Long)

enum class GroupKind { ALBUM, ARTIST, PRODUCER }

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
        val bytes = extractEmbeddedArt(file.absolutePath)
            ?: fallbackThumbnail?.takeIf { it.exists() }?.readBytes()
            ?: return null
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

/** Arte embebido de un archivo de audio, o null si no tiene (o no se ha podido leer). Lo usan
 * tanto [AudioCoverFetcher] (una sola canción) como [GroupCoverFetcher] (varias, para el
 * collage), así que vive aparte en vez de duplicarse en los dos. */
private fun extractEmbeddedArt(path: String): ByteArray? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        retriever.embeddedPicture
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/**
 * Fetcher de Coil que resuelve la carátula de un álbum/artista/productor SIN imagen propia (ver
 * [CoverArt.cover]): intenta un collage con las carátulas de varias de sus canciones y, si no da
 * para uno, cae en la de una sola. Va en un Fetcher (no en [CoverArt.cover], que es síncrona)
 * porque hace falta consultar la base de datos y leer archivos, y así ese trabajo queda fuera del
 * hilo principal y se cancela solo si la vista que lo pidió se recicla (Coil ya se encarga).
 *
 * Las canciones candidatas llegan YA en el orden en que el usuario las vería al entrar en la
 * ficha (ver `LibraryDao.collageCandidatesForAlbum`/`...ForArtist`/`...ForProducer`): el collage
 * se forma con las primeras que tengan carátula, sin contar duplicadas.
 *
 * Sin tope de tamaño a propósito: sigue creciendo (2×2, 3×3, 4×4...) mientras haya suficientes
 * canciones con carátula DISTINTA. "Distinta" se decide por el CONTENIDO (hash de los bytes), no
 * por de qué canción viene: así un álbum entero con el mismo arte embebido en cada pista no
 * repite la misma imagen varias veces en el collage.
 */
class GroupCoverFetcher(
    private val source: GroupCoverSource,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val context = options.context
        val dao = UltiMusicDatabase.get(context).libraryDao()
        val candidates = when (source.kind) {
            GroupKind.ALBUM -> dao.collageCandidatesForAlbum(source.id)
            GroupKind.ARTIST -> dao.collageCandidatesForArtist(source.id)
            GroupKind.PRODUCER -> dao.collageCandidatesForProducer(source.id)
        }

        val dir = CoverArt.imagesDir(context)
        val seenHashes = HashSet<String>()
        val distinctCovers = ArrayList<ByteArray>()
        for (candidate in candidates) {
            val bytes = resolveCandidateBytes(dir, candidate) ?: continue
            if (seenHashes.add(md5(bytes))) distinctCovers.add(bytes)
        }

        val side = sqrt(distinctCovers.size.toDouble()).toInt()
        val targetPx = targetSidePx(options)
        val bitmap = if (side >= 2) {
            composeCollage(distinctCovers.take(side * side), side, targetPx)
        } else {
            distinctCovers.firstOrNull()?.let { decodeSampled(it, targetPx) }
        } ?: return null

        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    /** Carátula de UNA canción candidata: misma cadena que [CoverArt.cover] usa para una canción
     * suelta (imagen propia de la canción → arte embebido de su archivo → su miniatura de
     * YouTube), pero devolviendo los BYTES en vez de un dato para Coil, porque aquí hace falta
     * decidir duplicados y componer el collage a mano. */
    private fun resolveCandidateBytes(dir: File, candidate: CollageCandidateRow): ByteArray? {
        candidate.imageName?.let { name ->
            val file = File(dir, name)
            if (file.exists()) runCatching { file.readBytes() }.getOrNull()?.let { return it }
        }
        extractEmbeddedArt(candidate.filePath)?.let { return it }
        candidate.videoThumbnailName?.let { name ->
            val file = File(dir, name)
            if (file.exists()) runCatching { file.readBytes() }.getOrNull()?.let { return it }
        }
        return null
    }

    class Factory : Fetcher.Factory<GroupCoverSource> {
        override fun create(data: GroupCoverSource, options: Options, imageLoader: ImageLoader): Fetcher =
            GroupCoverFetcher(data, options)
    }
}

/** Huella MD5 de [bytes], en hexadecimal: suficiente para decidir "misma imagen" sin tener que
 * comparar arrays enteros a mano. */
private fun md5(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

/** Lado en píxeles con el que componer el collage (o decodificar la carátula única de reserva):
 * el que pida la vista que lo carga ([Options.size]), o [DEFAULT_COLLAGE_SIDE_PX] si la pide
 * "original" (no tiene sentido componer una imagen sintética a resolución de pantalla completa). */
private fun targetSidePx(options: Options): Int {
    val width = (options.size.width as? Dimension.Pixels)?.px
    val height = (options.size.height as? Dimension.Pixels)?.px
    return (listOfNotNull(width, height).minOrNull() ?: DEFAULT_COLLAGE_SIDE_PX)
        .coerceAtLeast(MIN_COLLAGE_SIDE_PX)
}

/** Decodifica [bytes] recortados al cuadrado central, reduciendo la resolución de origen (con
 * `inSampleSize`) para no cargar una imagen entera cuando la casilla de destino es mucho más
 * pequeña. */
private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    return bitmap.centerCropSquare()
}

/** Recorta [this] al cuadrado central, mismo criterio que
 * [com.untar.ultimusic.data.LibraryRepository.downloadVideoThumbnail] para la miniatura de
 * YouTube: así ninguna carátula no cuadrada queda estirada. */
private fun Bitmap.centerCropSquare(): Bitmap {
    val side = min(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}

/** Compone [cells] (ya cuadradas, ver [decodeSampled]) en un único bitmap de [side] × [side]
 * casillas, cada una de [targetSidePx] / [side] píxeles de lado. */
private fun composeCollage(cells: List<ByteArray>, side: Int, targetSidePx: Int): Bitmap {
    val cellPx = (targetSidePx / side).coerceAtLeast(1)
    val collage = Bitmap.createBitmap(cellPx * side, cellPx * side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(collage)
    cells.forEachIndexed { index, bytes ->
        val cell = decodeSampled(bytes, cellPx) ?: return@forEachIndexed
        val row = index / side
        val col = index % side
        val dest = Rect(col * cellPx, row * cellPx, (col + 1) * cellPx, (row + 1) * cellPx)
        canvas.drawBitmap(cell, null, dest, null)
    }
    return collage
}

private const val DEFAULT_COLLAGE_SIDE_PX = 512
private const val MIN_COLLAGE_SIDE_PX = 64

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

/**
 * Clave de caché para [GroupCoverSource]. Sin ella, Coil no le pone ninguna clave a este tipo de
 * dato (solo sabe derivarla de un [File]/[AudioCover]: ver [CoverFileKeyer]/[AudioCoverKeyer]) y
 * por tanto NO cachea el collage en memoria: [GroupCoverFetcher] se ejecutaría entero (consulta a
 * la base de datos, lectura de archivos, hash y composición del bitmap) en cada bind de la vista,
 * aunque nada haya cambiado -por ejemplo, al hacer scroll arriba y abajo por la rejilla de álbumes.
 *
 * Se incluye [CoverArt.revision] en la clave para que la caché siga siendo correcta: es el mismo
 * contador que ya sube cada vez que un editor guarda una carátula (ver su comentario), así que
 * editar la carátula de una canción invalida al instante el collage de su álbum/artista/productor
 * -y el de cualquier otro, ya que el contador es único para toda la app-, mientras que cualquier
 * repintado que NO venga de una edición (scroll, rotar la pantalla...) sí reaprovecha el bitmap ya
 * compuesto.
 */
class GroupCoverKeyer : Keyer<GroupCoverSource> {
    override fun key(data: GroupCoverSource, options: Options): String =
        "${data.kind}:${data.id}:${CoverArt.revision.value}"
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
                    add(GroupCoverFetcher.Factory())
                    add(CoverFileKeyer())
                    add(AudioCoverKeyer())
                    add(GroupCoverKeyer())
                }
                .build()
                .also { instance = it }
        }
}
