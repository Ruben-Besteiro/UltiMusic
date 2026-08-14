package com.untar.ultimusic.model

import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.SortableLibraryItem

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
) : SortableLibraryItem {
    override val sortName: String get() = title
    override val sortDuration: Long get() = totalDuration
    override val sortYear: Int? get() = year
    override val sortPopularity: Long? get() = null
    override val sortSongCount: Int get() = songCount
}

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
    val cover: CoverRef,
    /** Suscriptores del canal de YouTube que más se repite entre las canciones donde esta persona es
     *  la artista PRINCIPAL (excluye colaboraciones donde solo aparece como invitada), o null si no
     *  hay ningún canal así (sin vídeos, sin datos todavía, o ninguno con datos válidos). Solo se
     *  calcula para artistas, nunca para productores (ver
     *  [com.untar.ultimusic.data.db.LibraryDao.observeArtistSummaries] y el enunciado de
     *  [com.untar.ultimusic.util.LibraryTab.ARTISTS]: la popularidad es una excepción explícita a la
     *  regla de tratar artistas y productores igual). */
    val popularity: Long?
) : SortableLibraryItem {
    override val sortName: String get() = name
    override val sortDuration: Long get() = totalDuration
    override val sortYear: Int? get() = null
    override val sortPopularity: Long? get() = popularity
    override val sortSongCount: Int get() = songCount
}

/**
 * Resumen de un género para su pestaña: su nombre, cuántas canciones lo llevan y cuánto duran entre
 * todas. A diferencia de álbumes/artistas/productores, un género no es una entidad propia de la base
 * de datos —no tiene ficha, id ni portada—: es solo el texto que trae cada canción en su lista de
 * géneros (ver [Song.genres]), agrupado aquí nada más que para pintar la pestaña (ver
 * [com.untar.ultimusic.data.LibraryRepository.genres]).
 */
data class GenreSummary(
    val name: String,
    val songCount: Int,
    val totalDuration: Long
) : SortableLibraryItem {
    override val sortName: String get() = name
    override val sortDuration: Long get() = totalDuration
    override val sortYear: Int? get() = null
    override val sortPopularity: Long? get() = null
    override val sortSongCount: Int get() = songCount
}
