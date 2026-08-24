package com.untar.ultimusic.ui.library

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.untar.ultimusic.R
import com.untar.ultimusic.model.SystemTagKey
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.DynamicColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Etiquetas de una canción (ver menú de 3 puntos "Editar etiquetas" y el botón "Ver etiquetas" del
 * iPod): cada una como una "salchicha" (ver [TagsAdapter]/item_tag.xml) con una X para quitarla si
 * es eliminable, y debajo un botón "+ Añadir" que abre [TagPickerDialogFragment].
 *
 * Patrón `AddToPlaylistDialogFragment` (`onCreateDialog` + `AlertDialog`), no el de pantalla completa
 * de [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment].
 *
 * Dos modos, según [songIds] traiga uno o varios (ver [isMultiEdit], selección múltiple de la
 * pestaña Canciones — [com.untar.ultimusic.ui.MainActivity.showEditTagsForSelection]):
 * - **Canción única**: se COLECCIONA [TagsViewModel.tagsOfSong] EN VIVO, sin botón de aceptar de
 *   verdad -"Aceptar" solo cierra-: cada toque (añadir o quitar) escribe en Room al instante, igual
 *   que [TagPickerDialogFragment] hijo (vía `childFragmentManager`), y este diálogo se repinta solo
 *   con lo que Room reemita, sin cerrar ni reabrir nada.
 * - **Selección múltiple**: al abrir se calcula UNA VEZ ([loadInitialPending]) la intersección de
 *   etiquetas con membresía real que tienen TODAS las canciones seleccionadas, y de ahí en adelante
 *   se edita en LOCAL ([pendingTags], sin tocar Room). Solo al pulsar "Aceptar" se aplica de golpe a
 *   TODAS las canciones seleccionadas (ver [applyMultiChanges]/[TagsViewModel.applyTagsToSongs]): lo
 *   añadido se añade a todas, lo quitado se quita de todas, aunque una canción concreta ya la tuviera
 *   o nunca la hubiera tenido.
 */
class SongTagsDialogFragment : DialogFragment() {

    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val songIds: LongArray by lazy { requireArguments().getLongArray(ARG_SONG_IDS) ?: LongArray(0) }
    private val isMultiEdit: Boolean get() = songIds.size > 1

    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView

    /** Solo en modo múltiple: etiquetas pendientes de aplicar a TODAS las canciones seleccionadas al
     *  pulsar "Aceptar" (ver [applyMultiChanges]). Arranca como la intersección calculada en
     *  [loadInitialPending] y se edita en LOCAL hasta el cierre: nada se escribe en Room hasta
     *  aceptar. Un `Map`, no un `Set<Long>`, porque hace falta el nombre/color de cada una para
     *  pintar su fila sin ir a buscarlos a ningún otro sitio (los trae ya [TagPickerDialogFragment]
     *  en su resultado, ver [onTagPickedForSelection]). */
    private val pendingTags = linkedMapOf<Long, TagSummary>()

    /** Snapshot de los ids de [pendingTags] tal como quedó [loadInitialPending], para saber al
     *  aceptar qué se añadió/quitó de verdad (ver [applyMultiChanges]). */
    private var initialTagIds: Set<Long> = emptySet()

    /** [loadInitialPending] es una carga ÚNICA (no una colecta en vivo, a diferencia del modo
     *  canción única): esta bandera evita repetirla si `onStart` se llama más de una vez (p. ej. tras
     *  un cambio de configuración). */
    private var pendingLoaded = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_song_tags, null, false)
        container = content.findViewById(R.id.songTagsContainer)
        emptyView = content.findViewById(R.id.songTagsEmpty)
        emptyView.setText(if (isMultiEdit) R.string.song_tags_empty_multi else R.string.song_tags_empty)
        val btnAddTag = content.findViewById<TextView>(R.id.btnAddTag)
        btnAddTag.setOnClickListener { showTagPicker() }

        if (isMultiEdit) {
            childFragmentManager.setFragmentResultListener(TagPickerDialogFragment.RESULT_KEY, this) { _, bundle ->
                onTagPickedForSelection(bundle)
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(
                if (isMultiEdit) getString(R.string.song_tags_title_multi, songIds.size)
                else getString(R.string.song_tags_title)
            )
            .setView(content)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> if (isMultiEdit) applyMultiChanges() }
            .create()
        dialog.setOnShowListener { AccentTint.buttons(dialog, playerViewModel.accentColor.value) }
        return dialog
    }

    private fun showTagPicker() {
        if (childFragmentManager.findFragmentByTag(TagPickerDialogFragment.TAG) != null) return
        val picker = if (isMultiEdit) {
            TagPickerDialogFragment.newInstanceForSelection(pendingTags.keys)
        } else {
            TagPickerDialogFragment.newInstance(songIds.first())
        }
        picker.show(childFragmentManager, TagPickerDialogFragment.TAG)
    }

    /** Respuesta de [TagPickerDialogFragment] en modo selección múltiple: la etiqueta elegida se
     *  añade en LOCAL a [pendingTags] (nada de Room todavía, ver [applyMultiChanges]) y se repinta. */
    private fun onTagPickedForSelection(bundle: Bundle) {
        val id = bundle.getLong(TagPickerDialogFragment.RESULT_TAG_ID)
        val tag = TagSummary(
            id = id,
            name = bundle.getString(TagPickerDialogFragment.RESULT_TAG_NAME).orEmpty(),
            colorArgb = bundle.getInt(TagPickerDialogFragment.RESULT_TAG_COLOR),
            songCount = 0,
            totalDuration = 0L,
            systemKey = bundle.getString(TagPickerDialogFragment.RESULT_TAG_SYSTEM_KEY)
        )
        pendingTags[id] = tag
        renderMulti()
    }

    // `lifecycleScope`, no `viewLifecycleOwner.lifecycleScope`: este DialogFragment no tiene vista
    // propia (solo onCreateDialog, como AddToPlaylistDialogFragment), pero sí Lifecycle propio que
    // DialogFragment mueve a STARTED/RESUMED en paralelo al diálogo.
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (isMultiEdit) {
                    loadInitialPending()
                } else {
                    tagsViewModel.tagsOfSong(songIds.first()).collect { tags -> renderSingle(tags) }
                }
            }
        }
    }

    /**
     * Calcula UNA VEZ ([pendingLoaded]) la intersección de etiquetas con membresía real (Favoritos o
     * una personalizada; las 3 calculadas nunca cuentan aquí, no se pueden añadir/quitar a mano) que
     * tienen TODAS las canciones de [songIds]. Es una lectura puntual (`first()` de cada
     * [TagsViewModel.tagsOfSong]), no una colecta en vivo: a partir de aquí [pendingTags] vive en
     * LOCAL hasta que se acepte o se cierre el diálogo (ver KDoc de la clase).
     */
    private suspend fun loadInitialPending() {
        if (pendingLoaded) return
        val perSong = songIds.map { id -> tagsViewModel.tagsOfSong(id).first().filter { isRemovable(it) } }
        val commonIds = perSong.map { list -> list.map { it.id }.toSet() }
            .reduceOrNull { a, b -> a intersect b }
            .orEmpty()
        val byId = perSong.flatten().associateBy { it.id }
        pendingTags.clear()
        commonIds.forEach { id -> byId[id]?.let { pendingTags[id] = it } }
        initialTagIds = pendingTags.keys.toSet()
        pendingLoaded = true
        renderMulti()
    }

    /** Eliminable = tiene membresía real: Favoritos, Vídeo sincronizado o una personalizada
     *  (systemKey null). Las 3 calculadas nunca lo son, no hay fila que borrar. Mismo criterio que
     *  [TagsViewModel.isEditable], repetido aquí en vez de reutilizado porque ese trabaja sobre la
     *  ETIQUETA (ficha de una etiqueta) y este sobre las etiquetas DE UNA CANCIÓN. */
    private fun isRemovable(tag: TagSummary) =
        tag.systemKey == null || tag.systemKey == SystemTagKey.FAVORITES.name ||
            tag.systemKey == SystemTagKey.SYNCED_VIDEO.name

    private fun renderSingle(tags: List<TagSummary>) {
        renderRows(tags, isRemovable = ::isRemovable) { tag ->
            tagsViewModel.removeSongFromTag(songIds.first(), tag.id)
        }
    }

    /** Todo lo que hay en [pendingTags] es SIEMPRE eliminable: solo llega ahí membresía real (ver
     *  [loadInitialPending]/[onTagPickedForSelection], que a su vez sale de
     *  [TagsViewModel.assignableTags] o de la propia intersección, ninguna de las dos incluye las 3
     *  calculadas). */
    private fun renderMulti() {
        renderRows(pendingTags.values.toList(), isRemovable = { true }) { tag ->
            pendingTags.remove(tag.id)
            renderMulti()
        }
    }

    /** Pintado común a los dos modos: una fila por etiqueta (ver item_tag_removable.xml), con su X
     *  de quitar visible solo si [isRemovable] lo dice para esa etiqueta. */
    private fun renderRows(tags: List<TagSummary>, isRemovable: (TagSummary) -> Boolean, onRemove: (TagSummary) -> Unit) {
        container.removeAllViews()
        emptyView.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(requireContext())
        for (tag in tags) {
            val row = inflater.inflate(R.layout.item_tag_removable, container, false)
            row.findViewById<TextView>(R.id.tagRemovableName).text = tag.name
            AccentTint.fill(row, R.id.tagRemovableChip, DynamicColor.dim(tag.colorArgb))
            AccentTint.stroke(row, R.id.tagRemovableChip, tag.colorArgb, R.dimen.tag_chip_stroke_width)

            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveTag)
            val removable = isRemovable(tag)
            btnRemove.visibility = if (removable) View.VISIBLE else View.GONE
            if (removable) btnRemove.setOnClickListener { onRemove(tag) }
            container.addView(row)
        }
    }

    /** Compara [pendingTags] contra [initialTagIds] y aplica la diferencia a TODAS las canciones
     *  seleccionadas de golpe (ver [TagsViewModel.applyTagsToSongs]): lo nuevo se añade a todas, lo
     *  que ya no está se quita de todas. Si no hubo ningún cambio, no escribe nada. */
    private fun applyMultiChanges() {
        val finalIds = pendingTags.keys
        val toAdd = finalIds - initialTagIds
        val toRemove = initialTagIds - finalIds
        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            tagsViewModel.applyTagsToSongs(songIds.toList(), toAdd, toRemove)
        }
    }

    companion object {
        private const val ARG_SONG_IDS = "songIds"
        const val TAG = "songTags"

        fun newInstance(songId: Long): SongTagsDialogFragment = newInstance(listOf(songId))

        /** Selección múltiple: más de un id abre el mismo diálogo en modo "editar en lote" (ver
         *  [isMultiEdit]). */
        fun newInstance(songIds: List<Long>): SongTagsDialogFragment = SongTagsDialogFragment().apply {
            arguments = bundleOf(ARG_SONG_IDS to songIds.toLongArray())
        }
    }
}
