package com.untarlamanteca.ultimusic.model

import com.untarlamanteca.ultimusic.util.CoverRef

/**
 * Modelos de DOMINIO para las pestañas de Álbumes, Artistas y Productores y para sus fichas de
 * detalle. Son "resúmenes" y no las entidades completas ([Album], [Artist], [Producer]) porque lo
 * que se pinta en esas pantallas son datos AGREGADOS —cuántas canciones, cuánto dura todo— que la
 * entidad por sí sola no conoce; los calcula la base de datos.
 */

data class AlbumSummary(
    val id: Long,
    val title: String,
    val artistName: String?,
    val year: Int?,
    val songCount: Int,
    val totalDuration: Long,
    val cover: CoverRef
)

/**
 * Resumen de una persona. El mismo modelo vale para un artista y para un productor: se tratan
 * exactamente igual, así que sus dos pestañas comparten adaptador y ficha de detalle.
 */
data class PersonSummary(
    val id: Long,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val totalDuration: Long,
    val cover: CoverRef
)

/** Una canción dentro de un álbum, con el número de pista que ocupa en él (puede no tenerlo). */
data class AlbumTrack(
    val song: Song,
    val trackNumber: Int?
)
