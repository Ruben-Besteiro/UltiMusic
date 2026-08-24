package com.untar.ultimusic.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.SongCheckboxAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Añadir varias canciones de golpe a una etiqueta (botón "+" de
 * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment] en la ficha de una etiqueta con
 * membresía real, ver [TagsViewModel.isEditable]): pantalla completa con un buscador y la lista de
 * canciones que TODAVÍA no la tienen ([TagsViewModel.songsNotInTag]), cada una con su casilla.
 *
 * Marcar/desmarcar una casilla NO escribe nada en Room -a diferencia de
 * [com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment], que aplica cada toque al instante-:
 * aquí se acumula en [checked] hasta que se pulsa el tick de la barra de arriba, que aplica TODO de
 * golpe ([TagsViewModel.applyTagsToSongs]) y cierra. La X cierra sin aplicar nada. Mismo patrón de
 * "aceptar en bloque" que el modo de selección múltiple de [SongTagsDialogFragment], aquí en la
 * dirección contraria (una etiqueta fija, varias canciones elegidas, en vez de varias canciones
 * fijas, etiquetas elegidas).
 *
 * Usa el mismo layout ([R.layout.dialog_add_songs]) que
 * [com.untar.ultimusic.ui.playlists.AddSongsToPlaylistDialogFragment]: las dos pantallas son
 * idénticas salvo el título, así que no hay dos copias del XML que puedan desincronizarse -mismo
 * motivo por el que `SearchAdapter` reutiliza `item_song.xml` en vez de tener el suyo propio-.
 */
class AddSongsToTagDialogFragment : DialogFragment() {

    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private val tagId: Long by lazy { requireArguments().getLong(ARG_TAG_ID) }
    private val tagName: String by lazy { requireArguments().getString(ARG_TAG_NAME).orEmpty() }

    private val query = MutableStateFlow("")

    /** Ids marcados, pendientes de aplicar al pulsar el tick (ver la cabecera de la clase). */
    private val checked = MutableStateFlow<Set<Long>>(emptySet())

    private lateinit var adapter: SongCheckboxAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_add_songs, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.addSongsRoot)
        val bar = view.findViewById<View>(R.id.addSongsBar)
        val title = view.findViewById<TextView>(R.id.addSongsTitle)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseAddSongs)
        val btnConfirm = view.findViewById<ImageButton>(R.id.btnConfirmAddSongs)
        val searchInput = view.findViewById<EditText>(R.id.addSongsSearchInput)
        val list = view.findViewById<RecyclerView>(R.id.addSongsList)
        val emptyView = view.findViewById<TextView>(R.id.addSongsEmpty)

        title.text = getString(R.string.add_songs_to_tag_title, tagName)

        // La barra de estado es transparente en este tema, así que la barra de arriba se aparta ella
        // sola de la hora y la batería, igual que SettingsDialogFragment/MetadataEditorDialogFragment.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
            bar.updatePadding(top = bars.top)
            insets
        }

        adapter = SongCheckboxAdapter(onToggle = { song -> toggle(song.id) })
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        btnClose.setOnClickListener { dismiss() }
        btnConfirm.setOnClickListener { applyAndClose() }
        searchInput.doAfterTextChanged { text -> query.value = text?.toString().orEmpty() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tagsViewModel.songsNotInTag(tagId, query).collect { songs ->
                        adapter.submit(songs)
                        emptyView.isVisible = songs.isEmpty()
                    }
                }
                launch {
                    checked.collect { ids -> adapter.setChecked(ids) }
                }
                launch {
                    playerViewModel.accentColor.collect { accent -> adapter.setAccentColor(accent) }
                }
            }
        }
    }

    private fun toggle(songId: Long) {
        checked.value = checked.value.toMutableSet().apply {
            if (!add(songId)) remove(songId)
        }
    }

    /** Aplica de golpe lo marcado ([TagsViewModel.applyTagsToSongs], solo añadir) y cierra. Sin nada
     *  marcado no hace ninguna escritura de más. */
    private fun applyAndClose() {
        val ids = checked.value
        if (ids.isNotEmpty()) {
            tagsViewModel.applyTagsToSongs(ids.toList(), addTagIds = setOf(tagId), removeTagIds = emptySet())
        }
        dismiss()
    }

    companion object {
        private const val ARG_TAG_ID = "tagId"
        private const val ARG_TAG_NAME = "tagName"
        const val TAG = "addSongsToTag"

        fun newInstance(tagId: Long, tagName: String): AddSongsToTagDialogFragment =
            AddSongsToTagDialogFragment().apply {
                arguments = bundleOf(ARG_TAG_ID to tagId, ARG_TAG_NAME to tagName)
            }
    }
}
