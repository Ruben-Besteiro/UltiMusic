package com.untar.ultimusic.data.db.relations

/**
 * Resultados "planos" de las consultas de agregación que alimentan las pestañas de Álbumes,
 * Artistas y Productores.
 *
 * No son entidades ni relaciones de Room: son POJOs de SALIDA. Room permite que una `@Query`
 * devuelva cualquier clase cuyos campos coincidan con los nombres de las columnas del SELECT, y eso
 * es justo lo que hacemos aquí. Se usan en vez de las relaciones (`@Relation`) porque lo que hay
 * que pintar son CUENTAS y SUMAS (nº de canciones, duración total), y calcularlas en SQLite es
 * muchísimo más barato que traerse todas las canciones a memoria para contarlas en Kotlin.
 *
 * Si el álbum/artista/productor no tiene imagen propia, la carátula sale de sus canciones: ver
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

/**
 * Resumen de una persona: vale igual para un artista y para un productor, porque ambos se tratan
 * exactamente igual y sus dos pestañas se pintan con el mismo adaptador.
 */
data class PersonSummaryRow(
    val id: Long,
    val name: String,
    val imageName: String?,
    val songCount: Int,
    val albumCount: Int,
    val totalDuration: Long
)

/** Número de pista de una canción dentro de un álbum concreto (vive en la tabla de cruce). */
data class TrackPosition(
    val songId: Long,
    val trackNumber: Int?
)

/**
 * Una canción candidata al collage de carátulas de un álbum/artista/productor, con solo las tres
 * columnas de las que sale su carátula individual (mismos campos que resuelve
 * `CoverArt.cover(context, song: Song)`). La usa `GroupCoverFetcher` (ver `CoverArt.kt`), que pide
 * estas filas ya en el orden en que el usuario vería esas canciones en la ficha (ver
 * `LibraryDao.collageCandidatesForAlbum`/`collageCandidatesForArtist`/`collageCandidatesForProducer`).
 */
data class CollageCandidateRow(
    val imageName: String?,
    val filePath: String,
    val videoThumbnailName: String?
)
