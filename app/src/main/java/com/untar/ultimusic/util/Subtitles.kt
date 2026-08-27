package com.untar.ultimusic.util

import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song

/**
 * Une trozos de un subtítulo tipo "Artista | Álbum" saltándose los que estén vacíos (o null), para
 * que un trozo ausente no deje un separador colgando (" | Álbum" en vez de "Álbum").
 */
fun joinNonBlank(vararg parts: String?, separator: String = " | "): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(separator)

/**
 * Nombre(s) de artista para mostrar, o el placeholder [MusicScanner.UNKNOWN_ARTIST] si la canción
 * no tiene ninguno etiquetado.
 */
fun Song.artistDisplay(): String =
    artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }

/**
 * Título(s) de álbum para mostrar -todos los de [Song.albums], no solo el principal ([Song.album])-
 * o el placeholder [MusicScanner.UNKNOWN_ALBUM] (vacío) si la canción no tiene ninguno. Al ser
 * vacío, [joinNonBlank] ya se lo salta -junto con el separador que lo acompañaría- sin necesidad de
 * mirar aquí si la canción tiene o no artista.
 */
fun Song.albumDisplay(): String =
    albums.joinToString(", ") { it.album.title }.ifBlank { MusicScanner.UNKNOWN_ALBUM }

/**
 * Rellena el subtítulo de dos líneas de una fila de canción, compartido por `SongsAdapter`
 * (pestaña «Canciones») y `CollectionSongsAdapter` (ficha de una lista o un género): primera línea
 * solo el artista -aparte, porque con varios puede ser un texto largo-, segunda línea el álbum y el
 * año de la canción y, si la canción tiene vídeo asignado y ya se le conocen visitas (ver
 * [com.untar.ultimusic.model.Song.youtubeViewCount], que se refresca como mucho una vez al día), el
 * icono de YouTube y sus visitas compactadas al final (ver [formatCompactCount]).
 */
fun bindSongSubtitle(
    song: Song,
    subtitleArtist: TextView,
    subtitleRest: TextView,
    youtubeIcon: ImageView,
    youtubeViews: TextView
) {
    subtitleArtist.text = song.artistDisplay()
    subtitleArtist.isVisible = true

    val rest = joinNonBlank(song.albumDisplay(), song.year?.toString())
    subtitleRest.text = rest
    // Sin álbum ni año no hay nada que enseñar en esta línea: ocultarla del todo, si no se queda
    // vacía pero sigue ocupando su alto y deja un hueco en blanco entre el artista y las etiquetas.
    subtitleRest.isVisible = rest.isNotBlank()

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
