package com.untar.ultimusic.model

/**
 * Una publicación (álbum o EP) del catálogo oficial de un artista, sacada de
 * [com.untar.ultimusic.data.remote.MusicBrainzApi] para la discografía de su ficha (ver
 * [com.untar.ultimusic.ui.library.DiscographyDialogFragment]). No es un [Album] de la fonoteca:
 * puede no tener ninguna canción en el dispositivo, que es justo lo que esa ficha enseña (en gris)
 * para lo que falta.
 */
data class DiscographyAlbum(
    val title: String,
    val year: Int?,
    val coverUrl: String?,
    val tracks: List<DiscographyTrack>
)

/** Una pista dentro de [DiscographyAlbum.tracks], en el orden del catálogo oficial.
 *  [discNumber] es 1 para una publicación de un solo disco/medio (ver
 *  [com.untar.ultimusic.data.remote.MusicBrainzApi]). */
data class DiscographyTrack(
    val title: String,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long?
)
