package com.untar.ultimusic.util

import com.untar.ultimusic.R
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.GenreSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.PlaylistSummary
import com.untar.ultimusic.model.Song

/**
 * Interfaz para permitir que diferentes tipos de objetos de librería se ordenen con la misma función
 * genérica, evitando choques de firma JVM entre las múltiples extension functions [sortedByOption].
 */
interface SortableLibraryItem {
    val sortName: String
    val sortDuration: Long
    val sortYear: Int?
    val sortPopularity: Long?
    val sortSongCount: Int
}

/**
 * Los cinco criterios de orden que ofrece el diálogo de ordenar (ver
 * [com.untar.ultimusic.ui.sort.SortDialogFragment]). No todos valen para todos los fragmentos: ver
 * [LibraryTab.availableFields].
 *
 * [naturalDirection] es la dirección con la que ordena cada uno con la casilla "Invertir orden" del
 * diálogo DESMARCADA: Nombre de la A a la Z, Número de canciones/Duración/Popularidad de más a
 * menos, Año de menos a más. Marcar la casilla pasa a la contraria ([SortDirection.opposite]),
 * cualquiera que sea el criterio elegido.
 */
enum class SortField(val naturalDirection: SortDirection) {
    NAME(SortDirection.ASCENDING),
    SONG_COUNT(SortDirection.DESCENDING),
    DURATION(SortDirection.DESCENDING),
    YEAR(SortDirection.ASCENDING),
    POPULARITY(SortDirection.DESCENDING)
}

enum class SortDirection {
    ASCENDING, DESCENDING;

    fun opposite(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class SortOption(val field: SortField, val direction: SortDirection) {
    companion object {
        /** Con la que arranca cualquier pestaña la primera vez que se abre (ver [com.untar.ultimusic.data.SortPreferences]). */
        val DEFAULT = SortOption(SortField.NAME, SortDirection.ASCENDING)
    }
}

/**
 * Las seis pestañas que puede ordenar el diálogo, en el mismo orden que el ViewPager
 * ([com.untar.ultimusic.ui.MainPagerAdapter]), con qué criterios tiene sentido ofrecerle a cada una.
 *
 * - Nombre: en las seis.
 * - Número de canciones: en todas menos Canciones (una canción no "tiene" canciones).
 * - Duración: en las seis (para canciones es la suya propia; para el resto, la suma de las suyas).
 * - Año: solo Canciones y Álbumes.
 * - Popularidad: solo Canciones (visitas del vídeo) y Artistas (suscriptores del canal que más se
 *   repite entre sus canciones donde es el artista principal, ver [com.untar.ultimusic.data.db.LibraryDao]).
 */
enum class LibraryTab(val titleRes: Int, val availableFields: List<SortField>) {
    SONGS(R.string.tab_songs, listOf(SortField.NAME, SortField.DURATION, SortField.YEAR, SortField.POPULARITY)),
    ALBUMS(R.string.tab_albums, listOf(SortField.NAME, SortField.SONG_COUNT, SortField.DURATION, SortField.YEAR)),
    ARTISTS(R.string.tab_artists, listOf(SortField.NAME, SortField.SONG_COUNT, SortField.DURATION, SortField.POPULARITY)),
    GENRES(R.string.tab_genres, listOf(SortField.NAME, SortField.SONG_COUNT, SortField.DURATION)),
    TAGS(R.string.tab_tags, listOf(SortField.NAME, SortField.SONG_COUNT, SortField.DURATION)),
    PLAYLISTS(R.string.tab_playlists, listOf(SortField.NAME, SortField.SONG_COUNT, SortField.DURATION));

    /** Si [field] no es válido para esta pestaña, cae a [SortField.NAME]: pasa si [SortPreferences]
     *  tenía guardado un criterio de una versión anterior que ya no aplica aquí. */
    fun sanitize(option: SortOption): SortOption =
        if (option.field in availableFields) option else SortOption.DEFAULT
}

/**
 * Aplica [option] reordenando la lista según el campo pedido; los campos que no tienen sentido para
 * el tipo (p. ej. Año en una lista de artistas) se ignoran cayendo al nombre, para que un
 * [SortField] guardado de otra pestaña nunca deje la lista sin ordenar.
 *
 * Los valores ausentes (año o popularidad sin dato) van SIEMPRE al final, en las dos direcciones -no
 * tiene sentido que lo desconocido parezca "lo más antiguo" en ascendente y "lo más popular" en
 * descendente- y ordenados alfabéticamente entre ellos, ya que el criterio elegido no dice nada de
 * cómo ordenarlos.
 */
fun <T : SortableLibraryItem> List<T>.sortedByOption(option: SortOption): List<T> {
    return when (option.field) {
        SortField.NAME -> sortedByField(option.direction) { it.sortName.lowercase() }
        SortField.DURATION -> sortedByField(option.direction) { it.sortDuration }
        SortField.YEAR -> sortedByNullableField(option.direction, { it.sortYear }) { it.sortName.lowercase() }
        SortField.POPULARITY -> sortedByNullableField(option.direction, { it.sortPopularity }) { it.sortName.lowercase() }
        SortField.SONG_COUNT -> sortedByField(option.direction) { it.sortSongCount }
    }
}

private fun <T, R : Comparable<R>> List<T>.sortedByField(
    direction: SortDirection,
    selector: (T) -> R
): List<T> = if (direction == SortDirection.ASCENDING) sortedBy(selector) else sortedByDescending(selector)

/** Como [sortedByField] pero para un campo que puede faltar ([Comparable]? ): lo ausente se manda
 *  siempre al final, en las dos direcciones (ver el porqué en el javadoc de la clase), ordenado por
 *  [missingSortKey] entre sí en vez de dejarlo en su orden original. */
private fun <T, R : Comparable<R>> List<T>.sortedByNullableField(
    direction: SortDirection,
    selector: (T) -> R?,
    missingSortKey: (T) -> String
): List<T> {
    val (withValue, withoutValue) = partition { selector(it) != null }
    val sorted = if (direction == SortDirection.ASCENDING) {
        withValue.sortedBy(selector)
    } else {
        withValue.sortedByDescending(selector)
    }
    return sorted + withoutValue.sortedBy(missingSortKey)
}
