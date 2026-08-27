package com.untar.ultimusic.model

import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.SortableLibraryItem

/**
 * Modelos de DOMINIO para las pestañas de Álbumes y Artistas y para sus fichas de detalle. Son
 * "resúmenes" y no las entidades completas ([Album], [Artist]) porque lo que se pinta en esas
 * pantallas son datos AGREGADOS —cuántas canciones, cuánto dura todo— que la entidad por sí sola
 * no conoce; los calcula la base de datos.
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

/** Resumen de un artista, para su pestaña y su ficha de detalle. */
data class PersonSummary(
    val id: Long,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val totalDuration: Long,
    val cover: CoverRef,
    /** Suscriptores del canal de YouTube que más se repite entre las canciones donde este artista es
     *  el PRINCIPAL (excluye colaboraciones donde solo aparece como invitado), o null si no hay
     *  ningún canal así (sin vídeos, sin datos todavía, o ninguno con datos válidos). Ver
     *  [com.untar.ultimusic.data.db.LibraryDao.observeArtistSummaries]. */
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
 * todas. A diferencia de álbumes/artistas, un género no es una entidad propia de la base
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

/**
 * Resumen de una etiqueta para su pestaña: nombre, color de borde/"salchicha" (ver
 * [com.untar.ultimusic.util.DynamicColor.dim] para el relleno), cuántas canciones tiene y cuánto
 * duran entre todas. A diferencia de [GenreSummary], una etiqueta SÍ es una entidad propia de la
 * base de datos ([com.untar.ultimusic.data.db.entities.TagEntity]) — de ahí el [id] —, pero su
 * recuento de canciones se sigue calculando aquí en Kotlin (ver
 * [com.untar.ultimusic.data.LibraryRepository.resolveSongsOfTag]) porque 3 de las 6 predefinidas no
 * tienen membresía guardada en ninguna tabla, se derivan al vuelo de la biblioteca.
 *
 * [systemKey] (ver [com.untar.ultimusic.model.SystemTagKey]) va aquí, no solo en [Tag]/[TagEntity],
 * porque la UI necesita saberlo sin resolver nada aparte: para decidir si la X de "quitar etiqueta"
 * se muestra (solo si tiene membresía real: `null`, `FAVORITES`, `SYNCED_VIDEO` o `REMIX_COVER`) y
 * para filtrarlas del buscador de "+ Añadir" (las 3 calculadas nunca aparecen ahí, ver
 * `TagsViewModel.assignableTags`).
 *
 * [isAutoAssigned] (ver [com.untar.ultimusic.data.db.entities.TagEntity.isAutoAssigned]) viaja igual
 * que [systemKey] por el mismo motivo: una etiqueta de idioma tiene [systemKey] a null (no cuelga de
 * un valor fijo de [com.untar.ultimusic.model.SystemTagKey]) pero necesita las mismas restricciones
 * que una predefinida, así que la UI la reconoce por este campo en vez de por `systemKey`.
 */
data class TagSummary(
    val id: Long,
    val name: String,
    val colorArgb: Int,
    val songCount: Int,
    val totalDuration: Long,
    val systemKey: String?,
    val isAutoAssigned: Boolean = false
) : SortableLibraryItem {
    override val sortName: String get() = name
    override val sortDuration: Long get() = totalDuration
    override val sortYear: Int? get() = null
    override val sortPopularity: Long? get() = null
    override val sortSongCount: Int get() = songCount
}
