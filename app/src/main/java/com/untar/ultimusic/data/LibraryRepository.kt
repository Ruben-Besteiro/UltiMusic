package com.untar.ultimusic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import coil.request.ImageRequest
import com.untar.ultimusic.R
import com.untar.ultimusic.data.db.AlbumEdit
import com.untar.ultimusic.data.db.LibraryDao
import com.untar.ultimusic.data.db.UltiMusicDatabase
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.LibraryRootEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongTagCrossRef
import com.untar.ultimusic.data.db.entities.TagEntity
import com.untar.ultimusic.data.db.relations.ArtistChannelCandidateRow
import com.untar.ultimusic.data.db.toDomain
import com.untar.ultimusic.data.remote.YouTubeStatsApi
import com.untar.ultimusic.data.remote.YouTubeStatsRefresh
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.GenreSummary
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.model.LibraryRoot
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.SystemTagKey
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.LanguageDetector
import com.untar.ultimusic.util.LyricsCache
import com.untar.ultimusic.util.SafStorage
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fachada de la biblioteca: la ÚNICA fuente de verdad de los modelos. Orquesta el escaneo del
 * filesystem ([MusicScanner]) y la base de datos ([UltiMusicDatabase]), expone flujos de dominio
 * que observa la UI y ofrece las operaciones de edición (que se reflejan al instante vía Room).
 */
class LibraryRepository private constructor(
    private val dao: LibraryDao,
    private val appContext: Context
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Cómo se comparten los flujos de biblioteca de abajo entre TODOS sus consumidores.
     *
     * Antes cada flujo era frío: cada `collect` (pestaña Canciones, Artistas -que también mira
     * [songs]-, Géneros, Etiquetas x3, buscador, Recount, el servicio de reproducción, el editor...)
     * lanzaba SU PROPIA consulta `observeSongs()` -la biblioteca entera con 6 relaciones- y su propio
     * `toDomain()`, y lo repetía en cada escritura en la BD: una sola edición de metadatos disparaba
     * una decena de lecturas completas de la fonoteca a la vez, y el mapeo corría en el hilo de quien
     * colectaba (casi siempre el principal). Compartidos en [observerScope] (IO), hay UNA consulta y
     * UN mapeo por cambio, fuera del hilo principal, y todos reciben el mismo resultado.
     *
     * `replayExpirationMillis = 0`: cuando ya no queda nadie mirando (pasados 5 s), se tira el último
     * valor en vez de guardarlo, para que quien vuelva a suscribirse más tarde nunca reciba una lista
     * vieja de antes de una edición hecha mientras nadie miraba.
     */
    private val sharing = SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0)

    /**
     * Reenlaza, UNA sola vez por instalación, las imágenes de `UltiMusic/images` que ninguna fila
     * tiene asignadas, a las canciones y álbumes SIN imagen cuyo nombre esperado
     * (`<artistas> - <título>.img`, ver [CoverArt.imageBaseName]) coincide exactamente con un archivo.
     * Recupera las carátulas de filas que se borraron y volvieron a catalogarse de cero (ver
     * [com.untar.ultimusic.data.db.entities.SongEntity.missingSince]); el archivo seguía en disco y
     * solo faltaba el enlace.
     *
     * Conservador a propósito: solo nombre EXACTO con extensión `img`; nunca pisa una imagen ya
     * asignada; un nombre que reclamarían dos canciones distintas se salta (no se sabe de cuál es);
     * y un archivo solo se da a UN dueño (primero canciones, luego álbumes) porque renombrar o borrar
     * la imagen de uno afectaría al otro. Devuelve cuántas filas ha reenlazado.
     */
    suspend fun relinkOrphanImages(): Int = withContext(Dispatchers.IO) {
        if (SafStorage.isOrphanImageRelinkDone(appContext)) return@withContext 0
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@withContext 0
        val dir = CoverArt.imagesDocPath(appContext) ?: return@withContext 0
        val files = SafStorage.listChildren(appContext, treeUri, dir)
            .filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
        // Carpeta aún sin indexar (ver imagesDocPath): no se da por hecho, se reintenta otra vez.
        if (files.isEmpty()) return@withContext 0

        val taken = dao.allUsedImageNames().toHashSet()
        var relinked = 0

        suspend fun relink(
            rows: List<com.untar.ultimusic.data.db.relations.TitledRow>,
            artistRows: List<com.untar.ultimusic.data.db.relations.OwnerNameRow>,
            assign: suspend (Long, String) -> Unit
        ) {
            val artistsByOwner = artistRows.groupBy({ it.ownerId }, { it.name })
            val wanted = rows.map { it to "${CoverArt.imageBaseName(artistsByOwner[it.id].orEmpty(), it.title)}.img" }
            val claims = wanted.groupingBy { it.second }.eachCount()
            for ((row, name) in wanted) {
                if (name !in files || name in taken || claims[name] != 1) continue
                assign(row.id, name)
                taken.add(name)
                relinked++
            }
        }
        relink(dao.songsWithoutImage(), dao.songArtistNames()) { id, name -> dao.setSongImageIfEmpty(id, name) }
        relink(dao.albumsWithoutImage(), dao.albumArtistNames()) { id, name -> dao.setAlbumImageIfEmpty(id, name) }

        SafStorage.markOrphanImageRelinkDone(appContext)
        relinked
    }

    /** Se incrementa al conceder la carpeta `UltiMusic` para que [songs] se recalcule (esa raíz no
     *  vive en `library_roots`, así que ninguna escritura en Room avisaría del cambio). Debe
     *  declararse ANTES de [songs]: su inicializador la lee al construir la clase. */
    private val grantEpoch = MutableStateFlow(0)

    /**
     * "Puerta" del primer arranque: mientras está cerrada, [songs], [albums] y los artistas salen
     * VACÍOS (y con ellos todo lo que se deriva: géneros, etiquetas...). Se cierra al conceder la
     * carpeta `UltiMusic` ([grantUltiMusicRoot]) y se abre cuando el usuario termina el segundo
     * diálogo ("elige tu carpeta de música", ver [releaseFirstRunGate]). Sin esto, una base de datos
     * restaurada de una copia enseñaba al instante filas de carpetas que aún no se han concedido
     * (o de archivos que ya no están donde decían) hasta que acababa el primer escaneo.
     *
     * Arranca cerrada si el segundo diálogo sigue pendiente (la restauración de la copia reinicia
     * el proceso entre medias). Debe declararse ANTES de [songs].
     */
    private val firstRunGate = MutableStateFlow(SafStorage.isMusicFolderPromptPending(appContext))

    /** Canciones persistidas, reactivas: cualquier edición reemite la lista. `distinctUntilChanged`
     *  porque Room reemite ante CUALQUIER escritura en las tablas que lee (aunque el resultado sea el
     *  mismo, p. ej. al registrar una reproducción): así nadie aguas abajo recalcula ni repinta nada
     *  si la lista no ha cambiado de verdad. */
    val songs: Flow<List<Song>> =
        // Solo se listan las canciones que SE PUEDEN REPRODUCIR: las que cuelgan de una carpeta
        // concedida y legible ahora mismo. Tras reinstalar (la BD vuelve de la copia con filas de
        // Download/Music, pero solo se ha concedido UltiMusic) o con una fila antigua de ruta
        // absoluta aún sin re-enlazar, la fila existe pero no hay forma de abrir su archivo: mostrarla
        // sería prometer algo que luego falla en silencio. Se repinta sola al cambiar las raíces
        // (tabla library_roots) o al conceder UltiMusic ([grantEpoch]).
        // También se ocultan (sin borrarlas de la BD) las más cortas que la duración mínima de
        // Ajustes (ver [MinDurationPreferences]); como todo lo demás sale de aquí, álbumes,
        // artistas y fichas las dejan de contar solas.
        combine(
            dao.observeSongs(), dao.observeLibraryRoots(), grantEpoch, firstRunGate, MinDurationPreferences.seconds
        ) { list, _, _, gated, minSeconds ->
            refreshSafRegistry()
            val prefixes = readableRootPrefixes()
            lastReadablePrefixes = prefixes.toSet()
            if (gated) emptyList()
            else list.filter { isUnderRoots(it.song.filePath, prefixes) && isLongEnough(it.song.duration, minSeconds) }
                .map { it.toDomain() }
        }
            .distinctUntilChanged()
            .shareIn(observerScope, sharing, replay = 1)

    /** Las raíces legibles con las que se calculó por última vez [songs] (null = aún no se ha calculado). */
    @Volatile
    private var lastReadablePrefixes: Set<String>? = null

    /**
     * Vuelve a mirar qué raíces se pueden leer y, si han cambiado desde el último cálculo de
     * [songs] (permiso revocado o concedido desde fuera de la app, p. ej. en los ajustes del
     * sistema), la recalcula: las canciones que ya no se pueden abrir dejan de listarse, y vuelven
     * si el permiso vuelve. NO borra nada de la BD (eso es cosa de [reconcile], que además se salta
     * si alguna carpeta no se puede leer). La llama `MainActivity.onResume`.
     */
    suspend fun recheckRootPermissions() {
        val before = lastReadablePrefixes ?: return
        refreshSafRegistry()
        if (readableRootPrefixes().toSet() != before) grantEpoch.value++
    }

    /** Prefijos de docPath de las raíces concedidas que el sistema aún deja leer. */
    private fun readableRootPrefixes(): List<String> =
        SafStorage.grantedRoots()
            .filter { (_, treeUri) -> SafStorage.isRootReadable(appContext, treeUri) }
            .map { it.first }

    /** ¿Cuelga [filePath] de alguna de [prefixes]? Una ruta absoluta (empieza por "/") es una fila
     *  de antes de SAF sin re-enlazar: nunca se puede abrir, así que nunca cuelga de nada. */
    private fun isUnderRoots(filePath: String, prefixes: List<String>): Boolean {
        if (filePath.startsWith("/")) return false
        return prefixes.any { it.isEmpty() || filePath == it || filePath.startsWith("$it/") }
    }

    /** Filtra [songs] a las que se pueden reproducir ahora mismo (ver [songs]). La usa el servicio
     *  al restaurar la cola guardada: una canción que ya no se lista no debe reaparecer ahí. */
    suspend fun playableOnly(songs: List<Song>): List<Song> {
        refreshSafRegistry()
        val prefixes = readableRootPrefixes()
        val minSeconds = MinDurationPreferences.seconds.value
        return songs.filter { isUnderRoots(it.filePath, prefixes) && isLongEnough(it.duration, minSeconds) }
    }

    /** Duración (ms) desconocida (0) cuenta como suficiente: mejor listarla que perderla. */
    private fun isLongEnough(durationMs: Long, minSeconds: Int): Boolean =
        durationMs <= 0L || durationMs >= minSeconds * 1000L

    /** Nombres ya existentes, para el autocompletado del editor de metadatos. */
    val artistNames: Flow<List<String>> = dao.observeArtistNames()
    val albumTitles: Flow<List<String>> = dao.observeAlbumTitles()
    val producerNames: Flow<List<String>> = dao.observeProducerNames()

    /** Subcarpetas de la lista gris (ajustes > Lista gris), reactivo: un cambio en cualquiera repinta la lista. */
    val greylistFolders: Flow<List<GreylistFolder>> =
        dao.observeGreylistFolders().map { list -> list.map { it.toDomain() } }

    /** Carpetas raíz adicionales de la fonoteca (ajustes > Carpetas de la fonoteca), reactivo. */
    val libraryRoots: Flow<List<LibraryRoot>> =
        dao.observeLibraryRoots().map { list -> list.map { it.toDomain() } }

    // --- Pestañas de Álbumes / Artistas ---
    //
    // El `null` de estas llamadas significa "sin filtrar por id": devuelve la lista entera. Con un
    // id concreto, la misma consulta sirve para la ficha de detalle (ver los métodos de abajo).

    // Álbumes, artistas y sus fichas SOLO cuentan canciones que se pueden reproducir (las de [songs]:
    // bajo una carpeta concedida y legible, ni ocultas por la lista gris ni perdidas). Las consultas
    // de Room no saben de permisos, así que aquí se descarta lo que no tiene ninguna canción
    // accesible y se recalculan sus cifras con las que sí. [songs] sale vacía mientras la puerta
    // del primer arranque ([firstRunGate]) está cerrada, y con ella todo lo de aquí.

    val albums: Flow<List<AlbumSummary>> =
        combine(dao.observeAlbumSummaries(null), songs) { rows, playable ->
            rows.map { it.toDomain() }.restrictToPlayable(playable)
        }
            .distinctUntilChanged()
            .shareIn(observerScope, sharing, replay = 1)

    /** Deja solo los álbumes con alguna canción de [playable], con su nº de canciones y duración
     *  recalculados sobre esas. */
    private fun List<AlbumSummary>.restrictToPlayable(playable: List<Song>): List<AlbumSummary> {
        val count = HashMap<Long, Int>()
        val duration = HashMap<Long, Long>()
        for (song in playable) {
            for (albumId in song.albums.map { it.album.id }.distinct()) {
                count[albumId] = (count[albumId] ?: 0) + 1
                duration[albumId] = (duration[albumId] ?: 0L) + song.duration
            }
        }
        return mapNotNull { album ->
            val n = count[album.id] ?: return@mapNotNull null
            album.copy(songCount = n, totalDuration = duration[album.id] ?: 0L)
        }
    }

    /** Gemela de [restrictToPlayable] para artistas (`albumCount` se recalcula aparte, con
     *  [withAlbumCount], sobre los álbumes ya restringidos). */
    private fun List<PersonSummary>.restrictArtistsToPlayable(playable: List<Song>): List<PersonSummary> {
        val count = HashMap<Long, Int>()
        val duration = HashMap<Long, Long>()
        for (song in playable) {
            for (artistId in song.artists.map { it.id }.distinct()) {
                count[artistId] = (count[artistId] ?: 0) + 1
                duration[artistId] = (duration[artistId] ?: 0L) + song.duration
            }
        }
        return mapNotNull { artist ->
            val n = count[artist.id] ?: return@mapNotNull null
            artist.copy(songCount = n, totalDuration = duration[artist.id] ?: 0L)
        }
    }

    /** Sin agrupar por "Otros" (ver [artistGrouping]): la lista cruda tal cual la guarda Room, una
     *  fila por artista real. El nº de álbumes se recalcula aquí con el string de artistas de cada
     *  álbum (ver [albumCountsByArtist]), el mismo criterio que el carrusel de su ficha. */
    private val rawArtists: Flow<List<PersonSummary>> =
        combine(dao.observeArtistSummaries(null), albums, songs) { rows, all, playable ->
            val counts = albumCountsByArtist(all)
            rows.map { it.toDomain() }.restrictArtistsToPlayable(playable).map { it.withAlbumCount(counts) }
        }

    /**
     * Agrupa bajo un perfil "Otros" (ver [ArtistGroupingPreferences]) los artistas con el umbral
     * guardado de canciones O MENOS (p. ej. con el umbral en 1, los artistas con una sola canción
     * pasan a "Otros"): sus canciones se cuentan juntas y dejan de listarse sueltos en la pestaña
     * Artistas. Es puramente una vista calculada aquí, en Kotlin -no hay ninguna fila
     * "Otros" en Room, ni se reasigna el artista de ninguna canción-, así que no hace falta ninguna
     * migración de base de datos para esto.
     *
     * Reacciona tanto a cambios en la fonoteca ([rawArtists]) como al propio umbral
     * ([ArtistGroupingPreferences.threshold]), que es un `StateFlow` justo para que cambiarlo en
     * Ajustes se note al instante en la pestaña, sin tener que volver a entrar en ella.
     *
     * Guarda las tres cosas que salen de esta cuenta -la lista ya agrupada para la pestaña, el
     * conjunto de ids "pequeños" (para redirigir a quien navegue directo a uno de ellos, ver
     * [DetailViewModel][com.untar.ultimusic.ui.library.DetailViewModel]) y el propio perfil "Otros"
     * (para su ficha de detalle)- en un solo cálculo, para que las tres sean siempre consistentes
     * entre sí.
     */
    private data class ArtistGrouping(
        val visible: List<PersonSummary>,
        val others: PersonSummary?,
        val smallIds: Set<Long>
    )

    private val artistGrouping: Flow<ArtistGrouping> =
        combine(rawArtists, songs, ArtistGroupingPreferences.threshold) { list, allSongs, threshold ->
            val small = if (threshold > 0) list.filter { it.songCount <= threshold } else emptyList()
            if (small.isEmpty()) return@combine ArtistGrouping(list, null, emptySet())
            val smallIds = small.mapTo(mutableSetOf()) { it.id }
            // Las cifras de la cabecera de "Otros" salen de las MISMAS canciones que lista su ficha
            // (ver [artistSongs]), no de sumar el songCount/albumCount/totalDuration de cada artista
            // pequeño por separado: sumarlos así contaría dos veces una colaboración entre dos
            // artistas pequeños, y de paso seguiría contando una canción que en realidad NO sale en
            // "Otros" por tener también un artista grande (ver [artistSongs]).
            val othersSongs = allSongs.filter { it.artists.isNotEmpty() && it.artists.all { a -> a.id in smallIds } }
            val others = PersonSummary(
                id = PersonSummary.OTHERS_ARTIST_ID,
                name = appContext.getString(R.string.artist_others),
                songCount = othersSongs.size,
                albumCount = othersSongs.flatMap { it.albums }.map { it.album.id }.distinct().size,
                totalDuration = othersSongs.sumOf { it.duration },
                // Sin imagen propia: agrupa a varios artistas reales a la vez, así que no hay un
                // collage único que resolver (ver CoverRef.group, pensado para UN solo artista).
                // Cae en el recuadro de reserva de cada ImageView, igual que un artista sin ninguna
                // canción con carátula.
                cover = CoverRef(ownImage = null),
                popularity = null
            )
            ArtistGrouping(list.filterNot { it.id in smallIds } + others, others, smallIds)
        }
            .distinctUntilChanged()
            .shareIn(observerScope, sharing, replay = 1)

    val artists: Flow<List<PersonSummary>> = artistGrouping.map { it.visible }

    /** Ids de los artistas agrupados dentro de "Otros" ahora mismo. La usa
     *  [DetailViewModel][com.untar.ultimusic.ui.library.DetailViewModel] para redirigir a "Otros"
     *  cualquier navegación directa a uno de ellos (menú de una canción, cabecera de un álbum,
     *  búsqueda...), no solo la propia pestaña Artistas. */
    val smallArtistIds: Flow<Set<Long>> = artistGrouping.map { it.smallIds }

    private val othersArtist: Flow<PersonSummary?> = artistGrouping.map { it.others }

    /**
     * Pestaña de Géneros. A diferencia de las tres de arriba, no sale de una consulta SQL: el
     * género no vive en su propia tabla (ver [com.untar.ultimusic.data.db.Converters]), solo como
     * lista de texto dentro de cada canción, así que se deriva aquí, en Kotlin, a partir de
     * [songs]. Se agrupa por el texto EXACTO —igual que el resto de nombres de la aplicación, donde
     * dos grafías distintas cuentan como cosas distintas— y se ordena igual que las demás pestañas.
     *
     * [GenreSummary.totalDuration] suma la duración de cada canción que lleve ese género: una
     * canción con varios géneros cuenta entera en cada uno de ellos, igual que [songCount].
     */
    val genres: Flow<List<GenreSummary>> = songs.map { list ->
        // name -> (canciones, duración total). LinkedHashMap solo por determinismo al recorrerlo;
        // el orden real lo pone el sortedBy de abajo.
        val byGenre = LinkedHashMap<String, Pair<Int, Long>>()
        for (song in list) {
            for (genre in song.genres) {
                if (genre.isBlank()) continue
                val (count, duration) = byGenre[genre] ?: (0 to 0L)
                byGenre[genre] = (count + 1) to (duration + song.duration)
            }
        }
        byGenre.map { (name, countAndDuration) -> GenreSummary(name, countAndDuration.first, countAndDuration.second) }
            .sortedBy { it.name.lowercase() }
    }
        .distinctUntilChanged()
        .shareIn(observerScope, sharing, replay = 1)

    /** Canciones que llevan el género [genre] (comparación exacta, como [genres]). */
    fun songsOfGenre(genre: String): Flow<List<Song>> =
        songs.map { list -> list.filter { genre in it.genres } }.flowOn(Dispatchers.Default)

    // --- Pestaña de Etiquetas ---
    //
    // Las 5 etiquetas predefinidas (ver Migrations.kt.seedDefaultTags) están sembradas en la tabla
    // `tags` desde la instalación/migración, así que [tagEntities] siempre las trae; lo único que se
    // calcula aquí es CUÁNTAS canciones tiene cada una y CUÁLES. Vídeo sincronizado y Remix / Cover
    // usan membresía real ([tagCrossRefs], escrita desde
    // SongTagsDialogFragment/TagPickerDialogFragment, o sincronizada sola por syncSyncedVideoTag/
    // syncRemixCoverTag); las otras 3 se derivan al vuelo de [songs] en [resolveSongsOfTag]. "En
    // ninguna lista" necesita saber qué
    // canciones están en alguna Lista, dato que vive en PlaylistRepository (archivos .txt, ajeno a
    // este repositorio): en vez de leerlo directamente -lo que acoplaría LibraryRepository a
    // PlaylistRepository y rompería la reactividad instantánea, ya que los .txt no reemiten solos-,
    // se recibe como parámetro externo (mismo patrón "bind" que
    // CollectionDetailViewModel.bindPlaylistSongs; ver TagsViewModel.bindPlaylistFilenames, que es
    // quien lo conecta de verdad al tick de PlaylistsViewModel).

    private val tagEntities: Flow<List<TagEntity>> =
        dao.observeTags().distinctUntilChanged().shareIn(observerScope, sharing, replay = 1)
    private val tagCrossRefs: Flow<List<SongTagCrossRef>> =
        dao.observeSongTagCrossRefs().distinctUntilChanged().shareIn(observerScope, sharing, replay = 1)

    /** Una etiqueta ya resuelta contra la biblioteca: su resumen para pintar, y el conjunto de ids de
     *  canción que la tienen (real o calculada, ver [resolveSongsOfTag]) — este último es lo que
     *  necesita [tagsOfSong] para saber si UNA canción concreta la tiene, sin repetir la resolución. */
    private data class ResolvedTag(val summary: TagSummary, val matchedSongIds: Set<Long>)

    /** Las predefinidas se siembran con el nombre del idioma de ese momento (ver `seedDefaultTags`), así
     *  que el nombre guardado en BD queda congelado; se resuelve al vuelo desde `strings.xml` según
     *  [TagEntity.systemKey] para que siga el idioma actual. Las personalizadas conservan su nombre. */
    private fun localizedTagName(tag: TagEntity): String {
        // Etiqueta de idioma: su nombre es el idioma en español (ver LanguageDetector.detect).
        if (tag.isAutoAssigned) return LanguageDetector.localizedName(tag.name) ?: tag.name
        val res = when (tag.systemKey?.let { runCatching { SystemTagKey.valueOf(it) }.getOrNull() }) {
            SystemTagKey.RECENTLY_ADDED -> R.string.tag_recently_added_name
            SystemTagKey.NOT_IN_PLAYLIST -> R.string.tag_not_in_playlist_name
            SystemTagKey.NO_CUSTOM_TAGS -> R.string.tag_no_custom_tags_name
            SystemTagKey.REMIX_COVER -> R.string.tag_remix_cover_name
            null -> return tag.name
        }
        return appContext.getString(res)
    }

    private fun resolveAllTags(
        tags: List<TagEntity>,
        crossRefs: List<SongTagCrossRef>,
        allSongs: List<Song>,
        inPlaylist: Set<String>
    ): List<ResolvedTag> {
        val members = crossRefs.groupBy({ it.tagId }, { it.songId }).mapValues { it.value.toSet() }
        val songsWithCustomTag = songsWithCustomTag(tags, crossRefs)
        return tags.map { tag ->
            val resolved = resolveSongsOfTag(tag, allSongs, members[tag.id].orEmpty(), inPlaylist, songsWithCustomTag)
            val summary = TagSummary(
                tag.id, localizedTagName(tag), tag.colorArgb, resolved.size, resolved.sumOf { it.duration }, tag.systemKey,
                tag.isAutoAssigned
            )
            ResolvedTag(summary, resolved.mapTo(mutableSetOf()) { it.id })
        }
    }

    /** Canciones con AL MENOS una etiqueta personalizada de verdad (`systemKey == null` y no
     *  [TagEntity.isAutoAssigned]), para resolver "Sin etiquetas personalizadas" (ver
     *  [resolveSongsOfTag]). Una etiqueta de idioma no cuenta: la pone la app sola, no el usuario, así
     *  que una canción con solo idioma detectado sigue contando como "sin etiquetas personalizadas"
     *  hasta que el usuario le ponga una de verdad. Se calcula una sola vez por resolución en vez de
     *  dentro de cada `tag.map`, porque no depende de qué etiqueta se esté resolviendo. */
    private fun songsWithCustomTag(tags: List<TagEntity>, crossRefs: List<SongTagCrossRef>): Set<Long> {
        val customTagIds = tags.filter { it.systemKey == null && !it.isAutoAssigned }.mapTo(mutableSetOf()) { it.id }
        return crossRefs.filter { it.tagId in customTagIds }.mapTo(mutableSetOf()) { it.songId }
    }

    /** Resúmenes de las 5 etiquetas predefinidas (más las personalizadas que haya en el futuro), para
     *  la pestaña de Etiquetas. [filenamesInAnyPlaylist] es el "bind" externo descrito arriba. */
    fun tagSummaries(filenamesInAnyPlaylist: Flow<Set<String>>): Flow<List<TagSummary>> =
        combine(tagEntities, tagCrossRefs, songs, filenamesInAnyPlaylist) { tags, crossRefs, allSongs, inPlaylist ->
            resolveAllTags(tags, crossRefs, allSongs, inPlaylist).map { it.summary }
        }.flowOn(Dispatchers.Default)

    /** Canciones de UNA etiqueta, por [id] (no por nombre: ver la decisión de diseño de usar el id
     *  como clave de una etiqueta en la ficha de detalle). */
    fun songsOfTag(id: Long, filenamesInAnyPlaylist: Flow<Set<String>>): Flow<List<Song>> =
        combine(tagEntities, tagCrossRefs, songs, filenamesInAnyPlaylist) { tags, crossRefs, allSongs, inPlaylist ->
            val tag = tags.firstOrNull { it.id == id } ?: return@combine emptyList()
            val memberIds = crossRefs.filter { it.tagId == tag.id }.map { it.songId }.toSet()
            resolveSongsOfTag(tag, allSongs, memberIds, inPlaylist, songsWithCustomTag(tags, crossRefs))
        }.flowOn(Dispatchers.Default)

    /**
     * Etiquetas de UNA canción (ver [SongTagsDialogFragment][com.untar.ultimusic.ui.library.SongTagsDialogFragment]):
     * tanto las de membresía real como las calculadas que la canción cumpla ahora mismo (p. ej. si es
     * una de las 20 más recientes, "Descargadas recientemente" sale en su lista, aunque no tenga
     * ninguna fila en `song_tag`).
     */
    fun tagsOfSong(songId: Long, filenamesInAnyPlaylist: Flow<Set<String>>): Flow<List<TagSummary>> =
        combine(tagEntities, tagCrossRefs, songs, filenamesInAnyPlaylist) { tags, crossRefs, allSongs, inPlaylist ->
            resolveAllTags(tags, crossRefs, allSongs, inPlaylist)
                .filter { songId in it.matchedSongIds }
                .map { it.summary }
                .sortedBy { it.name }
        }.flowOn(Dispatchers.Default)

    /**
     * Igual que [tagsOfSong] pero para TODAS las canciones a la vez, indexadas por id (reales +
     * calculadas). La usa la pestaña Canciones para pintar la tercera línea de cada fila (ver
     * [com.untar.ultimusic.ui.songs.SongsAdapter]): resuelve [resolveAllTags] UNA sola vez para toda
     * la biblioteca en vez de una vez por fila visible, que sería tan caro como abrir la pestaña
     * Etiquetas una vez por canción.
     */
    fun songTagsById(filenamesInAnyPlaylist: Flow<Set<String>>): Flow<Map<Long, List<TagSummary>>> =
        combine(tagEntities, tagCrossRefs, songs, filenamesInAnyPlaylist) { tags, crossRefs, allSongs, inPlaylist ->
            val bySong = HashMap<Long, MutableList<TagSummary>>()
            for (resolved in resolveAllTags(tags, crossRefs, allSongs, inPlaylist)) {
                for (songId in resolved.matchedSongIds) {
                    bySong.getOrPut(songId) { mutableListOf() }.add(resolved.summary)
                }
            }
            bySong.mapValues { (_, list) -> list.sortedBy { it.name } }
        }.flowOn(Dispatchers.Default)

    /** Añade/quita la membresía real de una canción en una etiqueta (ver
     *  [SongTagsDialogFragment][com.untar.ultimusic.ui.library.SongTagsDialogFragment]/
     *  [TagPickerDialogFragment][com.untar.ultimusic.ui.library.TagPickerDialogFragment]). Solo tiene
     *  sentido para etiquetas SIN calcular (Favoritos o una personalizada): las 3 calculadas no
     *  tienen fila que insertar/borrar, y la UI ya no deja llegar hasta aquí para ellas (sin X que
     *  quitar, sin sitio en el buscador de añadir). No hace falta refrescar nada a mano:
     *  [dao.observeSongTagCrossRefs] reemite sola al escribir. */
    suspend fun addSongToTag(songId: Long, tagId: Long) = dao.insertSongTag(SongTagCrossRef(songId, tagId))
    suspend fun removeSongFromTag(songId: Long, tagId: Long) = dao.deleteSongTag(songId, tagId)

    /**
     * Igual que [addSongToTag]/[removeSongFromTag] pero para VARIAS canciones a la vez (edición
     * múltiple de etiquetas, ver
     * [SongTagsDialogFragment][com.untar.ultimusic.ui.library.SongTagsDialogFragment] con más de un
     * id): [addTagIds] se añaden a TODAS las [songIds], [removeTagIds] se quitan de TODAS, sin
     * importar si cada canción concreta ya las tenía o no -insertar una que ya tenía es un no-op
     * (`insertSongTag` usa `OnConflictStrategy.IGNORE`), quitar una que no tenía es un `DELETE` que
     * no encuentra fila-. Mismo patrón de bucle secuencial que
     * [MetadataEditorViewModel.saveMulti][com.untar.ultimusic.ui.editor.MetadataEditorViewModel.saveMulti]
     * para su propia edición múltiple: cada escritura es ya atómica de por sí, no hace falta envolver
     * el bucle entero en una transacción aparte.
     */
    suspend fun applyTagsToSongs(songIds: List<Long>, addTagIds: Set<Long>, removeTagIds: Set<Long>) {
        for (songId in songIds) {
            for (tagId in addTagIds) dao.insertSongTag(SongTagCrossRef(songId, tagId))
            for (tagId in removeTagIds) dao.deleteSongTag(songId, tagId)
        }
    }

    /** Crea una etiqueta personalizada nueva, al final del orden actual (ver [LibraryDao.maxTagSortOrder]).
     *  [name] se recorta y no puede quedar vacío tras el trim: la UI (TagEditorDialogFragment) ya
     *  deshabilita el botón de aceptar en ese caso, esto es un cinturón de seguridad, igual que hace
     *  PlaylistRepository con el nombre de una lista. No hace falta refrescar nada a mano:
     *  [dao.observeTags] reemite sola al escribir. */
    suspend fun createTag(name: String, colorArgb: Int) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val sortOrder = dao.maxTagSortOrder() + 1
        dao.insertTag(TagEntity(name = trimmed, colorArgb = colorArgb, systemKey = null, sortOrder = sortOrder))
    }

    /** Renombra/recolorea una etiqueta personalizada existente. No tiene efecto sobre una predefinida
     *  (ver [LibraryDao.updateTag], `WHERE systemKey IS NULL`); la UI no deja llegar hasta aquí para esas. */
    suspend fun updateTag(id: Long, name: String, colorArgb: Int) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        dao.updateTag(id, trimmed, colorArgb)
    }

    /** Borra una etiqueta personalizada (y, en cascada, su membresía en `song_tag`, ver el
     *  `ON DELETE CASCADE` de `SongTagCrossRef.tagId`). No tiene efecto sobre una predefinida. */
    suspend fun deleteTag(id: Long) = dao.deleteTag(id)

    /**
     * Resuelve qué canciones tiene [tag], según su tipo (ver [SystemTagKey]):
     * - Descargadas recientemente: las de [Song.dateAdded] (fecha del archivo al detectarlo por primera vez; no cambia al editarlo) de hace menos de un
     *   mes, pero nunca menos de [RECENTLY_ADDED_MIN] (si no llegan, las más recientes). 100% calculada.
     * - En ninguna lista: cuyo archivo no está en [inPlaylist] (unión de todas las Listas).
     * - Sin etiquetas personalizadas: todas las canciones EXCEPTO las que ya tengan alguna etiqueta
     *   personalizada ([songsWithCustomTag]).
     * - Remix / Cover: membresía real, [memberIds] -aquí solo se LEE; quien la mantiene
     *   sincronizada con [Song.ogTitle] es [syncRemixCoverTag]-.
     * - Una etiqueta personalizada (`systemKey == null`): pura membresía real, igual que la
     *   anterior.
     */
    private fun resolveSongsOfTag(
        tag: TagEntity,
        allSongs: List<Song>,
        memberIds: Set<Long>,
        inPlaylist: Set<String>,
        songsWithCustomTag: Set<Long>
    ): List<Song> =
        when (tag.systemKey?.let { runCatching { SystemTagKey.valueOf(it) }.getOrNull() }) {
            SystemTagKey.RECENTLY_ADDED -> {
                val sorted = allSongs.sortedByDescending { it.dateAdded }
                val cutoff = java.time.ZonedDateTime.now().minusMonths(1).toInstant().toEpochMilli()
                val lastMonth = sorted.takeWhile { it.dateAdded >= cutoff }
                if (lastMonth.size >= RECENTLY_ADDED_MIN) lastMonth else sorted.take(RECENTLY_ADDED_MIN)
            }
            SystemTagKey.NOT_IN_PLAYLIST -> allSongs.filter { it.filePath.substringAfterLast('/') !in inPlaylist }
            SystemTagKey.NO_CUSTOM_TAGS -> allSongs.filter { it.id !in songsWithCustomTag }
            SystemTagKey.REMIX_COVER -> allSongs.filter { it.id in memberIds }
            null -> allSongs.filter { it.id in memberIds }
        }

    // --- Fichas de detalle ---

    fun album(id: Long): Flow<AlbumSummary?> =
        combine(dao.observeAlbumSummaries(id), songs) { rows, playable ->
            rows.map { it.toDomain() }.restrictToPlayable(playable).firstOrNull()
        }

    /**
     * Nombres de TODOS los artistas enlazados al álbum, en el orden en que se enlazaron: a
     * diferencia de [AlbumSummary.artistName] —que junta esos mismos nombres en un solo string ya
     * listo para pintar, con sus fallbacks si el álbum no tiene ninguno enlazado— esta lista viene
     * cruda, sin fallback, porque la necesita el editor de metadatos para rellenar sus campos
     * editables (ver [DetailViewModel] y [com.untar.ultimusic.ui.editor.AlbumEditorViewModel]).
     */
    fun albumArtistNames(id: Long): Flow<List<String>> = dao.observeAlbumArtistNames(id)

    /**
     * El álbum tal cual está guardado (fila cruda + sus artistas), para el editor de metadatos de
     * álbum. A diferencia de [album] —que trae cifras agregadas de sus canciones, calculadas para la
     * ficha— aquí hace falta justo lo que el editor puede tocar: título editable, año, géneros,
     * portada propia y la lista completa de artistas enlazados.
     */
    fun albumEntityForEdit(id: Long): Flow<Pair<AlbumEntity, List<String>>?> =
        combine(dao.observeAlbumEntity(id), dao.observeAlbumArtistNames(id)) { entity, names ->
            entity?.let { it to names }
        }

    fun artist(id: Long): Flow<PersonSummary?> =
        if (id == PersonSummary.OTHERS_ARTIST_ID) othersArtist
        else combine(dao.observeArtistSummaries(id), albums, songs) { rows, all, playable ->
            val counts = albumCountsByArtist(all)
            rows.map { it.toDomain() }.restrictArtistsToPlayable(playable).firstOrNull()?.withAlbumCount(counts)
        }

    /** Canciones de un álbum, ya ordenadas por número de pista (y de disco) DENTRO de ese álbum: ver
     * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]. Solo las que se pueden reproducir
     * (ver [songs]). */
    fun albumTracks(albumId: Long): Flow<List<Song>> =
        combine(dao.observeSongsOfAlbum(albumId), songs) { list, playable ->
            val ids = playable.mapTo(HashSet(playable.size)) { it.id }
            list.map { it.toDomain() }.filter { it.id in ids }
        }

    /**
     * Canciones de un artista. Para "Otros" ([PersonSummary.OTHERS_ARTIST_ID]) no hay ninguna
     * consulta propia en Room -esa fila no existe ahí-: se filtran directamente [songs] (que ya
     * excluye la lista gris) por si TODOS sus artistas están en [smallArtistIds]. Si una
     * colaboración tiene al menos un artista "grande" (con ficha propia), la canción ya sale ahí y
     * NO se repite en "Otros": duplicarla en los dos sitios sería confuso, y "Otros" es para las
     * canciones que de otro modo no tendrían ninguna ficha donde aparecer, no para todas las que
     * tengan de paso algún colaborador pequeño. Una canción sin ningún artista tampoco entra aquí
     * (`all` sobre una lista vacía daría `true` por vacuidad): "Otros" agrupa artistas pequeños, no
     * canciones sin artista.
     */
    fun artistSongs(id: Long): Flow<List<Song>> =
        if (id == PersonSummary.OTHERS_ARTIST_ID) {
            combine(songs, smallArtistIds) { all, smallIds ->
                if (smallIds.isEmpty()) emptyList()
                else all.filter { song -> song.artists.isNotEmpty() && song.artists.all { it.id in smallIds } }
                    .sortedBy { it.title.lowercase() }
            }.flowOn(Dispatchers.Default)
        } else {
            combine(dao.observeSongsOfArtist(id), songs) { list, playable ->
                val ids = playable.mapTo(HashSet(playable.size)) { it.id }
                list.map { it.toDomain() }.filter { it.id in ids }
            }
        }

    /**
     * Álbumes de un artista, para el carrusel horizontal de su ficha: solo los que lo llevan en SU
     * PROPIO string de artistas ([AlbumSummary.artistName], separado por comas como en el resto de
     * la app; ver [AlbumSummary.hasArtist]), no todos en los que aparece alguna canción suya. Así una
     * colaboración suelta en el álbum de otro no mete ese álbum en su carrusel. Vacío para "Otros":
     * agrupa varios artistas reales a la vez, y ese carrusel es el de la discografía de uno solo.
     */
    fun artistAlbums(id: Long): Flow<List<AlbumSummary>> =
        if (id == PersonSummary.OTHERS_ARTIST_ID) flowOf(emptyList())
        else combine(dao.observeArtistSummaries(id), albums) { rows, all ->
            val name = rows.firstOrNull()?.name ?: return@combine emptyList()
            all.filter { it.hasArtist(name) }.sortedForCarousel()
        }.flowOn(Dispatchers.Default)

    /** Álbumes cuyos géneros PROPIOS (los del editor de álbum, ver [AlbumSummary.genres]) incluyen
     *  [genre], para el carrusel de la ficha de género. Comparación exacta, como [songsOfGenre]. */
    fun genreAlbums(genre: String): Flow<List<AlbumSummary>> =
        albums.map { all -> all.filter { genre in it.genres }.sortedForCarousel() }.flowOn(Dispatchers.Default)

    /** Orden de los carruseles de álbumes: por año ascendente, los que no tienen año al final, y a
     *  igualdad por título. */
    private fun List<AlbumSummary>.sortedForCarousel(): List<AlbumSummary> =
        sortedWith(compareBy<AlbumSummary> { it.year == null }.thenBy { it.year }.thenBy { it.title.lowercase() })

    /** Cuántos álbumes tiene cada artista con el mismo criterio que [artistAlbums] (su string de
     *  artistas), por nombre en minúsculas: así la cifra de la pestaña/cabecera cuadra con lo que
     *  enseña el carrusel. */
    private fun albumCountsByArtist(all: List<AlbumSummary>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (album in all) {
            album.artistName.orEmpty().split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts
    }

    private fun PersonSummary.withAlbumCount(counts: Map<String, Int>): PersonSummary =
        copy(albumCount = counts[name.trim().lowercase()] ?: 0)

    /**
     * Canciones sueltas por id, en el mismo orden que [ids] (la consulta no lo garantiza). Las
     * canciones que ya no existan se omiten. La usa [PlayerViewModel] al restaurar las colas de
     * reproducción tras un reinicio del proceso, cuando solo se guardaron los ids.
     */
    suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = dao.findSongsByIds(ids).associateBy { it.song.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    /** Canción de la fonoteca con esa ruta exacta, o null si no está catalogada. Ver [LibraryDao.findSongByPath]. */
    suspend fun songByPath(path: String): Song? = withContext(Dispatchers.IO) {
        dao.findSongByPath(path)?.toDomain()
    }

    /**
     * Refresca el registro en memoria de [SafStorage] (URI de árbol de `UltiMusic` + raíces
     * adicionales de `library_roots`) con lo que haya en la base de datos ahora mismo. Hace falta
     * llamarlo tras cualquier cambio en `library_roots` (añadir/quitar una raíz) y al arrancar: sin
     * esto [MusicScanner] no sabría qué carpetas recorrer.
     */
    private suspend fun refreshSafRegistry() {
        SafStorage.refreshRegistry(appContext, dao.libraryRootPaths())
    }

    /** Solo una reconciliación de verdad a la vez: [reconcile] la puede disparar
     *  [SongsViewModel][com.untar.ultimusic.ui.SongsViewModel]
     *  (al abrir/reintentar), o añadir/quitar/conceder una carpeta raíz, todos ellos de forma
     *  independiente: sin serializarlos, dos reconciliaciones a la vez leerían y escribirían la misma
     *  foto de "qué hay catalogado" y podrían pisarse (una da por buena una baja que la otra ya había
     *  resuelto de otra forma). */
    private val reconcileMutex = Mutex()

    /** ¿Hay una reconciliación en curso ahora mismo? Reactivo y compartido por TODA la aplicación (a
     *  diferencia de antes, donde solo [com.untar.ultimusic.ui.SongsViewModel] lo sabía de la suya
     *  propia): así la pantalla de Canciones muestra "Actualizando base de datos..." tanto si el
     *  escaneo lo disparó ella misma como si lo disparó, por ejemplo, añadir una carpeta desde
     *  Ajustes. */
    private val _reconciling = MutableStateFlow(false)
    val reconciling: Flow<Boolean> = _reconciling

    /** Progreso (0-100) de la reconciliación en curso, compartido igual que [reconciling]. Solo tiene
     *  sentido mientras [reconciling] vale `true`. */
    private val _reconcileProgress = MutableStateFlow(0)
    val reconcileProgress: Flow<Int> = _reconcileProgress

    /**
     * Reconcilia lo que hay en las carpetas concedidas con lo guardado: escanea (fuera de
     * transacción) y delega en el DAO la inserción de novedades y el borrado de lo que ya no existe.
     * Las ediciones del usuario nunca se pisan.
     *
     * Antes de escanear se piden las rutas ya catalogadas ([LibraryDao.allSongPaths]) para
     * pasárselas a [MusicScanner.scan] como `knownPaths`: así el escaneo solo abre y lee las
     * etiquetas de los archivos NUEVOS, no de toda la fonoteca en cada llamada (ver
     * [MusicScanner.scan] y [LibraryDao.reconcile]).
     *
     * El trabajo de verdad se lanza en [observerScope], no en el de quien llama: `SettingsViewModel`
     * (añadir/quitar una carpeta raíz) vive en el ciclo de vida del diálogo de ajustes, y cerrarlo
     * ANTES de que termine el escaneo cancelaba silenciosamente la reconciliación a medias -de ahí
     * que la fonoteca pareciera no actualizarse hasta cerrar y volver a abrir la aplicación entera-.
     * Aquí solo se espera ([join]) a que termine; si quien llama se cancela antes, el escaneo sigue
     * su curso en segundo plano y la base de datos (y [reconciling]/[reconcileProgress], que observa
     * toda la aplicación) queda consistente igualmente.
     */
    suspend fun reconcile(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        observerScope.launch {
            reconcileMutex.withLock {
                refreshSafRegistry()
                // Una carpeta registrada que no se puede leer ahora mismo (permiso revocado desde
                // Ajustes del sistema, tarjeta desmontada, BD restaurada tras reinstalar...) se vería
                // vacía y sus canciones parecerían haber desaparecido. Por eso el escaneo se salta
                // esas carpetas y LibraryDao.reconcile no da de baja ninguna fila que cuelgue de
                // ellas ([unreadable]); pero las carpetas LEGIBLES sí se escanean, para que un
                // archivo movido a una de ellas se reenlace aunque la carpeta de origen ya no sea
                // accesible. Solo si no hay NINGUNA legible no se hace nada. Ver
                // MusicScanner.unreadableRootPrefixes.
                val unreadable = MusicScanner.unreadableRootPrefixes(appContext) ?: return@withLock
                _reconciling.value = true
                _reconcileProgress.value = 0
                try {
                    val knownPaths = dao.allSongPaths().toHashSet()
                    val result = MusicScanner.scan(appContext, knownPaths) { current, total ->
                        _reconcileProgress.value = if (total > 0) (current * 100) / total else 0
                        onProgress(current, total)
                    }
                    dao.reconcile(result.newSongs, result.currentPaths, result.currentSizes, unreadable)
                } finally {
                    _reconciling.value = false
                }
            }
        }.join()
    }

    /**
     * Refresca las visitas (y el canal) de YouTube de todas las canciones con vídeo, como mucho una
     * vez al día (ver [YouTubeStatsRefresh]). La llama [com.untar.ultimusic.ui.SongsViewModel] al
     * terminar cada reconciliación con el filesystem, que es lo más parecido que hay a "abrir la
     * aplicación" sin depender de que la biblioteca ya esté cargada del todo, y también
     * [com.untar.ultimusic.ui.sort.YouTubeApiKeyDialogFragment] justo al guardar una clave nueva (para
     * no hacer esperar al usuario hasta el refresco del día siguiente).
     *
     * De paso resuelve el canal "de cada artista" y su número de suscriptores, guardándolos
     * directamente en su fila ([com.untar.ultimusic.data.db.entities.ArtistEntity.youtubeChannelId]/
     * [com.untar.ultimusic.data.db.entities.ArtistEntity.youtubeChannelSubscriberCount]): es lo que usa
     * `LibraryDao.observeArtistSummaries` para la popularidad de un artista (ver
     * [com.untar.ultimusic.model.PersonSummary.popularity]). La moda (el canal que más se repite entre
     * las canciones donde el artista es el principal) se calcula aquí, en Kotlin, a partir de
     * [LibraryDao.artistChannelCandidates]; SQL solo hace el `GROUP BY` de conteo, no decide cuál gana.
     *
     * Es best-effort y en segundo plano ([observerScope], no el de quien llama): un fallo de red o
     * de cuota no debe alargar ni romper la reconciliación, y las visitas/suscriptores son un dato
     * decorativo del subtítulo (y de un criterio de orden opcional), no algo de lo que dependa la
     * reproducción.
     *
     * Excepciones al tope diario: cuando el usuario cambia el vídeo de UNA canción desde el editor
     * (ver [refreshYouTubeStatsForSong]), y cuando pone o cambia la clave con [force] (ver más abajo).
     *
     * @param force Salta la comprobación de [YouTubeStatsRefresh.isDue] (pero no la de
     * [YouTubeStatsApi.isConfigured]). La usa [com.untar.ultimusic.ui.sort.YouTubeApiKeyDialogFragment]
     * al guardar una clave nueva O al cambiar una ya puesta: sin esto, si el usuario había introducido
     * vídeos sin clave (o con una que dejó de funcionar), el barrido de hoy ya se habría dado por
     * intentado -aunque no consiguiera nada por no haber clave todavía- y esos vídeos se quedarían sin
     * visitas hasta el refresco de mañana en vez de pedirse en cuanto la clave por fin sirve.
     */
    fun refreshYouTubeStatsIfDue(force: Boolean = false) {
        if (!YouTubeStatsApi.isConfigured || (!force && !YouTubeStatsRefresh.isDue)) return
        observerScope.launch {
            // Agrupadas por id de vídeo (no por canción): dos canciones podrían compartir el mismo
            // vídeo, y así se piden sus visitas una sola vez y se reparten entre todas las que lo
            // llevan.
            val songIdsByVideoId = dao.songsWithVideoUrl()
                .mapNotNull { row -> YouTubeUrl.videoId(row.videoUrl)?.let { it to row.id } }
                .groupBy({ it.first }, { it.second })
            if (songIdsByVideoId.isEmpty()) {
                YouTubeStatsRefresh.markRefreshed()
                return@launch
            }

            // Paso 1: visitas + canal de cada vídeo, guardados en la propia canción.
            val stats = mutableMapOf<Long, Pair<Long, String?>>()
            for (chunk in songIdsByVideoId.keys.chunked(YouTubeStatsApi.MAX_IDS_PER_CALL)) {
                val videoStats = YouTubeStatsApi.videoStats(chunk)
                for ((videoId, videoStat) in videoStats) {
                    songIdsByVideoId[videoId]?.forEach { songId -> stats[songId] = videoStat.viewCount to videoStat.channelId }
                }
            }
            dao.setYoutubeViewCounts(stats)

            // Paso 2: el canal de cada artista, de TODA la fonoteca.
            resolveArtistChannels(dao.artistChannelCandidates())

            // Se marca al final, no al principio: si el proceso se interrumpe a medias, la próxima
            // apertura de la aplicación vuelve a intentarlo entero en vez de darlo por hecho.
            YouTubeStatsRefresh.markRefreshed()
        }
    }

    /**
     * Refresco INMEDIATO de las visitas (y el canal) de UNA canción con vídeo, sin esperar al tope
     * de una vez al día de [refreshYouTubeStatsIfDue]. La llama
     * [com.untar.ultimusic.ui.editor.MetadataEditorViewModel.save] justo después de guardar, pero
     * SOLO cuando el vídeo ha cambiado de verdad (uno nuevo, o distinto del que tenía antes): es la
     * única edición que deja desactualizado el dato guardado — el resto de campos no tocan las
     * visitas, así que no hay nada que valga la pena volver a pedir.
     *
     * También recalcula, si la tiene, la popularidad del artista PRINCIPAL de esta canción: el canal
     * que se le acaba de guardar podría cambiar cuál es "el canal del artista" (la moda de sus
     * canciones), y esperar al refresco diario dejaría su ficha con un dato viejo hasta entonces.
     * Solo ESE artista, no toda la fonoteca: el barrido completo ya lo hace [refreshYouTubeStatsIfDue]
     * a diario.
     *
     * A propósito NO llama a [YouTubeStatsRefresh.markRefreshed]: el tope diario sigue intacto para
     * el resto de la fonoteca, esto es solo una excepción puntual para la canción que se acaba de
     * editar, no un refresco general adelantado.
     */
    fun refreshYouTubeStatsForSong(songId: Long, videoUrl: String) {
        if (!YouTubeStatsApi.isConfigured) return
        observerScope.launch {
            val videoId = YouTubeUrl.videoId(videoUrl) ?: return@launch
            val stat = YouTubeStatsApi.videoStats(listOf(videoId))[videoId] ?: return@launch
            dao.setYoutubeViewCount(songId, stat.viewCount, stat.channelId)

            val artistIds = dao.principalArtistIds(songId)
            if (artistIds.isEmpty()) return@launch
            resolveArtistChannels(dao.artistChannelCandidates().filter { it.artistId in artistIds })
        }
    }

    /**
     * Resuelve, para cada artista presente en [candidates], cuál es su canal (el más repetido entre
     * las canciones donde es el PRINCIPAL) y guarda sus suscriptores. Compartido por
     * [refreshYouTubeStatsIfDue] (con TODOS los candidatos de la fonoteca) y
     * [refreshYouTubeStatsForSong] (con los de un solo artista): la lógica de elegir moda y pedir
     * suscriptores es la misma, solo cambia el alcance de la lista de entrada.
     *
     * A propósito NO hay reserva a un segundo candidato si el más repetido resulta inválido: un
     * candidato secundario suele ser una sola canción resubida a otro canal (una versión, un cover, un
     * reupload de terceros), y sus suscriptores no tienen nada que ver con los del artista — usarlo
     * como reserva daría un dato falso, peor que no tener ninguno.
     */
    private suspend fun resolveArtistChannels(candidates: List<ArtistChannelCandidateRow>) {
        // artistChannelCandidates ya viene agrupado por (artista, canal) con su recuento; aquí solo
        // hace falta quedarse, por artista, con el candidato más repetido (la moda).
        val topChannelByArtist = candidates
            .groupBy({ it.artistId }, { it.channelId to it.cnt })
            .mapValues { (_, cands) -> cands.maxBy { it.second }.first }
        if (topChannelByArtist.isEmpty()) return

        // Una sola tanda de peticiones para los canales de TODOS los artistas de este lote, sin
        // repetir el mismo canal si lo comparten varios.
        val allChannelIds = topChannelByArtist.values.distinct()
        val channelSubscribers = mutableMapOf<String, Long>()
        // Canales cuya llamada SÍ ha respondido, aunque haya sido con datos vacíos (channelStats
        // devuelve null, no mapa vacío, cuando la llamada entera falla): sin esto no se puede
        // distinguir "YouTube contestó y este canal no tiene suscriptores válidos" de "la cuota se
        // agotó a media consulta y no llegamos a preguntar por él".
        val queriedChannelIds = mutableSetOf<String>()
        for (chunk in allChannelIds.chunked(YouTubeStatsApi.MAX_IDS_PER_CALL)) {
            val stats = YouTubeStatsApi.channelStats(chunk) ?: continue
            queriedChannelIds += chunk
            channelSubscribers.putAll(stats)
        }

        // Por artista: los suscriptores de su canal si YouTube los dio. Si YouTube respondió pero sin
        // dato válido (canal borrado, o con el recuento oculto), es un "sin canal" de verdad y se
        // borra (null). Si la llamada de ese canal falló entera, no se toca esa fila hoy: se
        // reintentará en el próximo refresco en vez de arriesgarse a borrar un dato bueno por un fallo
        // de red.
        val resolved = topChannelByArtist.mapNotNull { (artistId, channelId) ->
            when {
                channelId in channelSubscribers -> artistId to (channelId to channelSubscribers.getValue(channelId))
                channelId in queriedChannelIds -> artistId to null
                else -> null
            }
        }.toMap()
        dao.setArtistYoutubeChannels(resolved)
    }

    // --- Lista gris ---
    //
    // Ninguno de estos tres llama a [reconcile]: activar/desactivar/quitar una subcarpeta es un
    // UPDATE en bloque sobre lo ya guardado (ver LibraryDao), instantáneo y sin reescanear nada.

    suspend fun addGreylistFolder(path: String) = withContext(Dispatchers.IO) {
        dao.addGreylistFolder(path)
    }

    suspend fun removeGreylistFolder(path: String) = withContext(Dispatchers.IO) {
        dao.removeGreylistFolder(path)
    }

    suspend fun setGreylistFolderExcluded(path: String, excluded: Boolean) = withContext(Dispatchers.IO) {
        dao.setGreylistFolderExcluded(path, excluded)
    }

    // --- Carpetas raíz de la fonoteca ---
    //
    // A diferencia de la lista gris (que solo oculta/muestra con un UPDATE, sin reescanear), añadir
    // o quitar una carpeta raíz SÍ cambia qué cuenta como parte de la biblioteca, así que las dos
    // operaciones reconcilian de inmediato: las canciones de una carpeta recién añadida aparecen sin
    // esperar a la siguiente apertura de la app, y las de una recién quitada se dan de baja igual de
    // rápido (salvo que reaparezcan con el mismo nombre en otra carpeta vigilada, ver
    // LibraryDao.reconcile).

    /** [treeUri] ya concedido con el selector del sistema (`ACTION_OPEN_DOCUMENT_TREE`), ver
     *  [SettingsViewModel][com.untar.ultimusic.ui.settings.SettingsViewModel]. */
    // Las tres operaciones de carpetas corren en [observerScope] (de aplicación) y quien llama solo
    // ESPERA el resultado: si el diálogo de Ajustes o la Activity se cierran justo al volver del
    // selector, su corrutina se cancela, y antes eso mataba la función a medias (la raíz ya guardada
    // pero SIN el reenlazado ni el reescaneo: "0 canciones" sin ningún aviso).
    suspend fun addLibraryRoot(treeUri: Uri) = observerScope.async {
        SafStorage.takePersistablePermission(appContext, treeUri)
        dao.insertLibraryRoot(LibraryRootEntity(path = treeUri.toString()))
        refreshSafRegistry()
        relinkAfterGrant(treeUri)
        reconcile()
    }.await()

    suspend fun removeLibraryRoot(treeUri: Uri) = observerScope.async {
        dao.deleteLibraryRoot(treeUri.toString())
        SafStorage.releasePersistablePermission(appContext, treeUri)
        refreshSafRegistry()
        reconcile()
    }.await()

    /**
     * Devuelve un docPath que se puede reproducir para [song], o null si el archivo ya no está en
     * ninguna parte de la fonoteca.
     *
     * Existe porque la ruta guardada puede quedarse obsoleta: [reconcile] escanea leyendo las
     * etiquetas de todos los archivos, tarda segundos y no escribe en la base de datos hasta el
     * final, así que entre que el usuario mueve un archivo y termina la siguiente reconciliación hay
     * una ventana en la que la biblioteca sigue apuntando a la carpeta antigua. Reproducir esa ruta
     * hacía que ExoPlayer fallara en silencio y se quedara clavado en 0:00.
     *
     * Si el archivo no está donde decía la base de datos, se busca por nombre con
     * [MusicScanner.findByFilename] (un recorrido de carpetas, sin leer etiquetas: barato) y, si
     * no está con ese nombre, con [MusicScanner.findRelocated] (movido Y renombrado: tamaño exacto
     * primero y duración después). Si aparece, se corrige la fila de paso para no tener que volver
     * a buscarlo nunca más.
     */
    suspend fun resolvePlayablePath(song: Song): String? = withContext(Dispatchers.IO) {
        refreshSafRegistry()
        // Una ruta absoluta (empieza por "/") es una fila TODAVÍA sin re-enlazar tras la migración a
        // SAF (ver MIGRATION_29_30): nunca se puede comprobar con SafStorage.exists, así que se trata
        // directamente como "no está donde decía" y se intenta relocalizar por nombre más abajo.
        if (!song.filePath.startsWith("/") && SafStorage.exists(appContext, song.filePath)) {
            return@withContext song.filePath
        }

        val filename = song.filePath.substringAfterLast('/')
        // Mismo nombre en otra carpeta (archivo movido) o, si no, archivo movido Y renombrado:
        // por tamaño y luego por duración, entre lo que todavía no está catalogado.
        val relocated = MusicScanner.findByFilename(appContext, filename)
            ?: MusicScanner.findRelocated(appContext, song.size, song.duration, dao.allSongPaths().toHashSet())
            ?: return@withContext null
        // Best-effort: si la reconciliación se nos ha adelantado, la fila antigua ya no existe y el
        // UPDATE no afecta a nadie. La ruta encontrada sigue siendo válida para reproducir.
        runCatching { dao.updateSongPath(song.filePath, relocated) }
        relocated
    }

    /**
     * ¿Se pueden leer las carpetas concedidas de la fonoteca? Lo consulta quien vaya a dar una
     * canción por perdida (ver [MusicScanner.libraryFolderReadable]).
     */
    suspend fun libraryFolderReadable(): Boolean {
        refreshSafRegistry()
        return MusicScanner.libraryFolderReadable(appContext)
    }

    // --- Migración a Storage Access Framework (ver com.untar.ultimusic.util.SafStorage) ---

    /** ¿Falta alguna canción por re-enlazar tras la migración? (fila con ruta absoluta de antes de
     *  v30, ver `MIGRATION_29_30`). Lo consulta `MainActivity` para decidir si ofrecer el paso
     *  opcional de re-conceder Download/Music/raíces propias, además de la obligatoria `UltiMusic`. */
    suspend fun hasUnlinkedLegacySongs(): Boolean = withContext(Dispatchers.IO) {
        dao.allSongPaths().any { it.startsWith("/") }
    }

    /**
     * Concede la carpeta `UltiMusic` (obligatoria: sin ella no hay fonoteca) y re-enlaza lo que
     * pueda de lo ya catalogado antes de la migración a SAF.
     */
    suspend fun grantUltiMusicRoot(treeUri: Uri): Boolean = observerScope.async {
        decisionInProgress = true
        firstRunGate.value = true   // ver [firstRunGate]; la abre [releaseFirstRunGate]
        try {
            SafStorage.setUltiMusicTreeUri(appContext, treeUri)
            refreshSafRegistry()
            grantEpoch.value++
            // Reinstalación: la BD interna nació vacía antes de poder leer la copia (el permiso de
            // la carpeta se revoca al desinstalar). Si la copia es más rica, se prepara para entrar
            // en el próximo arranque y se pide reiniciar; el reenlazado ya lo hará la BD buena.
            val restartNeeded = stageBackupRestoreIfRicher()
            SafStorage.markBackupChecked(appContext)
            if (restartNeeded) return@async true
            relinkAfterGrant(treeUri)
            // El reescaneo NO se hace aquí: lo lanza MainActivity justo después con
            // SongsViewModel.reload().
            false
        } finally {
            decisionInProgress = false
        }
    }.await()

    /**
     * Abre la puerta del primer arranque (ver [firstRunGate]): el usuario ha cerrado el segundo
     * diálogo (carpeta concedida, o "ahora no"). Espera antes a que termine el escaneo en curso, si
     * lo hay, para que las listas se llenen ya con filas verificadas contra el disco. Idempotente.
     */
    suspend fun releaseFirstRunGate() {
        if (!firstRunGate.value) return
        // Todo en observerScope: si la Activity se destruye al volver del selector de carpeta, la
        // puerta no puede quedarse cerrada por una cancelación.
        observerScope.async {
            reconcileMutex.withLock { }
            firstRunGate.value = false
        }.await()
    }

    /**
     * ¿Es la copia de `UltiMusic/databases/` más rica (más filas en total) que la BD interna? Si
     * lo es -y su esquema no es de una versión futura de la app-, la deja como
     * [UltiMusicDatabase.STAGED_RESTORE_NAME] y devuelve true. Se evalúa una sola vez por
     * instalación (ver [SafStorage.isBackupChecked]): en uso normal la BD real siempre contiene a
     * la copia, que sale de ella.
     */
    private fun stageBackupRestoreIfRicher(): Boolean = runCatching {
        val root = SafStorage.ultiMusicDocPath(appContext) ?: return@runCatching false
        val backupDocPath = "$root/databases/${UltiMusicDatabase.DB_NAME}"
        if (!SafStorage.exists(appContext, backupDocPath)) return@runCatching false

        val dbFile = appContext.getDatabasePath(UltiMusicDatabase.DB_NAME)
        val staged = File(dbFile.parentFile, UltiMusicDatabase.STAGED_RESTORE_NAME)
        try {
            SafStorage.openInputStream(appContext, backupDocPath)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false

            val live = UltiMusicDatabase.get(appContext)
            live.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val liveVersion = live.openHelper.readableDatabase.version

            val (backupVersion, backupRows) = openReadOnly(staged).use { it.version to totalRows(it) }
            val liveRows = openReadOnly(dbFile).use { totalRows(it) }

            if (backupVersion <= liveVersion && backupRows > liveRows) true
            else { staged.delete(); false }
        } catch (t: Throwable) {
            staged.delete()
            false
        }
    }.getOrDefault(false)

    private fun openReadOnly(file: File) = android.database.sqlite.SQLiteDatabase.openDatabase(
        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
    )

    private fun totalRows(d: android.database.sqlite.SQLiteDatabase): Long {
        val names = d.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return names.sumOf { t -> d.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { c -> c.moveToFirst(); c.getLong(0) } }
    }

    /**
     * Re-enlaza las canciones catalogadas ANTES de la migración a SAF (`filePath` con ruta absoluta
     * de verdad, p. ej. `/storage/emulated/0/UltiMusic/Bootlegs/track.mp3`) con su docPath nuevo, en
     * cuanto el usuario concede de nuevo la carpeta que las contenía.
     *
     * Recorre TODO el árbol recién concedido [treeUri] (con [MusicScanner], el mismo camino rápido
     * que usa el escaneo normal, sin leer etiquetas) y, para cada archivo encontrado, compone la
     * ruta absoluta que habría tenido ANTES de esta migración (`/storage/emulated/0/` + su docPath:
     * el mismo volumen de siempre, ver la cabecera de [SafStorage]) y reapunta la fila que tuviera
     * exactamente esa ruta. Una fila sin ninguna coincidencia sencillamente no se toca -sigue
     * "perdida" hasta que su carpeta se conceda, igual que ya pasaba con cualquier carpeta no
     * montada- y nunca se borra por esto.
     */
    private suspend fun relinkAfterGrant(treeUri: Uri) {
        val rootDocPath = SafStorage.grantedRoots().firstOrNull { it.second == treeUri }?.first ?: return
        val entries = MusicScanner.collectAudioEntries(appContext, treeUri, rootDocPath)
        if (entries.isEmpty()) return
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        for (entry in entries) {
            val oldAbsolutePath = "$storageRoot/${entry.docPath}"
            dao.updateSongPath(oldAbsolutePath, entry.docPath)
        }
    }

    // --- Ediciones (se reflejan al instante en el Flow [songs]) ---

    suspend fun updateSong(song: SongEntity) = dao.updateSong(song)
    suspend fun updateArtist(artist: ArtistEntity) = dao.updateArtist(artist)
    suspend fun updateAlbum(album: AlbumEntity) = dao.updateAlbum(album)

    /** Renombra el artista [id] para TODA la app de golpe; devuelve el id que sobrevive (ver
     *  [LibraryDao.renameArtist] sobre la fusión si el nombre nuevo ya lo tenía otro artista). */
    suspend fun renameArtist(id: Long, newName: String): Long = dao.renameArtist(id, newName)

    /** Renombra el género [oldName] para TODA la app de golpe (ver [LibraryDao.renameGenre]). */
    suspend fun renameGenre(oldName: String, newName: String) = dao.renameGenre(oldName, newName)

    /** Guardado completo desde el editor de metadatos de ÁLBUM (fila + reenlazado de artistas). */
    suspend fun saveAlbumEdits(album: AlbumEntity, artistNames: List<String>) =
        dao.saveAlbumEdits(album, artistNames)
    suspend fun updateProducer(producer: ProducerEntity) = dao.updateProducer(producer)

    /**
     * Guarda el enlace del videoclip elegido en el buscador del iPod. Al escribir en Room, el flujo
     * [songs] reemite solo y la canción que suena vuelve a llegar al reproductor ya con su `videoUrl`.
     *
     * Gestiona también la miniatura de reserva (best-effort), igual que el editor de metadatos: si
     * el enlace cambia de verdad, borra la miniatura anterior (si tenía) y descarga la del vídeo
     * nuevo. Así da igual por cuál de los dos caminos llegue el enlace: los dos dejan la miniatura
     * lista para cuando haga falta como carátula de reserva.
     *
     * También pide, como [refreshYouTubeStatsForSong], las visitas del vídeo nuevo al instante: sin
     * esto se quedarían en null (o en las del vídeo viejo) hasta el refresco diario, igual que le
     * pasaría al editor de metadatos si no hiciera lo mismo tras guardar.
     */
    suspend fun setVideoUrl(song: Song, videoUrl: String?): String? = withContext(Dispatchers.IO) {
        if (videoUrl == song.videoUrl) return@withContext song.videoThumbnailName
        song.videoThumbnailName?.let { deleteVideoThumbnail(it) }
        val thumbnailName = videoUrl?.let { url ->
            YouTubeUrl.videoId(url)?.let {
                downloadVideoThumbnail(it, CoverArt.imageBaseName(song.artists.map { a -> a.name }, song.title))
            }
        }
        dao.setVideoUrl(songId = song.id, videoUrl = videoUrl, videoThumbnailName = thumbnailName)
        if (videoUrl != null) {
            refreshYouTubeStatsForSong(song.id, videoUrl)
        }
        thumbnailName
    }

    /**
     * Guarda la letra elegida en el buscador de lrclib.net que abre el lápiz del recuadro de letra
     * del iPod (ver [IPodDialogFragment][com.untar.ultimusic.ui.player.IPodDialogFragment]) y la que
     * encuentra sola [LyricsAutoFetcher] al reproducir una canción sin letra.
     * Al escribir en Room, el flujo [songs] reemite solo y la canción que suena vuelve a llegar al
     * reproductor ya con su `lyrics`, igual que [setVideoUrl] con el enlace del videoclip.
     *
     * A diferencia del editor de metadatos -donde el idioma se deduce en pantalla mientras se escribe
     * la letra y solo se guarda al pulsar "Guardar", ver
     * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.updateLanguageFromLyrics]-, aquí no
     * hay ningún formulario de por medio: sin esto, una letra añadida desde el iPod o por la búsqueda
     * automática se quedaría sin su etiqueta de idioma hasta que alguien abriera el editor y
     * volviera a guardar esa canción a mano. [LanguageDetector.detectSuspend] deduce el idioma de la
     * letra nueva (o lo deja en null si se ha vaciado, igual que `updateLanguageFromLyrics`) y
     * [syncLanguageTag] se encarga de mover la etiqueta como en [saveSongEdits].
     */
    suspend fun setLyrics(song: Song, lyrics: String?) = withContext(Dispatchers.IO) {
        if (lyrics == song.lyrics) return@withContext
        dao.setLyrics(songId = song.id, lyrics = lyrics)
        val newLanguage = lyrics?.trim()?.takeIf { it.isNotEmpty() }?.let { LanguageDetector.detectSuspend(it) }
        dao.setLanguage(songId = song.id, language = newLanguage)
        syncLanguageTag(song.id, song.language, newLanguage)
    }

    /** Ver [com.untar.ultimusic.data.db.entities.SongEntity.lyricsSearchedAt]. */
    suspend fun setLyricsSearchedAt(songId: Long, searchedAt: Long?) = withContext(Dispatchers.IO) {
        dao.setLyricsSearchedAt(songId = songId, searchedAt = searchedAt)
    }

    /**
     * Guarda el desplazamiento vídeo/audio elegido con la regla del modo vídeo del iPod (ver
     * `IPodDialogFragment`, `ValueRuler.onReleased`). Al escribir en Room, el flujo [songs] reemite
     * solo y la canción que suena vuelve a llegar ya con su `videoOffsetMs` nuevo, igual que
     * [setVideoUrl] y [setLyrics].
     */
    suspend fun setVideoOffsetMs(song: Song, offsetMs: Long) = withContext(Dispatchers.IO) {
        if (offsetMs == song.videoOffsetMs) return@withContext
        dao.setVideoOffsetMs(songId = song.id, offsetMs = offsetMs)
    }

    /**
     * Guardado completo desde el editor de metadatos (fila + reenlazado de artistas y álbum(es)).
     *
     * [albums] REEMPLAZA por completo los álbumes de la canción, en el orden en que llega (ver
     * [LibraryDao.saveSongEdits]): una canción puede estar en más de uno a la vez (N:M, ver
     * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]).
     *
     * [previousLanguage] es el [Song.language] que tenía la canción ANTES de este guardado (lo trae
     * ya en la mano [com.untar.ultimusic.ui.editor.MetadataEditorViewModel.writeSong], que parte del
     * `Song` cargado del flujo): hace falta para saber si hay que mover la etiqueta de idioma de una a
     * otra, no solo para crear/asignar la nueva (ver [syncLanguageTag]).
     */
    suspend fun saveSongEdits(
        song: SongEntity,
        artistNames: List<String>,
        albums: List<AlbumEdit>,
        producerNames: List<String>,
        previousLanguage: String?
    ) {
        // Todo en UNA transacción: Room avisa a sus observadores al cerrar cada transacción, así que
        // por separado eran cuatro oleadas de relecturas de la biblioteca por cada "Guardar" (la
        // edición en sí y luego cada una de las tres etiquetas sincronizadas); juntas, una sola.
        UltiMusicDatabase.get(appContext).withTransaction {
            dao.saveSongEdits(
                song.copy(language = LanguageDetector.canonicalName(song.language)),
                artistNames, albums, producerNames
            )
            syncRemixCoverTag(song.id, song.ogTitle)
            syncLanguageTag(song.id, previousLanguage, song.language)
        }
    }

    /**
     * Mantiene la etiqueta predefinida "Remix / Cover" (ver [SystemTagKey.REMIX_COVER]) en línea con
     * [ogTitle] ("Título original" del editor de metadatos): la añade en cuanto deja de estar vacío,
     * la quita en cuanto vuelve a estarlo. Membresía real, fila en `song_tag`; el usuario también puede
     * añadirla/quitarla a mano desde la ficha de la etiqueta. Solo hay UN sitio que puede cambiar
     * `ogTitle` ([saveSongEdits], el editor de metadatos).
     *
     * `findTagBySystemKey` puede devolver null en teoría (una instalación que nunca haya pasado por
     * `migration25To26` ni por el `Callback` de instalación nueva), aunque en la práctica siempre hay
     * fila: se ignora sin más en vez de reventar, por si acaso.
     */
    private suspend fun syncRemixCoverTag(songId: Long, ogTitle: String?) {
        val tag = dao.findTagBySystemKey(SystemTagKey.REMIX_COVER.name) ?: return
        if (!ogTitle.isNullOrBlank()) {
            dao.insertSongTag(SongTagCrossRef(songId, tag.id))
        } else {
            dao.deleteSongTag(songId, tag.id)
        }
    }

    /**
     * Mantiene la etiqueta de IDIOMA de [songId] en línea con [newLanguage] ([Song.language], que
     * [com.untar.ultimusic.util.LanguageDetector] deduce sola a partir de la letra, ver
     * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.updateLanguageFromLyrics]): si el
     * idioma ha cambiado de verdad, quita la canción de la etiqueta ANTERIOR ([previousLanguage]) y la
     * mete en la NUEVA, creándola primero si no existe ya una etiqueta con ese nombre exacto.
     *
     * A diferencia de [syncSyncedVideoTag] (un booleano: la etiqueta está o no está) aquí hay que
     * saber de dónde viene la canción, no solo a dónde va -si el idioma pasa de "Inglés" a "Español" no
     * basta con añadir "Español", hay que soltar "Inglés" también-, así que hace falta [previousLanguage]
     * (ver el javadoc de [saveSongEdits]). Si no ha cambiado (incluido "seguir sin letra"), no toca nada.
     *
     * Reutiliza una etiqueta existente con ese nombre tal cual esté -color, [TagEntity.isAutoAssigned]-
     * en vez de sobrescribirla: si el usuario ya se había creado a mano una etiqueta "Inglés" con su
     * propio color, esta función solo la usa para la membresía, no la convierte en una etiqueta de
     * idioma ni le toca el color. Blanco fijo ([R.color.um_tag_language], la excepción de color dinámico
     * documentada en `TagsAdapter`) y [TagEntity.isAutoAssigned] a `true` son solo para una etiqueta que
     * esta función CREA de cero.
     */
    private suspend fun syncLanguageTag(songId: Long, previousLanguage: String?, newLanguage: String?) {
        val previous = LanguageDetector.canonicalName(previousLanguage?.trim()).orEmpty()
        val current = LanguageDetector.canonicalName(newLanguage?.trim()).orEmpty()
        if (previous == current) return
        if (previous.isNotEmpty()) {
            dao.findTagByName(previous)?.let { dao.deleteSongTag(songId, it.id) }
        }
        if (current.isNotEmpty()) {
            val tag = dao.findTagByName(current) ?: run {
                val sortOrder = dao.maxTagSortOrder() + 1
                val colorArgb = ContextCompat.getColor(appContext, R.color.um_tag_language)
                val id = dao.insertTag(
                    TagEntity(name = current, colorArgb = colorArgb, systemKey = null, sortOrder = sortOrder, isAutoAssigned = true)
                )
                dao.findTagByName(current) ?: TagEntity(id, current, colorArgb, null, sortOrder, true)
            }
            dao.insertSongTag(SongTagCrossRef(songId, tag.id))
        }
    }

    /**
     * Arreglo de arranque, llamado una vez desde [com.untar.ultimusic.UltiMusicApp.onCreate]: hasta
     * que [setLyrics] empezó a deducir el idioma solo (ver su doc), una letra añadida desde el iPod o
     * desde "Añadir todas las letras" se quedaba con `language` vacío para siempre, sin etiqueta de
     * idioma ninguna. Recorre las canciones con letra pero sin idioma guardado y, para cada una,
     * intenta deducirlo y sincronizar su etiqueta -mismo camino que [setLyrics], `previousLanguage`
     * siempre vacío aquí porque es justo lo que estamos comprobando-, dejándola tal cual si ML Kit no
     * consigue decidirse (se reintentará en el próximo arranque, es barato).
     *
     * Igual que [migration22To23][com.untar.ultimusic.data.db.migration22To23] con el backfill por
     * `language` ya guardado, pero disparado en cada arranque en vez de una sola vez en una migración:
     * hace falta porque esto no es un cambio de esquema, así que no hay ningún momento fijo de
     * instalación/actualización al que enganchar un backfill único.
     */
    suspend fun backfillMissingLanguageTags() = withContext(Dispatchers.IO) {
        val candidates = songs.first().filter { !it.lyrics.isNullOrBlank() && it.language.isNullOrBlank() }
        for (song in candidates) {
            val detected = LanguageDetector.detectSuspend(song.lyrics!!.trim()) ?: continue
            dao.setLanguage(songId = song.id, language = detected)
            syncLanguageTag(song.id, song.language, detected)
        }
    }

    /**
     * Copia la imagen elegida por el usuario a `UltiMusic/images` (ver [CoverArt.imagesDocPath]) y
     * devuelve el nombre de archivo resultante (lo que se guarda en `imageName`), nombrado como
     * [title].
     *
     * Se copia en vez de guardar la URI del sistema porque una URI del selector de fotos es un
     * permiso temporal: en cuanto el usuario borra o mueve la foto —o simplemente al reiniciar—
     * dejaría de poder leerse y la portada se rompería. Con la copia, la carátula es nuestra.
     *
     * Si la canción ya tenía una carátula propia, quien llame debe borrarla ANTES de llamar aquí
     * (ver [deleteCoverImage]): así, cuando el título no ha cambiado, el nombre vuelve a estar
     * libre y esta imagen ocupa el mismo hueco en vez de acabar en "Título (2).img".
     *
     * Aun así, el nombre devuelto es el del `docPath` que de verdad creó el proveedor de
     * almacenamiento ([SafStorage.createFile]), NO el que se le pidió ([CoverArt.reserveFileName]):
     * si el borrado anterior falló en silencio (es best-effort, ver [deleteCoverImage]) o hay
     * cualquier otra colisión de nombre, `DocumentsContract.createDocument` no sobrescribe el
     * archivo viejo, crea uno nuevo con el nombre cambiado ("Título (1).img") para no chocar. Guardar
     * el nombre que se PIDIÓ en vez del que de verdad se creó dejaba `Song.imageName` apuntando a un
     * archivo que no existe -Coil no lo encuentra y cae al recuadro de reserva-, justo el bug que
     * describió el usuario ("a veces, al cambiar la carátula... aparece el placeholder").
     */
    suspend fun importCoverImage(uri: Uri, baseName: String): String = withContext(Dispatchers.IO) {
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: error("La carpeta UltiMusic no está concedida")
        val dir = CoverArt.imagesDocPath(appContext, createIfMissing = true) ?: error("La carpeta UltiMusic no está concedida")
        val name = CoverArt.reserveFileName(appContext, baseName, "img", null)
        val docPath = SafStorage.createFile(appContext, treeUri, dir, name, "image/*")
            ?: error("No se ha podido crear la carátula")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            SafStorage.openOutputStream(appContext, docPath)?.use { output -> input.copyTo(output) }
                ?: error("No se ha podido escribir la carátula")
        } ?: error("No se ha podido leer la imagen seleccionada")
        val finalName = docPath.substringAfterLast('/')
        // Ver SafStorage.awaitReadable: en algunos dispositivos el archivo recién escrito tarda un
        // poco en ser legible de verdad. Sin esto, Song.imageName quedaba apuntando (de momento) a
        // un archivo que Coil no podía leer todavía -el bug del placeholder tras editar la carátula-.
        if (!SafStorage.awaitReadable(appContext, docPath)) {
            error("La carátula se escribió pero el sistema todavía no la reconoce")
        }
        finalName
    }

    /**
     * Renombra una imagen ya guardada (carátula propia o miniatura de YouTube) para que coincida
     * con un título nuevo, sin volver a copiarla ni descargarla. Best-effort: si el archivo ya no
     * existe, no hace nada y devuelve el nombre tal cual estaba.
     *
     * @param baseName nombre ya compuesto con [CoverArt.imageBaseName] (`"<artistas> - <título>"`).
     * @param suffix lo que va detrás, p. ej. `" (video)"` para una miniatura o `""` para la
     *   carátula propia.
     */
    suspend fun renameImage(oldName: String, baseName: String, suffix: String, ext: String): String =
        withContext(Dispatchers.IO) {
            val dir = CoverArt.imagesDocPath(appContext) ?: return@withContext oldName
            if (!SafStorage.exists(appContext, "$dir/$oldName")) return@withContext oldName
            val baseName = baseName + suffix
            val newName = CoverArt.reserveFileName(appContext, baseName, ext, null)
            if (newName == oldName) return@withContext oldName
            SafStorage.renameTo(appContext, "$dir/$oldName", newName)?.let { return@withContext newName }
            oldName
        }

    /**
     * Descarga la miniatura del vídeo [videoId], la recorta al cuadrado central y la guarda en
     * `UltiMusic/images` nombrada como [title]. Best-effort: si algo falla (sin red, vídeo
     * borrado...) devuelve null y la canción se queda sin miniatura, cayendo al recuadro negro.
     */
    suspend fun downloadVideoThumbnail(videoId: String, baseName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(appContext)
                    .data("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                    .allowHardware(false)
                    .build()
                val bitmap = (CoverLoader.get(appContext).execute(request).drawable as? BitmapDrawable)
                    ?.bitmap ?: return@runCatching null

                val side = minOf(bitmap.width, bitmap.height)
                val cropped = Bitmap.createBitmap(
                    bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side
                )

                val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@runCatching null
                val dir = CoverArt.imagesDocPath(appContext, createIfMissing = true) ?: return@runCatching null
                val name = CoverArt.reserveFileName(appContext, "$baseName (video)", "jpg", null)
                val docPath = SafStorage.createFile(appContext, treeUri, dir, name, "image/jpeg") ?: return@runCatching null
                SafStorage.openOutputStream(appContext, docPath)?.use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                } ?: return@runCatching null
                // El nombre real es el del docPath que devolvió el proveedor, no el pedido (ver el
                // mismo razonamiento en importCoverImage): si hubiera colisión, no coincidirían.
                // Ver SafStorage.awaitReadable: mismo motivo que en importCoverImage.
                if (!SafStorage.awaitReadable(appContext, docPath)) return@runCatching null
                docPath.substringAfterLast('/')
            }.getOrNull()
        }

    /**
     * Borra una canción de verdad: su archivo en disco, su fila en la base de datos (con los
     * artistas/álbumes/productores que se queden huérfanos), su carátula importada y su miniatura
     * de YouTube cacheada, si tenía. No toca las listas; quien llame debe sacarla también de
     * ahí con `PlaylistRepository.removeSongFromAll`, igual que cuando un archivo desaparece solo.
     */
    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        runCatching { SafStorage.deleteRecursively(appContext, song.filePath) }
        song.imageName?.let { deleteCoverImage(it) }
        song.videoThumbnailName?.let { deleteVideoThumbnail(it) }
        LyricsCache.delete(appContext, CoverArt.imageBaseName(song.artists.map { it.name }, song.title))
        dao.deleteSong(song.id)
    }

    /** Renombra las letras traducidas/romanizadas guardadas de una canción (ver [LyricsCache.rename]). */
    suspend fun renameLyricsCache(oldBase: String, newBase: String) = LyricsCache.rename(appContext, oldBase, newBase)

    /** Borra una carátula importada que ya no usa nadie (best-effort). */
    suspend fun deleteCoverImage(imageName: String) = withContext(Dispatchers.IO) {
        val dir = CoverArt.imagesDocPath(appContext)
        if (dir != null && CoverArt.isRealImage(imageName)) {
            runCatching { SafStorage.deleteRecursively(appContext, "$dir/$imageName") }
        }
        Unit
    }

    /** Gemela de [deleteCoverImage], para la miniatura de YouTube cacheada. */
    suspend fun deleteVideoThumbnail(name: String) = withContext(Dispatchers.IO) {
        val dir = CoverArt.imagesDocPath(appContext)
        if (dir != null) runCatching { SafStorage.deleteRecursively(appContext, "$dir/$name") }
        Unit
    }

    /**
     * Copia (unidireccional, solo para inspección) la base de datos interna a
     * `UltiMusic/databases/`, porque en algunos móviles no se puede entrar en /data/data.
     * Best-effort: cualquier fallo se ignora (incluido que `UltiMusic` no esté concedida todavía).
     * Antes hace checkpoint del WAL para que la copia sea consistente.
     */
    suspend fun exportDatabaseCopy() = withContext(Dispatchers.IO) {
        runCatching {
            // Nunca exportar antes de haber decidido cuál es la buena (ver SafStorage.isBackupChecked).
            // Sin la marca pero con la carpeta ya concedida es una instalación anterior a este
            // control, cuya BD es la de siempre: se da por decidida.
            if (decisionInProgress) return@runCatching
            if (!SafStorage.isBackupChecked(appContext)) {
                if (SafStorage.ultiMusicTreeUri(appContext) == null) return@runCatching
                SafStorage.markBackupChecked(appContext)
            }

            val db = UltiMusicDatabase.get(appContext)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            SafStorage.ensureUltiMusicRegistered(appContext)
            val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@runCatching
            val root = SafStorage.ultiMusicDocPath(appContext) ?: return@runCatching
            val destDir = SafStorage.getOrCreateSubfolder(appContext, treeUri, root, "databases") ?: return@runCatching

            val dbFile = appContext.getDatabasePath(UltiMusicDatabase.DB_NAME)
            preserveBackupIfTableEmptied(treeUri, destDir, dbFile)
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(dbFile.path + suffix)
                if (!src.exists()) continue
                val destName = dbFile.name + suffix
                val destDocPath = "$destDir/$destName"
                val docPath = if (SafStorage.exists(appContext, destDocPath)) {
                    destDocPath
                } else {
                    SafStorage.createFile(appContext, treeUri, destDir, destName, "application/octet-stream")
                } ?: continue
                SafStorage.openOutputStream(appContext, docPath)?.use { output ->
                    src.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
        Unit
    }

    /**
     * Antes de que [exportDatabaseCopy] sobrescriba la copia, mira si alguna tabla que tenía filas
     * en ella está ahora vacía en la BD real (borrado a propósito, o BD perdida/recreada). Si es así,
     * guarda la copia antigua como `ultimusic.prev.db` y solo entonces se sobrescribe la principal:
     * nunca se pierde el último estado "con datos", y si el usuario vació la tabla adrede la copia
     * principal sigue reflejándolo. `prev` solo se reescribe en el siguiente vaciado, no en cada export.
     */
    private fun preserveBackupIfTableEmptied(treeUri: Uri, destDir: String, dbFile: File) {
        val backupDocPath = "$destDir/${dbFile.name}"
        if (!SafStorage.exists(appContext, backupDocPath)) return
        val tmp = File(appContext.cacheDir, "backup_check.db")
        try {
            SafStorage.openInputStream(appContext, backupDocPath)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return
            val emptied = android.database.sqlite.SQLiteDatabase.openDatabase(
                tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { old ->
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { cur ->
                    fun tables(d: android.database.sqlite.SQLiteDatabase) =
                        d.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'", null
                        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

                    fun count(d: android.database.sqlite.SQLiteDatabase, t: String) =
                        d.rawQuery("SELECT EXISTS(SELECT 1 FROM \"$t\")", null).use { c -> c.moveToFirst() && c.getInt(0) == 1 }

                    val curTables = tables(cur).toSet()
                    tables(old).any { t -> t in curTables && count(old, t) && !count(cur, t) }
                }
            }
            if (!emptied) return
            val prevName = "ultimusic.prev.db"
            val prevDocPath = "$destDir/$prevName"
            val target = if (SafStorage.exists(appContext, prevDocPath)) prevDocPath
            else SafStorage.createFile(appContext, treeUri, destDir, prevName, "application/octet-stream")
            if (target != null) {
                SafStorage.openOutputStream(appContext, target)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** Mínimo de canciones de "Descargada recientemente", aunque sean de hace más de un mes. */
        private const val RECENTLY_ADDED_MIN = 20

        /** true mientras [grantUltiMusicRoot] decide entre BD interna y copia: no se exporta. */
        @Volatile
        private var decisionInProgress = false

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(
                    dao = UltiMusicDatabase.get(context).libraryDao(),
                    appContext = context.applicationContext
                ).also { instance = it }
            }
    }
}
