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
 * Los campos `sample*` sostienen la cadena de failsafe de la carátula: si el álbum/artista/productor
 * no tiene imagen propia, se usa la de una de sus canciones; si esa tampoco la tiene, se extrae el
 * arte embebido del archivo de audio (`sampleSongPath`); y si tampoco hay, se usa la miniatura de
 * YouTube de una de sus canciones (`sampleSongVideoThumbnail`). Ver `CoverArt.cover(...)`.
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
    val totalDuration: Long,
    val sampleSongImage: String?,
    val sampleSongPath: String?,
    val sampleSongVideoThumbnail: String?
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
    val totalDuration: Long,
    val sampleSongImage: String?,
    val sampleSongPath: String?,
    val sampleSongVideoThumbnail: String?
)

/** Número de pista de una canción dentro de un álbum concreto (vive en la tabla de cruce). */
data class TrackPosition(
    val songId: Long,
    val trackNumber: Int?
)
