package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de la tabla de etiquetas. Las 6 predefinidas (Favoritos, Descargada recientemente, En
 * ninguna lista, Sin etiquetas personalizadas, Vídeo sincronizado, Remix / Cover) se siembran al
 * instalar/migrar (ver `Migrations.kt.seedDefaultTags`) con [systemKey] a uno de los valores de
 * [com.untar.ultimusic.model.SystemTagKey]; una etiqueta personalizada futura tendrá [systemKey] a
 * null.
 *
 * Favoritos, Vídeo sincronizado y Remix / Cover usan membresía real (tabla `song_tag`, ver
 * [SongTagCrossRef]): las otras 3 predefinidas se calculan al vuelo a partir de la biblioteca (ver
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
    val sortOrder: Int,
    /**
     * True para una etiqueta de IDIOMA (ver [com.untar.ultimusic.data.LibraryRepository.syncLanguageTag]):
     * se crea sola con el nombre del idioma detectado en la letra y no cuelga de [SystemTagKey] (hay
     * una por idioma, no un valor fijo del enum), así que necesita su propio candado. Tiene las mismas
     * restricciones que una predefinida -no se puede renombrar, recolorear, borrar ni asignar/quitar de
     * una canción a mano (ver `LibraryDao.updateTag`/`deleteTag`, `TagsViewModel.isEditable`/
     * `assignableTags`, `SongTagsDialogFragment.isRemovable`)-, pero SÍ tiene membresía real en
     * `song_tag`, a diferencia de las 3 predefinidas calculadas al vuelo. Siempre false para el resto
     * de etiquetas (predefinidas de verdad y personalizadas del usuario).
     */
    val isAutoAssigned: Boolean = false
)
