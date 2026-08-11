package com.untar.ultimusic.model

data class Song(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artists: List<Artist>,
    val albums: List<Album>,
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
    val ogAlbum: String?,
    val ogYear: Int?
)
