package com.untar.ultimusic.model

/**
 * Las 6 etiquetas predefinidas del sistema (ver `Migrations.kt.seedDefaultTags`), en vez de strings
 * mágicos sueltos por el repositorio/migración/seeding. [TagEntity.systemKey] guarda `name` de este
 * enum como texto (Room necesita una columna primitiva); `null` en esa columna es una etiqueta
 * personalizada (ver [TagEditorDialogFragment][com.untar.ultimusic.ui.library.TagEditorDialogFragment]),
 * que no tiene entrada aquí.
 *
 * Hubo una quinta, `DEBUG`, funcionalmente idéntica a [FAVORITES] pero pensada solo para probar el
 * flujo de añadir/quitar etiquetas antes de que existieran las personalizadas de verdad; se retiró
 * (con migración de BD, ver `migration19To20` en `Migrations.kt`) en cuanto dejó de hacer falta.
 *
 * [SYNCED_VIDEO] (ver `migration20To21`) y [REMIX_COVER] (ver `migration25To26`) usan membresía real
 * (fila en `song_tag`) en vez de calcularse al vuelo, igual que [FAVORITES], así que el usuario puede
 * añadirlas/quitarlas a mano desde la ficha de la etiqueta -tienen su botón "+" y su X por fila, ver
 * `CollectionDetailDialogFragment`-, y además salen en la pestaña Canciones cuando está activo el
 * ajuste "Ver etiquetas en pestaña Canciones" (ver `TagsViewModel.songTagsById`), a diferencia de las
 * 3 calculadas. [com.untar.ultimusic.data.LibraryRepository] las mantiene sincronizadas solas con
 * [com.untar.ultimusic.model.Song.videoOffsetMs] y [com.untar.ultimusic.model.Song.ogTitle]
 * respectivamente: en cuanto ese campo deja de estar vacío se añaden solas, y si vuelve a estarlo se
 * quitan solas (ver `LibraryRepository.syncSyncedVideoTag`/`syncRemixCoverTag`). El campo de
 * [REMIX_COVER] es "Título original" del editor de metadatos -un remix/cover guarda ahí de qué
 * canción original parte, ver CLAUDE.md sobre los campos `og*`-.
 */
enum class SystemTagKey { FAVORITES, RECENTLY_ADDED, NOT_IN_PLAYLIST, NO_CUSTOM_TAGS, SYNCED_VIDEO, REMIX_COVER }

/**
 * Etiqueta de dominio. Scaffolding histórico del editor de etiquetas personalizadas: hoy ninguna
 * pantalla la consume directamente, todas usan [TagSummary] (ver `model/Summaries.kt`); el editor
 * de verdad ([TagEditorDialogFragment][com.untar.ultimusic.ui.library.TagEditorDialogFragment])
 * llama al repositorio con nombre/color sueltos en vez de construir uno de estos.
 */
data class Tag(
    val id: Long,
    val name: String,
    val colorArgb: Int,
    val systemKey: String?,
    val sortOrder: Int
)
