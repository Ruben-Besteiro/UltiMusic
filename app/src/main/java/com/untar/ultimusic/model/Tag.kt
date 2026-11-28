package com.untar.ultimusic.model

/**
 * Las 5 etiquetas predefinidas del sistema (ver `Migrations.kt.seedDefaultTags`), en vez de strings
 * mágicos sueltos por el repositorio/migración/seeding. [TagEntity.systemKey] guarda `name` de este
 * enum como texto (Room necesita una columna primitiva); `null` en esa columna es una etiqueta
 * personalizada (ver [TagEditorDialogFragment][com.untar.ultimusic.ui.library.TagEditorDialogFragment]),
 * que no tiene entrada aquí.
 *
 * Hubo una sexta, `DEBUG`, funcionalmente idéntica a la antigua `FAVORITES` pero pensada solo para
 * probar el flujo de añadir/quitar etiquetas antes de que existieran las personalizadas de verdad; se
 * retiró (con migración de BD, ver `migration19To20` en `Migrations.kt`) en cuanto dejó de hacer
 * falta. `FAVORITES` en sí se retiró más tarde por decisión de producto, con el mismo mecanismo (ver
 * `MIGRATION_27_28`).
 *
 * [REMIX_COVER] (ver `migration25To26`) usa membresía real (fila en `song_tag`) en vez de calcularse
 * al vuelo, así que el usuario puede añadirla/quitarla a mano desde la ficha de la etiqueta -tiene su
 * botón "+" y su X por fila, ver `CollectionDetailDialogFragment`-, y además sale en la pestaña
 * Canciones cuando está activo el ajuste "Ver etiquetas en pestaña Canciones" (ver
 * `TagsViewModel.songTagsById`), a diferencia de las 3 calculadas.
 * [com.untar.ultimusic.data.LibraryRepository] la mantiene sincronizada sola con
 * [com.untar.ultimusic.model.Song.ogTitle]: en cuanto ese campo deja de estar vacío se añade sola, y
 * si vuelve a estarlo se quita sola (ver `LibraryRepository.syncRemixCoverTag`). El campo es "Título
 * original" del editor de metadatos -un remix/cover guarda ahí de qué canción original parte, ver
 * CLAUDE.md sobre los campos `og*`-.
 *
 * Hubo otra, `SYNCED_VIDEO` ("Vídeo sincronizado"), retirada en `MIGRATION_32_33`: el modo vídeo
 * saca audio y vídeo de YouTube, así que siempre están sincronizados.
 */
enum class SystemTagKey { RECENTLY_ADDED, NOT_IN_PLAYLIST, NO_CUSTOM_TAGS, REMIX_COVER }

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
