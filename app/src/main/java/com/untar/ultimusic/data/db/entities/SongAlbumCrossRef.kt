package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Relación N:M canción ↔ álbum: una canción puede estar catalogada en más de un álbum a la vez
 * (un recopilatorio y el álbum original, por ejemplo), cada uno con su propia posición dentro de
 * él. Segunda vuelta sobre esto: la relación nació N:M, [MIGRATION_12_13][com.untar.ultimusic.data.db.MIGRATION_12_13]
 * la simplificó a N:1 (columna `albumId` directa en `songs`) y esta tabla la trae de vuelta (ver
 * `MIGRATION_26_27` en `Migrations.kt`).
 *
 * [trackNumber]/[discNumber] son la posición de la canción DENTRO de [albumId] concretamente: la
 * misma canción puede ser la pista 5 de un álbum y la 12 de un recopilatorio, así que viven aquí,
 * no en [SongEntity] (que ya no tiene ninguna de las dos columnas).
 *
 * [position] es el orden en que el usuario fue añadiendo álbumes a la canción desde el editor de
 * metadatos (0 = el primero, el que se ve siempre; el resto los añade "+ Añadir otro álbum" — ver
 * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.addAlbumGroup]). El primero
 * (`position = 0`) es el "álbum principal" que usa el resto de la aplicación cuando solo hace
 * falta enseñar uno (notificación de reproducción, menú "ir al álbum"...), igual que
 * [com.untar.ultimusic.data.db.entities.SongArtistCrossRef.position] con el artista principal.
 *
 * `CASCADE` en los dos sentidos (a diferencia de la vieja columna `albumId`, que era `SET_NULL`):
 * ahora esto es una tabla de cruce como `song_artist`/`song_producer`, así que borrar cualquiera de
 * los dos lados debe borrar solo el enlace, nunca dejar una fila huérfana a medias.
 */
@Entity(
    tableName = "song_album",
    primaryKeys = ["songId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId")]
)
data class SongAlbumCrossRef(
    val songId: Long,
    val albumId: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val position: Int = 0
)
