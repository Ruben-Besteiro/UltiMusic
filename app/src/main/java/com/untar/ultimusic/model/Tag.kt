package com.untar.ultimusic.model

/**
 * Las 4 etiquetas predefinidas del sistema (ver `Migrations.kt.seedDefaultTags`), en vez de strings
 * mágicos sueltos por el repositorio/migración/seeding. [TagEntity.systemKey] guarda `name` de este
 * enum como texto (Room necesita una columna primitiva); `null` en esa columna es una etiqueta
 * personalizada (ver [TagEditorDialogFragment][com.untar.ultimusic.ui.library.TagEditorDialogFragment]),
 * que no tiene entrada aquí.
 *
 * Hubo una quinta, `DEBUG`, funcionalmente idéntica a [FAVORITES] pero pensada solo para probar el
 * flujo de añadir/quitar etiquetas antes de que existieran las personalizadas de verdad; se retiró
 * (con migración de BD, ver `migration19To20` en `Migrations.kt`) en cuanto dejó de hacer falta.
 *
 * [SYNCED_VIDEO] es la más nueva (ver `migration20To21`): igual que [FAVORITES], usa membresía real
 * (fila en `song_tag`) en vez de calcularse al vuelo, así que el usuario puede añadirla/quitarla a
 * mano desde la ficha de la etiqueta -tiene su botón "+" y su X por fila, ver
 * `CollectionDetailDialogFragment`-, pero además [com.untar.ultimusic.data.LibraryRepository] la
 * mantiene sincronizada sola con [com.untar.ultimusic.model.Song.videoOffsetMs]: en cuanto ese
 * desplazamiento deja de ser 0 se añade sola, y si vuelve a 0 se quita sola (ver
 * `LibraryRepository.syncSyncedVideoTag`).
 */
enum class SystemTagKey { FAVORITES, RECENTLY_ADDED, NOT_IN_PLAYLIST, NO_CUSTOM_TAGS, SYNCED_VIDEO }

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
