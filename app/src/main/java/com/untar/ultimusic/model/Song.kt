package com.untar.ultimusic.model

import com.untar.ultimusic.util.SortableLibraryItem

data class Song(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artists: List<Artist>,
    /**
     * Álbumes a los que pertenece, en el orden en que se enlazaron (ver
     * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef.position]); vacío si no está
     * catalogada en ninguno. A diferencia de la época N:1 de la app (v13-v26), una canción puede
     * estar en más de uno a la vez (un recopilatorio y el álbum original, por ejemplo), cada uno
     * con su propia posición ([SongAlbumEntry.trackNumber]/[SongAlbumEntry.discNumber]).
     */
    val albums: List<SongAlbumEntry>,
    val producers: List<Producer>,
    val duration: Long,
    val year: Int?,
    val genres: List<String>,
    val lyrics: String?,
    val language: String?,
    val imageName: String?,
    val comment: String?,

    /** Enlace de YouTube del videoclip, siempre puesto por el usuario. Null si aún no tiene. */
    val videoUrl: String?,

    /** Miniatura de [videoUrl] cacheada como carátula de reserva. Ver
     * [com.untar.ultimusic.data.db.entities.SongEntity.videoThumbnailName]. */
    val videoThumbnailName: String?,

    /** Milisegundos que se adelanta (positivo) o atrasa (negativo) el vídeo respecto al audio local,
     * para corregir videoclips que no van del todo sincronizados. Ver los ajustes del reproductor de
     * vídeo del iPod. */
    val videoOffsetMs: Long,

    /** Milisegundos que se adelanta (positivo) o atrasa (negativo) la letra sincronizada respecto al
     * audio local, para corregir letras de lrclib.net que no van del todo a tiempo. Mismo mecanismo
     * que [videoOffsetMs] pero aplicado en [com.untar.ultimusic.util.LrcParser.currentIndex] en vez
     * de en el vídeo. No afecta a letras sin sincronizar: sin marcas de tiempo no hay nada que
     * desplazar. */
    val lyricsOffsetMs: Long,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogYear: Int?,

    /** Visitas del vídeo de [videoUrl] en YouTube, o null si no tiene vídeo o todavía no se han
     * pedido. Ver [com.untar.ultimusic.data.db.entities.SongEntity.youtubeViewCount] sobre de dónde
     * sale y cada cuánto se refresca. */
    val youtubeViewCount: Long?,

    /** Ver [com.untar.ultimusic.data.db.entities.SongEntity.dateAdded]. Default `0` para no romper
     * construcciones con nombre existentes. */
    val dateAdded: Long = 0
) : SortableLibraryItem {
    /**
     * Álbum "principal" (el primero de [albums]), para el resto de la aplicación que solo necesita
     * enseñar uno (la notificación de reproducción, el menú "ir al álbum" de una ficha de artista...).
     * Null si [albums] está vacío.
     */
    val album: Album? get() = albums.firstOrNull()?.album

    /** Posición dentro de [album] (el principal). Ver [SongAlbumEntry.trackNumber]/[discNumber]. */
    val trackNumber: Int? get() = albums.firstOrNull()?.trackNumber
    val discNumber: Int? get() = albums.firstOrNull()?.discNumber

    override val sortName: String get() = title
    override val sortDuration: Long get() = duration
    override val sortYear: Int? get() = year
    override val sortPopularity: Long? get() = youtubeViewCount
    override val sortSongCount: Int get() = 0
}

/**
 * Un álbum al que pertenece una [Song], con la posición ([trackNumber]/[discNumber]) que ocupa
 * DENTRO de ese álbum concretamente: la misma canción puede ser la pista 5 de un álbum y la 12 de
 * un recopilatorio, así que esa posición es del enlace, no de la canción ni del álbum en sí (ver
 * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]).
 */
data class SongAlbumEntry(
    val album: Album,
    val trackNumber: Int?,
    val discNumber: Int?
)
