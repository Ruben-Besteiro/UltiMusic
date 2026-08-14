package com.untar.ultimusic.util

import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song

/**
 * Une trozos de un subtítulo tipo "Artista | Álbum" saltándose los que estén vacíos, para que una
 * canción sin artista o sin álbum (ver [com.untar.ultimusic.data.scan.MusicScanner.UNKNOWN_ARTIST])
 * no deje un separador colgando (" | Álbum" en vez de "Álbum").
 */
fun joinNonBlank(vararg parts: String?, separator: String = " | "): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(separator)

/**
 * Rellena el subtítulo de dos líneas de una fila de canción, compartido por `SongsAdapter`
 * (pestaña «Canciones») y `CollectionSongsAdapter` (ficha de una lista o un género): primera línea
 * solo el artista -aparte, porque con varios puede ser un texto largo-, segunda línea el álbum y el
 * año de la canción y, si la canción tiene vídeo asignado y ya se le conocen visitas (ver
 * [com.untar.ultimusic.model.Song.youtubeViewCount], que se refresca como mucho una vez al día), el
 * icono de YouTube y sus visitas compactadas al final (ver [formatCompactCount]).
 *
 * La línea de artista se oculta entera si viene vacía (sin tag de artista, ver
 * [MusicScanner.UNKNOWN_ARTIST]): dejarla puesta solo dejaría un hueco en blanco encima del álbum.
 */
fun bindSongSubtitle(
    song: Song,
    subtitleArtist: TextView,
    subtitleRest: TextView,
    youtubeIcon: ImageView,
    youtubeViews: TextView
) {
    val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
    subtitleArtist.text = artist
    subtitleArtist.isVisible = artist.isNotBlank()

    subtitleRest.text = joinNonBlank(song.album?.title ?: MusicScanner.UNKNOWN_ALBUM, song.year?.toString())

    // Solo se enseñan visitas de una canción CON vídeo: youtubeViewCount podría quedar como un
    // valor viejo si el usuario quita el enlace sin que haya pasado por medio otro refresco diario.
    val views = song.youtubeViewCount?.takeIf { song.videoUrl != null }
    youtubeIcon.isVisible = views != null
    youtubeViews.isVisible = views != null
    if (views != null) youtubeViews.text = formatCompactCount(views)
}

/**
 * Rellena el icono y el texto de suscriptores de la fila de un artista/productor (ver
 * `PeopleAdapter`/`SearchAdapter`, y [com.untar.ultimusic.model.PersonSummary.popularity]): mismo
 * icono de YouTube y mismo formato compacto que en [bindSongSubtitle], sin ningún texto de reserva
 * ("suscriptores") porque aquí no hay sitio de sobra — la palabra sí sale en la ficha de detalle
 * (ver `DetailViewModel.toHeader`).
 *
 * Siempre oculto para un productor: su [com.untar.ultimusic.model.PersonSummary.popularity] es
 * siempre null (ver el comentario de esa propiedad), así que basta con mirarlo aquí sin distinguir
 * de qué pestaña viene la fila.
 */
fun bindPersonSubscribers(popularity: Long?, youtubeIcon: ImageView, youtubeSubscribers: TextView) {
    youtubeIcon.isVisible = popularity != null
    youtubeSubscribers.isVisible = popularity != null
    if (popularity != null) youtubeSubscribers.text = formatCompactCount(popularity)
}
