package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de canciones. El [filePath] es la clave estable que ancla la canción entre
 * escaneos (aunque el [id] autogenerado cambie de instalación a instalación).
 *
 * Los campos `og*` guardan la info de la canción ORIGINAL cuando esta es un remix (título, artista,
 * álbum y año del tema original). No provienen de la etiqueta del archivo: los rellena el usuario
 * desde el editor y quedan a null mientras la canción no se marque como remix.
 *
 * [videoUrl] es el enlace de YouTube del videoclip, para el modo vídeo del iPod. Lo introduce SIEMPRE
 * el usuario (a mano en el editor, o eligiendo un vídeo en el buscador que abre el propio iPod): no
 * se obtiene nunca de la API de YouTube. Esa distinción importa, porque las políticas de YouTube
 * obligan a borrar o refrescar cada 30 días los datos sacados de su API, mientras que lo que teclea
 * el usuario es dato nuestro y se puede guardar indefinidamente.
 *
 * [videoThumbnailName] es la miniatura de ese vídeo, recortada al cuadrado y cacheada como una
 * carátula más (ver `util/CoverArt.kt`): sirve de carátula de reserva cuando la canción no tiene
 * imagen propia ni arte embebido.
 *
 * [videoOffsetMs] adelanta (positivo) o atrasa (negativo) el vídeo respecto al audio local, para
 * cuando el videoclip no va del todo sincronizado. Se ajusta desde los ajustes del reproductor de
 * vídeo del iPod y nunca toca el audio, solo la posición a la que se pide el vídeo.
 *
 * [hiddenByGreylist] marca una canción como oculta porque su carpeta está desactivada en la lista
 * gris de los ajustes (ver `LibraryDao.setSongsHiddenUnderFolder`). Es solo un filtro de lectura:
 * la fila nunca se borra por esto, así que reactivar la carpeta la devuelve intacta, con sus
 * ediciones, carátula y vídeo.
 *
 * El productor NO está aquí: vive en su propia tabla ([ProducerEntity]) enlazada por
 * [SongProducerCrossRef], igual que los artistas, porque tiene pestaña y ficha propias.
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val title: String,
    val duration: Long,
    val year: Int?,
    val genres: List<String>,
    val lyrics: String?,
    val language: String?,
    val country: String?,
    val imageName: String?,
    val comment: String?,
    val videoUrl: String?,
    val videoThumbnailName: String?,
    val videoOffsetMs: Long = 0,
    val hiddenByGreylist: Boolean = false,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
