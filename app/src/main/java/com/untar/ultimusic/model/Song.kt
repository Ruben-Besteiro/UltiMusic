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
    /** Código de país tal como lo da MusicBrainz (p. ej. "JP", "US"; "XW" es "en todo el mundo"),
     * de la publicación elegida al autorrellenar (ver [com.untar.ultimusic.model.MetadataSuggestion.
     * country]). También editable a mano. */
    val country: String?,
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

    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
