package com.untar.ultimusic.model

import com.untar.ultimusic.util.SortableLibraryItem

data class Song(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artists: List<Artist>,
    /** Álbum al que pertenece, o null si no está catalogada en ninguno. A diferencia de artistas y
     * productores, una canción pertenece como mucho a UN álbum (ver [com.untar.ultimusic.data.db.entities.SongEntity.albumId]). */
    val album: Album?,
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

    /** Posición dentro de [album] (número de pista y de disco). Null si no pertenece a ningún
     * álbum o si el álbum no la trae. */
    val trackNumber: Int?,
    val discNumber: Int?,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?,

    /** Visitas del vídeo de [videoUrl] en YouTube, o null si no tiene vídeo o todavía no se han
     * pedido. Ver [com.untar.ultimusic.data.db.entities.SongEntity.youtubeViewCount] sobre de dónde
     * sale y cada cuánto se refresca. */
    val youtubeViewCount: Long?
) : SortableLibraryItem {
    override val sortName: String get() = title
    override val sortDuration: Long get() = duration
    override val sortYear: Int? get() = year
    override val sortPopularity: Long? get() = youtubeViewCount
    override val sortSongCount: Int get() = 0
}
