package com.untar.ultimusic.data.db.relations

/**
 * Resultados "planos" de las consultas de agregación que alimentan las pestañas de Álbumes y
 * Artistas.
 *
 * No son entidades ni relaciones de Room: son POJOs de SALIDA. Room permite que una `@Query`
 * devuelva cualquier clase cuyos campos coincidan con los nombres de las columnas del SELECT, y eso
 * es justo lo que hacemos aquí. Se usan en vez de las relaciones (`@Relation`) porque lo que hay
 * que pintar son CUENTAS y SUMAS (nº de canciones, duración total), y calcularlas en SQLite es
 * muchísimo más barato que traerse todas las canciones a memoria para contarlas en Kotlin.
 *
 * Si el álbum/artista no tiene imagen propia, la carátula sale de sus canciones: ver
 * `GroupCoverSource`/`GroupCoverFetcher` en `CoverArt.kt`, que resuelven bajo demanda el collage
 * (o, si no da para uno, la carátula de una sola canción) a partir de [CollageCandidateRow].
 *
 * El sufijo `Row` los distingue de los modelos de DOMINIO del mismo nombre (`model/Summaries.kt`),
 * que son los que consume la interfaz; los mappers convierten unos en otros.
 */

data class AlbumSummaryRow(
    val id: Long,
    val title: String,
    val imageName: String?,
    val year: Int?,
    val artistName: String?,
    val songCount: Int,
    val totalDuration: Long
)

/** Resumen de un artista, para su pestaña y su ficha de detalle. */
data class PersonSummaryRow(
    val id: Long,
    val name: String,
    val imageName: String?,
    val songCount: Int,
    val albumCount: Int,
    val totalDuration: Long,
    /** Ver [com.untar.ultimusic.model.PersonSummary.popularity]. */
    val popularity: Long?
)

/**
 * Clave de agrupación para detectar álbumes duplicados (ver `LibraryDao.mergeDuplicateAlbums`):
 * mismo [tagTitle] y mismo primer artista enlazado son, en la práctica, el mismo álbum.
 * [firstArtistId] es null si el álbum no tiene ningún artista enlazado; esas filas no se agrupan
 * entre sí (dos álbumes sin artista con el mismo título podrían ser homónimos de verdad distintos).
 */
data class AlbumGroupRow(
    val id: Long,
    val tagTitle: String,
    val firstArtistId: Long?
)

/**
 * Una canción candidata al collage de carátulas de un álbum/artista, con solo las tres columnas de
 * las que sale su carátula individual (mismos campos que resuelve
 * `CoverArt.cover(context, song: Song)`). La usa `GroupCoverFetcher` (ver `CoverArt.kt`), que pide
 * estas filas ya en el orden en que el usuario vería esas canciones en la ficha (ver
 * `LibraryDao.collageCandidatesForAlbum`/`collageCandidatesForArtist`).
 */
data class CollageCandidateRow(
    val imageName: String?,
    val filePath: String,
    val videoThumbnailName: String?
)

/** Una canción con vídeo, solo con lo que hace falta para pedir sus visitas a YouTube: su id (para
 *  guardar la respuesta) y el enlace (del que se saca el id de 11 caracteres del vídeo, ver
 *  `YouTubeUrl.videoId`). La usa `LibraryRepository.refreshYouTubeStatsIfDue`. */
data class SongVideoRow(
    val id: Long,
    val videoUrl: String
)

/**
 * Ruta y duración (ms) de una canción ya guardada, para el emparejamiento por contenido de
 * `LibraryDao.reconcile`: cuando un archivo se mueve Y se renombra a la vez, ni la ruta ni el
 * nombre sirven de ancla, pero la duración exacta del audio no cambia por mover o renombrar el
 * archivo (a diferencia del título, que el usuario puede haber sobrescrito desde el editor).
 */
data class SongPathDurationRow(
    val filePath: String,
    val duration: Long
)

/** Un canal candidato a "el canal de este artista", con cuántas de sus canciones principales lo
 *  llevan. Ver `LibraryDao.artistChannelCandidates`: no es un resumen para pintar, es la materia
 *  prima con la que `LibraryRepository.refreshYouTubeStatsIfDue` calcula la moda de cada artista. */
data class ArtistChannelCandidateRow(
    val artistId: Long,
    val channelId: String,
    val cnt: Int
)
