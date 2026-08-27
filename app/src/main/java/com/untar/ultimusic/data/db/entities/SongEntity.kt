package com.untar.ultimusic.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de canciones. El [filePath] es la clave estable que ancla la canción entre
 * escaneos (aunque el [id] autogenerado cambie de instalación a instalación).
 *
 * El álbum (o álbumes: N:M, ver [SongAlbumCrossRef]) al que pertenece esta canción NO vive aquí,
 * a diferencia de los artistas y productores tampoco viven aquí: se enlaza en su propia tabla de
 * cruce ([SongAlbumCrossRef]), con el número de pista/disco de cada enlace como columnas suyas
 * (una misma canción puede ser la pista 5 de un álbum y la 12 de un recopilatorio).
 *
 * Los campos `og*` guardan la info de la canción ORIGINAL cuando esta es un remix (título, artista
 * y año del tema original; no hay `ogAlbum` — se quitó del editor porque Genius se equivocaba
 * demasiado rellenándolo y el dato por sí solo no compensaba pedirlo a mano). No provienen de la
 * etiqueta del archivo: los rellena el usuario desde el editor y quedan a null mientras la canción
 * no se marque como remix.
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
 * [lyricsOffsetMs] es lo mismo que [videoOffsetMs] pero para la letra sincronizada: adelanta o
 * atrasa qué línea toca resaltar respecto al audio, para cuando la letra de lrclib.net no va del
 * todo a tiempo. Se ajusta desde el editor de metadatos, igual que el del vídeo.
 *
 * [hiddenByGreylist] marca una canción como oculta porque su carpeta está desactivada en la lista
 * gris de los ajustes (ver `LibraryDao.setSongsHiddenUnderFolder`). Es solo un filtro de lectura:
 * la fila nunca se borra por esto, así que reactivar la carpeta la devuelve intacta, con sus
 * ediciones, carátula y vídeo.
 *
 * [youtubeViewCount] son las visitas del vídeo de [videoUrl] en YouTube. A diferencia de todo lo
 * demás de aquí arriba, ESTE campo sí sale de la API de YouTube (ver
 * `data/remote/YouTubeStatsApi.kt`), nunca lo escribe el usuario: null mientras la canción no tenga
 * vídeo o todavía no se haya refrescado. Se actualiza solo, como mucho una vez al día (ver
 * `LibraryRepository.refreshYouTubeStatsIfDue`), lo que de paso cumple la política de YouTube de
 * refrescar o borrar cada 30 días los datos sacados de su API — algo que no aplicaba a ningún otro
 * campo de esta tabla porque ninguno viene de ahí.
 *
 * [youtubeChannelId] es el canal que subió ese vídeo, refrescado junto con [youtubeViewCount] y por
 * el mismo motivo. Es un dato puramente interno: no se enseña en ninguna pantalla, solo sirve de
 * materia prima para que [com.untar.ultimusic.data.LibraryRepository.refreshYouTubeStatsIfDue]
 * calcule la "popularidad" de cada artista —el canal que más se repite entre las canciones donde ese
 * artista es el principal— y la guarde en
 * [com.untar.ultimusic.data.db.entities.ArtistEntity.youtubeChannelId]/
 * [com.untar.ultimusic.data.db.entities.ArtistEntity.youtubeChannelSubscriberCount].
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
    val imageName: String?,
    val comment: String?,
    val videoUrl: String?,
    val videoThumbnailName: String?,
    val videoOffsetMs: Long = 0,
    val lyricsOffsetMs: Long = 0,
    val hiddenByGreylist: Boolean = false,
    val youtubeViewCount: Long? = null,
    val youtubeChannelId: String? = null,

    val ogTitle: String?,
    val ogArtist: String?,
    val ogYear: Int?,

    /**
     * Cuándo se creó el archivo en disco (`File.lastModified()`, milisegundos epoch), usado como
     * "fecha de creación" para la etiqueta predefinida "Canciones descargadas recientemente" (ver
     * [com.untar.ultimusic.data.LibraryRepository.resolveSongsOfTag]). Se rellena al escanear (ver
     * `ScannedSong.toEntity`) y se conserva tal cual si la canción solo cambia de carpeta: mover un
     * archivo no debería resetear cuándo se "añadió" a la fonoteca. Default `0` solo para no romper
     * construcciones con nombre existentes; las filas ya guardadas antes de esta columna se
     * rellenan una vez en la migración que la introduce (ver Migrations.kt).
     *
     * `@ColumnInfo(defaultValue = "0")` es a propósito, no solo el default de Kotlin: sin él, el
     * esquema que Room ESPERA (deducido de la entidad) no llevaría ningún `DEFAULT` de verdad, y no
     * coincidiría con el `DEFAULT 0` que la migración necesita poner de verdad en SQLite —SQLite
     * exige un `DEFAULT` para añadir una columna `NOT NULL` a una tabla con filas ya existentes—, lo
     * que rompería la validación de esquema de Room al abrir la base de datos tras migrar.
     */
    @ColumnInfo(defaultValue = "0")
    val dateAdded: Long = 0
)
