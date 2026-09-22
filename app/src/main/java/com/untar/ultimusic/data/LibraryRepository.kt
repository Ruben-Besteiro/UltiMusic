package com.untar.ultimusic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
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
import com.untar.ultimusic.util.SafStorage
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    /** Job del reescaneo periódico (ver [startWatchingLibraryChanges]); reemplaza al viejo
     *  `MusicLibraryObserver` basado en `FileObserver`, que no tiene equivalente sobre un árbol SAF
     *  (ver [com.untar.ultimusic.util.SafStorage]): SAF no ofrece vigilancia recursiva de cambios, así
     *  que en vez de enterarse al instante de una canción nueva, se reconcilia sola cada
     *  [WATCH_INTERVAL_MS] mientras alguien la quiera vigilada. */
    private var watchJob: Job? = null

    /** Quién puede pedir que el vigilante de la fonoteca esté vivo (ver [startWatchingLibraryChanges]). */
    enum class LibraryWatchRequester { ACTIVITY, PLAYBACK_SERVICE }

    /**
     * Qué interesados quieren el vigilante vivo ahora mismo. Un SET, no un contador: `MainActivity`
     * puede llamar a [startWatchingLibraryChanges] varias veces seguidas con [LibraryWatchRequester.ACTIVITY]
     * sin haber soltado antes (p. ej. `onResume` y, en la misma ventana `onStart`/`onStop`, el callback
     * de permisos de almacenamiento), y solo suelta una vez desde `onStop`. Con un contador puro esas
     * llamadas de más dejarían el vigilante encendido para siempre; con un set, pedirlo dos veces
     * seguidas con el mismo interesado no hace nada la segunda vez, tal como pasaba con el flag
     * booleano de antes de que [LibraryWatchRequester.PLAYBACK_SERVICE] necesitara pedirlo también por
     * su lado.
     */
    private val activeWatchRequesters = mutableSetOf<LibraryWatchRequester>()

    /** Canciones persistidas, reactivas: cualquier edición reemite la lista. */
    val songs: Flow<List<Song>> =
        dao.observeSongs().map { list -> list.map { it.toDomain() } }

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

    val albums: Flow<List<AlbumSummary>> =
        dao.observeAlbumSummaries(null).map { rows -> rows.map { it.toDomain() } }

    /** Sin agrupar por "Otros" (ver [artistGrouping]): la lista cruda tal cual la guarda Room, una
     *  fila por artista real. */
    private val rawArtists: Flow<List<PersonSummary>> =
        dao.observeArtistSummaries(null).map { rows -> rows.map { it.toDomain() } }

    /**
     * Agrupa bajo un perfil "Otros" (ver [ArtistGroupingPreferences]) los artistas con menos
     * canciones que el umbral guardado: sus canciones se cuentan juntas y dejan de listarse sueltos
     * en la pestaña Artistas. Es puramente una vista calculada aquí, en Kotlin -no hay ninguna fila
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
        combine(rawArtists, ArtistGroupingPreferences.threshold) { list, threshold ->
            val small = if (threshold > 0) list.filter { it.songCount < threshold } else emptyList()
            if (small.isEmpty()) return@combine ArtistGrouping(list, null, emptySet())
            val smallIds = small.mapTo(mutableSetOf()) { it.id }
            val others = PersonSummary(
                id = PersonSummary.OTHERS_ARTIST_ID,
                name = appContext.getString(R.string.artist_others),
                songCount = small.sumOf { it.songCount },
                albumCount = small.sumOf { it.albumCount },
                totalDuration = small.sumOf { it.totalDuration },
                // Sin imagen propia: agrupa a varios artistas reales a la vez, así que no hay un
                // collage único que resolver (ver CoverRef.group, pensado para UN solo artista).
                // Cae en el recuadro de reserva de cada ImageView, igual que un artista sin ninguna
                // canción con carátula.
                cover = CoverRef(ownImage = null),
                popularity = null
            )
            ArtistGrouping(list.filterNot { it.id in smallIds } + others, others, smallIds)
        }

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

    /** Canciones que llevan el género [genre] (comparación exacta, como [genres]). */
    fun songsOfGenre(genre: String): Flow<List<Song>> =
        songs.map { list -> list.filter { genre in it.genres } }

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

    private val tagEntities: Flow<List<TagEntity>> = dao.observeTags()
    private val tagCrossRefs: Flow<List<SongTagCrossRef>> = dao.observeSongTagCrossRefs()

    /** Una etiqueta ya resuelta contra la biblioteca: su resumen para pintar, y el conjunto de ids de
     *  canción que la tienen (real o calculada, ver [resolveSongsOfTag]) — este último es lo que
     *  necesita [tagsOfSong] para saber si UNA canción concreta la tiene, sin repetir la resolución. */
    private data class ResolvedTag(val summary: TagSummary, val matchedSongIds: Set<Long>)

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
                tag.id, tag.name, tag.colorArgb, resolved.size, resolved.sumOf { it.duration }, tag.systemKey,
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
        }

    /** Canciones de UNA etiqueta, por [id] (no por nombre: ver la decisión de diseño de usar el id
     *  como clave de una etiqueta en la ficha de detalle). */
    fun songsOfTag(id: Long, filenamesInAnyPlaylist: Flow<Set<String>>): Flow<List<Song>> =
        combine(tagEntities, tagCrossRefs, songs, filenamesInAnyPlaylist) { tags, crossRefs, allSongs, inPlaylist ->
            val tag = tags.firstOrNull { it.id == id } ?: return@combine emptyList()
            val memberIds = crossRefs.filter { it.tagId == tag.id }.map { it.songId }.toSet()
            resolveSongsOfTag(tag, allSongs, memberIds, inPlaylist, songsWithCustomTag(tags, crossRefs))
        }

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
        }

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
        }

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
     * - Descargadas recientemente: las 20 con [Song.dateAdded] más reciente. 100% calculada, nunca
     *   más de 20.
     * - En ninguna lista: cuyo archivo no está en [inPlaylist] (unión de todas las Listas).
     * - Sin etiquetas personalizadas: todas las canciones EXCEPTO las que ya tengan alguna etiqueta
     *   personalizada ([songsWithCustomTag]).
     * - Vídeo sincronizado: membresía real, [memberIds] -aquí solo se LEE; quien la mantiene
     *   sincronizada con [Song.videoOffsetMs] es [syncSyncedVideoTag]-.
     * - Remix / Cover: membresía real, igual que Vídeo sincronizado -aquí solo se LEE [memberIds];
     *   quien la mantiene sincronizada con [Song.ogTitle] es [syncRemixCoverTag]-.
     * - Una etiqueta personalizada (`systemKey == null`): pura membresía real, igual que las dos
     *   anteriores.
     */
    private fun resolveSongsOfTag(
        tag: TagEntity,
        allSongs: List<Song>,
        memberIds: Set<Long>,
        inPlaylist: Set<String>,
        songsWithCustomTag: Set<Long>
    ): List<Song> =
        when (tag.systemKey?.let { runCatching { SystemTagKey.valueOf(it) }.getOrNull() }) {
            SystemTagKey.RECENTLY_ADDED -> allSongs.sortedByDescending { it.dateAdded }.take(20)
            SystemTagKey.NOT_IN_PLAYLIST -> allSongs.filter { File(it.filePath).name !in inPlaylist }
            SystemTagKey.NO_CUSTOM_TAGS -> allSongs.filter { it.id !in songsWithCustomTag }
            SystemTagKey.SYNCED_VIDEO -> allSongs.filter { it.id in memberIds }
            SystemTagKey.REMIX_COVER -> allSongs.filter { it.id in memberIds }
            null -> allSongs.filter { it.id in memberIds }
        }

    // --- Fichas de detalle ---

    fun album(id: Long): Flow<AlbumSummary?> =
        dao.observeAlbumSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

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
        else dao.observeArtistSummaries(id).map { rows -> rows.firstOrNull()?.toDomain() }

    /** Canciones de un álbum, ya ordenadas por número de pista (y de disco) DENTRO de ese álbum: ver
     * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]. */
    fun albumTracks(albumId: Long): Flow<List<Song>> =
        dao.observeSongsOfAlbum(albumId).map { list -> list.map { it.toDomain() } }

    /**
     * Canciones de un artista. Para "Otros" ([PersonSummary.OTHERS_ARTIST_ID]) no hay ninguna
     * consulta propia en Room -esa fila no existe ahí-: se filtran directamente [songs] (que ya
     * excluye la lista gris) por si alguno de sus artistas está en [smallArtistIds], igual que
     * [Song.artistDisplay][com.untar.ultimusic.util.artistDisplay] mira esa misma lista para pintar
     * el subtítulo. Una canción con dos artistas pequeños a la vez (colaboración) sale una sola vez,
     * no se cuentan `any` como si fueran dos.
     */
    fun artistSongs(id: Long): Flow<List<Song>> =
        if (id == PersonSummary.OTHERS_ARTIST_ID) {
            combine(songs, smallArtistIds) { all, smallIds ->
                if (smallIds.isEmpty()) emptyList()
                else all.filter { song -> song.artists.any { it.id in smallIds } }
                    .sortedBy { it.title.lowercase() }
            }
        } else {
            dao.observeSongsOfArtist(id).map { list -> list.map { it.toDomain() } }
        }

    /** Álbumes de un artista, para el carrusel horizontal de su ficha. Vacío para "Otros": agrupa
     *  varios artistas reales a la vez, y ese carrusel es el de la discografía de uno solo. */
    fun artistAlbums(id: Long): Flow<List<AlbumSummary>> =
        if (id == PersonSummary.OTHERS_ARTIST_ID) flowOf(emptyList())
        else dao.observeAlbumsOfArtist(id).map { rows -> rows.map { it.toDomain() } }

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

    /**
     * Reconcilia lo que hay en las carpetas concedidas con lo guardado: escanea (fuera de
     * transacción) y delega en el DAO la inserción de novedades y el borrado de lo que ya no existe.
     * Las ediciones del usuario nunca se pisan.
     *
     * Antes de escanear se piden las rutas ya catalogadas ([LibraryDao.allSongPaths]) para
     * pasárselas a [MusicScanner.scan] como `knownPaths`: así el escaneo solo abre y lee las
     * etiquetas de los archivos NUEVOS, no de toda la fonoteca en cada llamada (ver
     * [MusicScanner.scan] y [LibraryDao.reconcile]).
     */
    suspend fun reconcile(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        refreshSafRegistry()
        val knownPaths = dao.allSongPaths().toHashSet()
        val result = MusicScanner.scan(appContext, knownPaths, onProgress)
        dao.reconcile(result.newSongs, result.currentPaths)
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

    /**
     * Arranca el reescaneo periódico si aún no lo estaba para [requester]. `MainActivity` (mientras
     * está en primer plano) y [com.untar.ultimusic.playback.PlaybackService] (mientras hay sesión de
     * reproducción activa) lo piden cada uno por su lado, y ninguno sabe si el otro también lo quiere
     * vivo. Cada [requester] que llame aquí debe soltar luego con [stopWatchingLibraryChanges]: solo
     * se para de verdad cuando el último interesado se da de baja, para no perder cambios de la
     * fonoteca mientras a alguien todavía le importan (p. ej. la Activity pasa a segundo plano pero
     * sigue sonando música).
     *
     * A diferencia del viejo `MusicLibraryObserver` (`FileObserver`, instantáneo), esto reconcilia
     * cada [WATCH_INTERVAL_MS] sin más: SAF no tiene forma de avisar de cambios en un árbol concedido,
     * así que una canción nueva puede tardar hasta ese intervalo en aparecer en vez de al instante
     * (decisión ya aceptada al migrar a Storage Access Framework).
     *
     * La primera vuelta NO reconcilia al momento: quien llama a esto (`MainActivity.loadIfPermitted`)
     * también llama a `SongsViewModel.loadIfNeeded` casi a la vez, que ya hace su propia
     * reconciliación de arranque con progreso visible en la pantalla de Canciones (ver
     * `SongsFragment`/`updating_database_percent`). Si esta reconciliación en segundo plano
     * arrancara YA, correría en paralelo con esa -duplicando el escaneo completo de la fonoteca, y
     * de paso dejando la pantalla sin el aviso de progreso mientras la de aquí seguía trabajando sin
     * que nadie la viera-, así que espera a [WATCH_INTERVAL_MS] antes de su primera pasada.
     */
    fun startWatchingLibraryChanges(requester: LibraryWatchRequester) {
        if (!activeWatchRequesters.add(requester)) return // este interesado ya lo tenía pedido
        if (activeWatchRequesters.size > 1) return // ya lo tenía pedido algún otro interesado

        watchJob = observerScope.launch {
            while (true) {
                delay(WATCH_INTERVAL_MS)
                reconcile()
            }
        }
    }

    /** Contraparte de [startWatchingLibraryChanges]: ver su doc para el reparto entre interesados. */
    fun stopWatchingLibraryChanges(requester: LibraryWatchRequester) {
        if (!activeWatchRequesters.remove(requester)) return
        if (activeWatchRequesters.isNotEmpty()) return
        watchJob?.cancel()
        watchJob = null
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
    suspend fun addLibraryRoot(treeUri: Uri) = withContext(Dispatchers.IO) {
        SafStorage.takePersistablePermission(appContext, treeUri)
        dao.insertLibraryRoot(LibraryRootEntity(path = treeUri.toString()))
        refreshSafRegistry()
        relinkAfterGrant(treeUri)
        reconcile()
    }

    suspend fun removeLibraryRoot(treeUri: Uri) = withContext(Dispatchers.IO) {
        dao.deleteLibraryRoot(treeUri.toString())
        SafStorage.releasePersistablePermission(appContext, treeUri)
        refreshSafRegistry()
        reconcile()
    }

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
     * aparece, se corrige la fila de paso para no tener que volver a buscarlo nunca más.
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
        val relocated = MusicScanner.findByFilename(appContext, filename) ?: return@withContext null
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
    suspend fun grantUltiMusicRoot(treeUri: Uri) = withContext(Dispatchers.IO) {
        SafStorage.setUltiMusicTreeUri(appContext, treeUri)
        refreshSafRegistry()
        relinkAfterGrant(treeUri)
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
            YouTubeUrl.videoId(url)?.let { downloadVideoThumbnail(it, song.title) }
        }
        dao.setVideoUrl(songId = song.id, videoUrl = videoUrl, videoThumbnailName = thumbnailName)
        if (videoUrl != null) {
            refreshYouTubeStatsForSong(song.id, videoUrl)
        }
        thumbnailName
    }

    /**
     * Guarda la letra elegida en el buscador de lrclib.net que abre el iPod al tocar el recuadro
     * de letra estando vacío (ver [IPodDialogFragment][com.untar.ultimusic.ui.player.IPodDialogFragment])
     * y la que mete de golpe [com.untar.ultimusic.data.BulkLyricsAdder] con "Añadir todas las letras".
     * Al escribir en Room, el flujo [songs] reemite solo y la canción que suena vuelve a llegar al
     * reproductor ya con su `lyrics`, igual que [setVideoUrl] con el enlace del videoclip.
     *
     * A diferencia del editor de metadatos -donde el idioma se deduce en pantalla mientras se escribe
     * la letra y solo se guarda al pulsar "Guardar", ver
     * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.updateLanguageFromLyrics]-, aquí no
     * hay ningún formulario de por medio: sin esto, una letra añadida desde el iPod o desde "Añadir
     * todas las letras" se quedaría sin su etiqueta de idioma hasta que alguien abriera el editor y
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

    /**
     * Guarda el desplazamiento vídeo/audio elegido con la regla del modo vídeo del iPod (ver
     * `IPodDialogFragment`, `ValueRuler.onReleased`). Al escribir en Room, el flujo [songs] reemite
     * solo y la canción que suena vuelve a llegar ya con su `videoOffsetMs` nuevo, igual que
     * [setVideoUrl] y [setLyrics].
     */
    suspend fun setVideoOffsetMs(song: Song, offsetMs: Long) = withContext(Dispatchers.IO) {
        if (offsetMs == song.videoOffsetMs) return@withContext
        dao.setVideoOffsetMs(songId = song.id, offsetMs = offsetMs)
        syncSyncedVideoTag(song.id, offsetMs)
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
        dao.saveSongEdits(song, artistNames, albums, producerNames)
        syncSyncedVideoTag(song.id, song.videoOffsetMs)
        syncRemixCoverTag(song.id, song.ogTitle)
        syncLanguageTag(song.id, previousLanguage, song.language)
    }

    /**
     * Mantiene la etiqueta predefinida "Vídeo sincronizado" (ver [SystemTagKey.SYNCED_VIDEO]) en
     * línea con [offsetMs]: la añade en cuanto deja de ser 0, la quita en cuanto vuelve a serlo.
     *
     * Se llama desde los DOS sitios que pueden cambiar `videoOffsetMs` -la regla del iPod
     * ([setVideoOffsetMs]) y el editor de metadatos ([saveSongEdits])-, así que da igual por dónde se
     * toque: la membresía real de la etiqueta (fila en `song_tag`, igual que Favoritos) nunca se
     * desincroniza del valor de verdad. El usuario también puede añadirla/quitarla a mano desde la
     * ficha de la etiqueta (ver [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment]);
     * esta función solo reacciona a cambios del desplazamiento, no al revés.
     *
     * `findTagBySystemKey` puede devolver null en teoría (una instalación que nunca haya pasado por
     * `migration20To21` ni por el `Callback` de instalación nueva), aunque en la práctica siempre hay
     * fila: se ignora sin más en vez de reventar, por si acaso.
     */
    private suspend fun syncSyncedVideoTag(songId: Long, offsetMs: Long) {
        val tag = dao.findTagBySystemKey(SystemTagKey.SYNCED_VIDEO.name) ?: return
        if (offsetMs != 0L) {
            dao.insertSongTag(SongTagCrossRef(songId, tag.id))
        } else {
            dao.deleteSongTag(songId, tag.id)
        }
    }

    /**
     * Mantiene la etiqueta predefinida "Remix / Cover" (ver [SystemTagKey.REMIX_COVER]) en línea con
     * [ogTitle] ("Título original" del editor de metadatos): la añade en cuanto deja de estar vacío,
     * la quita en cuanto vuelve a estarlo. Mismo patrón que [syncSyncedVideoTag] -membresía real, fila
     * en `song_tag`, el usuario también puede añadirla/quitarla a mano desde la ficha de la etiqueta-,
     * pero con UN solo sitio que puede cambiar `ogTitle` ([saveSongEdits], el editor de metadatos) en
     * vez de dos: a diferencia de `videoOffsetMs`, no hay ninguna regla del iPod que lo toque.
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
        val previous = previousLanguage?.trim().orEmpty()
        val current = newLanguage?.trim().orEmpty()
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
     */
    suspend fun importCoverImage(uri: Uri, title: String): String = withContext(Dispatchers.IO) {
        val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: error("La carpeta UltiMusic no está concedida")
        val dir = CoverArt.imagesDocPath(appContext, createIfMissing = true) ?: error("La carpeta UltiMusic no está concedida")
        val name = CoverArt.reserveFileName(appContext, CoverArt.sanitizeFileName(title), "img", null)
        val docPath = SafStorage.createFile(appContext, treeUri, dir, name, "image/*")
            ?: error("No se ha podido crear la carátula")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            SafStorage.openOutputStream(appContext, docPath)?.use { output -> input.copyTo(output) }
                ?: error("No se ha podido escribir la carátula")
        } ?: error("No se ha podido leer la imagen seleccionada")
        name
    }

    /**
     * Renombra una imagen ya guardada (carátula propia o miniatura de YouTube) para que coincida
     * con un título nuevo, sin volver a copiarla ni descargarla. Best-effort: si el archivo ya no
     * existe, no hace nada y devuelve el nombre tal cual estaba.
     *
     * @param suffix lo que va detrás del título saneado, p. ej. `" (video)"` para una miniatura o
     *   `""` para la carátula propia.
     */
    suspend fun renameImage(oldName: String, newTitle: String, suffix: String, ext: String): String =
        withContext(Dispatchers.IO) {
            val dir = CoverArt.imagesDocPath(appContext) ?: return@withContext oldName
            if (!SafStorage.exists(appContext, "$dir/$oldName")) return@withContext oldName
            val baseName = CoverArt.sanitizeFileName(newTitle) + suffix
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
    suspend fun downloadVideoThumbnail(videoId: String, title: String): String? =
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
                val baseName = "${CoverArt.sanitizeFileName(title)} (video)"
                val name = CoverArt.reserveFileName(appContext, baseName, "jpg", null)
                val docPath = SafStorage.createFile(appContext, treeUri, dir, name, "image/jpeg") ?: return@runCatching null
                SafStorage.openOutputStream(appContext, docPath)?.use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                } ?: return@runCatching null
                name
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
        dao.deleteSong(song.id)
    }

    /** Borra una carátula importada que ya no usa nadie (best-effort). */
    suspend fun deleteCoverImage(imageName: String) = withContext(Dispatchers.IO) {
        val dir = CoverArt.imagesDocPath(appContext)
        if (dir != null) runCatching { SafStorage.deleteRecursively(appContext, "$dir/$imageName") }
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
            val db = UltiMusicDatabase.get(appContext)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            SafStorage.ensureUltiMusicRegistered(appContext)
            val treeUri = SafStorage.ultiMusicTreeUri(appContext) ?: return@runCatching
            val root = SafStorage.ultiMusicDocPath(appContext) ?: return@runCatching
            val destDir = SafStorage.getOrCreateSubfolder(appContext, treeUri, root, "databases") ?: return@runCatching

            val dbFile = appContext.getDatabasePath(UltiMusicDatabase.DB_NAME)
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

    companion object {
        /** Cada cuánto reconcilia [startWatchingLibraryChanges] mientras la app está en primer plano
         *  (ver su doc: sustituye al `FileObserver` instantáneo de antes de la migración a SAF). */
        private const val WATCH_INTERVAL_MS = 3 * 60 * 1000L

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
