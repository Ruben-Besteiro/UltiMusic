package com.untar.ultimusic.ui.library

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.TextSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Buscador para añadir una etiqueta. Al principio (sin texto) salen todas las etiquetas asignables
 * (ver [TagsViewModel.assignableTags]: nunca las 3 predefinidas calculadas) menos las que ya estén
 * excluidas; tocar una fila NO cierra el diálogo -se puede seguir buscando y añadiendo varias
 * seguidas-, la fila tocada desaparece sola de la lista en el siguiente `collect`.
 *
 * Dos modos, según cómo se construya (ver [newInstance]/[newInstanceForSelection]):
 * - **Canción única** ([songId] no nulo, el de siempre): "ya la tiene" se lee en vivo de
 *   [TagsViewModel.tagsOfSong] y tocar una fila la añade DIRECTAMENTE en Room
 *   ([TagsViewModel.addSongToTag]). Lo abre [SongTagsDialogFragment] con una sola canción.
 * - **Selección múltiple** ([songId] nulo): "ya la tiene" es [excludedIds], una lista en LOCAL que
 *   el llamador inicializa con sus etiquetas pendientes y que este diálogo va ampliando por su
 *   cuenta con cada toque (mismo efecto visual que el modo de una sola canción, sin tocar Room).
 *   Tocar una fila NO escribe nada aquí: avisa por [RESULT_KEY] a quien lo abrió (ver
 *   [SongTagsDialogFragment] en modo múltiple), que es quien decide qué hacer con la elección.
 */
class TagPickerDialogFragment : DialogFragment() {

    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private val songId: Long? by lazy {
        requireArguments().getLong(ARG_SONG_ID, NO_SONG_ID).takeIf { it != NO_SONG_ID }
    }

    /** Solo en modo selección múltiple (ver KDoc de la clase): arranca con
     *  [ARG_EXCLUDED_IDS] y se amplía en LOCAL con cada etiqueta que se toque aquí. */
    private val excludedIds = MutableStateFlow<Set<Long>>(emptySet())

    private val query = MutableStateFlow("")
    private lateinit var adapter: TagPickerAdapter

    // `by lazy`, no un `val` de construcción directa: en construcción todavía no hay `arguments`
    // (se fijan DESPUÉS, en `newInstance().apply { ... }`) ni Activity a la que colgar
    // `activityViewModels()`, y esto usa las dos (vía `songId`/`tagsViewModel`). Al ser `lazy`, no se
    // evalúa hasta el primer acceso real, en `onStart()`, cuando el fragmento ya está adjunto.
    private val pickerList: Flow<List<TagSummary>> by lazy {
        val alreadyHas: Flow<Set<Long>> = songId?.let { id ->
            tagsViewModel.tagsOfSong(id).map { list -> list.map { it.id }.toSet() }
        } ?: excludedIds
        combine(
            tagsViewModel.assignableTags, alreadyHas, query
        ) { assignable, currentIds, rawQuery ->
            val normalized = TextSearch.normalize(rawQuery)
            assignable.filter { it.id !in currentIds && TextSearch.contains(it.name, normalized) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        excludedIds.value = requireArguments().getLongArray(ARG_EXCLUDED_IDS)?.toSet().orEmpty()

        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_tag_picker, null, false)
        val searchInput = content.findViewById<EditText>(R.id.tagSearchInput)
        val list = content.findViewById<RecyclerView>(R.id.tagPickerList)

        adapter = TagPickerAdapter(onTagClick = { tag -> onTagPicked(tag) })
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        searchInput.doAfterTextChanged { text -> query.value = text?.toString().orEmpty() }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.tag_picker_title)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener { AccentTint.buttons(dialog, playerViewModel.accentColor.value) }
        return dialog
    }

    /** Modo canción única: escribe en Room de una vez, como siempre. Modo selección múltiple: ni
     *  toca Room ni sabe qué hará el llamador con esto, solo (1) hace que la fila desaparezca sola
     *  de la lista, igual que en modo canción única, y (2) lo notifica por [RESULT_KEY]. */
    private fun onTagPicked(tag: TagSummary) {
        val id = songId
        if (id != null) {
            tagsViewModel.addSongToTag(id, tag.id)
            return
        }
        excludedIds.value = excludedIds.value + tag.id
        setFragmentResult(
            RESULT_KEY,
            bundleOf(
                RESULT_TAG_ID to tag.id,
                RESULT_TAG_NAME to tag.name,
                RESULT_TAG_COLOR to tag.colorArgb,
                RESULT_TAG_SYSTEM_KEY to tag.systemKey
            )
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pickerList.collect { list -> adapter.submit(list) }
            }
        }
    }

    companion object {
        private const val ARG_SONG_ID = "songId"
        private const val ARG_EXCLUDED_IDS = "excludedIds"
        private const val NO_SONG_ID = -1L
        const val TAG = "tagPicker"

        /** Resultado de una elección en modo selección múltiple (ver [newInstanceForSelection]):
         *  quien escuche [RESULT_KEY] recibe la etiqueta completa (no solo el id) para poder
         *  pintarla sin tener que ir a buscarla a ningún otro sitio. */
        const val RESULT_KEY = "tagPickerResult"
        const val RESULT_TAG_ID = "tagId"
        const val RESULT_TAG_NAME = "tagName"
        const val RESULT_TAG_COLOR = "tagColor"
        const val RESULT_TAG_SYSTEM_KEY = "tagSystemKey"

        /** Modo canción única (comportamiento de siempre). */
        fun newInstance(songId: Long): TagPickerDialogFragment = TagPickerDialogFragment().apply {
            arguments = bundleOf(ARG_SONG_ID to songId)
        }

        /** Modo selección múltiple (ver [SongTagsDialogFragment] con más de una canción): no escribe
         *  nada en Room, solo avisa de cada elección por [RESULT_KEY]. [excludedTagIds] son las que
         *  ya están en la lista pendiente del llamador, para no ofrecerlas de nuevo. */
        fun newInstanceForSelection(excludedTagIds: Collection<Long>): TagPickerDialogFragment =
            TagPickerDialogFragment().apply {
                arguments = bundleOf(ARG_EXCLUDED_IDS to excludedTagIds.toLongArray())
            }
    }
}
