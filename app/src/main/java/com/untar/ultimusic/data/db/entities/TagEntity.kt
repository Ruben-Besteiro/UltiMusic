package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de etiquetas. Las 4 predefinidas (Favoritos, Descargadas recientemente, En
 * ninguna lista, Sin etiquetas personalizadas) se siembran al instalar/migrar (ver
 * `Migrations.kt.seedDefaultTags`) con [systemKey] a uno de los valores de
 * [com.untar.ultimusic.model.SystemTagKey]; una etiqueta personalizada futura tendrá [systemKey] a
 * null.
 *
 * Solo Favoritos usa membresía real (tabla `song_tag`, ver [SongTagCrossRef]): las otras 3
 * predefinidas se calculan al vuelo a partir de la biblioteca (ver
 * [com.untar.ultimusic.data.LibraryRepository.resolveSongsOfTag]), sin ninguna fila en `song_tag`.
 */
@Entity(tableName = "tags", indices = [Index(value = ["systemKey"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    /** `SystemTagKey.name`, o null para una etiqueta personalizada futura. SQLite permite varios
     *  NULL en un índice único, así que futuras etiquetas custom no chocan entre sí. */
    val systemKey: String?,
    val sortOrder: Int
)
