package com.untar.ultimusic.ui.collection

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.AddSongsToTagDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.SongTagsDialogFragment
import com.untar.ultimusic.ui.library.TagsViewModel
import com.untar.ultimusic.ui.playlists.AddSongsToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.PlaylistResumeStore
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.joinNonBlank
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ficha intermedia de una lista, un género o una etiqueta: cabecera teñida con el acento de lo que
 * suena (igual que [DetailDialogFragment]) con el resumen de la colección (nº de canciones +
 * duración) debajo, y sus canciones en una lista visualmente igual que la pestaña Canciones (ver
 * [CollectionSongsAdapter]). Se abre al tocar una lista, un género o una etiqueta en vez de saltar
 * directamente al iPod (ver [com.untar.ultimusic.ui.library.GenresFragment],
 * [com.untar.ultimusic.ui.library.TagsFragment] y
 * [com.untar.ultimusic.ui.playlists.PlaylistsFragment]): aquí se elige qué sonará, y tocar una fila
 * empieza a reproducir la colección de una — la ventana del iPod, si se abre después, solo refleja
 * lo que ya está sonando (ver [com.untar.ultimusic.ui.player.IPodDialogFragment]).
 *
 * Lleva su propio mini-reproductor abajo, igual que [DetailDialogFragment] y por el mismo motivo:
 * se entra a mirar/elegir canciones sin querer necesariamente controlar la reproducción de golpe.
 */
class CollectionDetailDialogFragment : DialogFragment() {

    private val viewModel: CollectionDetailViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()
    private val tagsViewModel: TagsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
        val args = requireArguments()
        viewModel.setTarget(
            CollectionKind.valueOf(args.getString(ARG_KIND)!!),
            args.getString(ARG_KEY)!!
        )
        // El de una lista se resuelve con el PlaylistsViewModel COMPARTIDO de la actividad, no con
        // uno propio de esta ficha: así comparte su mismo tick y se entera al instante de cualquier
        // cambio de pertenencia, venga de donde venga (ver playlistSongsSource en el ViewModel).
        viewModel.bindPlaylistSongs { name -> playlistsViewModel.songsOf(name) }
        // Igual que arriba pero para una etiqueta: TagsViewModel COMPARTIDO de la actividad, no uno
        // propio. bindTagName resuelve el nombre actual a partir del id (ver
        // CollectionDetailViewModel.displayTitle): una fila de `tags` que ya no exista devuelve
        // null, y el `map` lo convierte en cadena vacía para no dejar el título a medio pintar.
        viewModel.bindTagSongs { id -> tagsViewModel.songsOfTag(id) }
        viewModel.bindTagName { id -> tagsViewModel.tags.map { list -> list.firstOrNull { it.id == id }?.name } }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_collection_detail, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onResume() {
        super.onResume()
        // Mismo tick compartido que PlaylistsFragment.onResume: por si se editaron los archivos de
        // lista desde fuera de la app. Un género no tiene nada que releer aparte de la biblioteca,
        // que ya es reactiva por sí sola, así que esta llamada no le afecta.
        playlistsViewModel.refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.collectionRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.collectionToolbar)
        val headerBox = view.findViewById<View>(R.id.collectionHeader)
        val summaryIcon = view.findViewById<ImageView>(R.id.collectionSummaryIcon)
        val summaryText = view.findViewById<TextView>(R.id.collectionSummaryText)
        val resumeBlock = view.findViewById<View>(R.id.collectionResumeBlock)
        val resumeIcon = view.findViewById<ImageView>(R.id.collectionResumeIcon)
        val resumeText = view.findViewById<TextView>(R.id.collectionResumeText)
        val btnResume = view.findViewById<MaterialButton>(R.id.btnResumePlaylist)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerCollectionSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val btnAddSongs = view.findViewById<FloatingActionButton>(R.id.btnAddSongs)
        val miniPlayer = view.findViewById<View>(R.id.miniPlayer)
        val miniProgress = view.findViewById<SeekBar>(R.id.songProgress)
        MiniPlayerController(miniPlayer, miniProgress, playerViewModel, parentFragmentManager, viewLifecycleOwner)
            .bind()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        var itemTouchHelper: ItemTouchHelper? = null
        lateinit var adapter: CollectionSongsAdapter
        adapter = CollectionSongsAdapter(
            reorderable = viewModel.reorderable,
            onSongClick = { position ->
                playerViewModel.playCollection(
                    adapter.currentSongs(), position, viewModel.currentKey, viewModel.currentKind
                )
            },
            onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) },
            // Vía el PlaylistsViewModel compartido, no un método propio de esta ficha: así el
            // nuevo orden también bombea el mismo tick que ve la pestaña de Listas (ver
            // viewModel.bindPlaylistSongs).
            onReordered = { newOrder ->
                viewModel.currentKey?.let { key ->
                    playlistsViewModel.reorder(key, newOrder.map { File(it.filePath).name })
                }
            },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, EDITOR_TAG)
                }
            },
            onEditTags = { song ->
                if (parentFragmentManager.findFragmentByTag(SongTagsDialogFragment.TAG) == null) {
                    SongTagsDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, SongTagsDialogFragment.TAG)
                }
            },
            onDeleteSong = { song -> showDeleteSongDialog(song) },
            onGoToAlbum = { song -> song.album?.let { DetailDialogFragment.showAlbum(this, it.id) } },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let { DetailDialogFragment.showArtist(this, it.id) }
            },
            // Solo llega a pulsarse con la X visible, es decir con currentKind ya resuelto a LISTA o
            // a una etiqueta editable (ver el `when` de más abajo); el `!!`/currentTagId() son
            // seguros por lo mismo.
            onRemove = { song ->
                when (viewModel.currentKind) {
                    CollectionKind.LISTA -> playlistsViewModel.removeSongs(
                        viewModel.currentKey!!, listOf(File(song.filePath).name)
                    )
                    CollectionKind.TAG -> tagsViewModel.removeSongFromTag(song.id, currentTagId())
                    else -> Unit
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(viewModel.songs.value.getOrNull(position)?.title)
        }

        // Arrastre solo vertical desde el manejador de cada fila; un género no lo engancha, ver
        // CollectionSongsAdapter.reorderable (su manejador ya viene oculto).
        if (viewModel.reorderable) {
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun isLongPressDragEnabled() = false
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }
                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    adapter.commitReorder()
                }
            }
            itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recycler) }
        }

        toolbar.setNavigationOnClickListener { dismiss() }
        btnAddSongs.setOnClickListener { showAddSongs() }
        toolbar.inflateMenu(R.menu.menu_collection_detail)
        // El menú de 3 puntos solo sigue teniendo sentido para un Género: renombrar/borrar una Lista
        // o una Etiqueta vive ahora en el menú de 3 puntos de su propia fila (PlaylistsAdapter /
        // TagsAdapter), y "añadir a la cola"/"añadir a lista" de toda la colección ya no se ofrece
        // ahí para esos dos casos (ver showCollectionMenu).
        toolbar.menu.findItem(R.id.action_collection_menu)?.isVisible =
            viewModel.currentKind == CollectionKind.GENRE
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_shuffle -> {
                    playerViewModel.shuffleCollection(adapter.currentSongs(), viewModel.currentKey, viewModel.currentKind)
                    true
                }
                R.id.action_collection_menu -> { showCollectionMenu(toolbar, adapter); true }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Género/Lista lo pintan de golpe (su clave YA es el nombre); Etiqueta lo resuelve
                // buscando su id en TagsViewModel.tags, así que se colecta en vez de asignarse una
                // sola vez (ver CollectionDetailViewModel.displayTitle sobre el porqué).
                launch {
                    viewModel.displayTitle.collect { title -> toolbar.title = title }
                }
                // Botón "+" y X de cada fila: en una LISTA los dos se enseñan siempre (a cualquiera
                // se le puede añadir/quitar una canción). En una ETIQUETA solo con membresía real
                // (ver TagsViewModel.isEditable) -Favoritos, Vídeo sincronizado o una personalizada-,
                // nunca en las 3 calculadas. En un GÉNERO ninguno de los dos llega a mostrarse
                // (deriva solo de los metadatos, no se puede tocar a mano).
                when (viewModel.currentKind) {
                    CollectionKind.LISTA -> {
                        btnAddSongs.isVisible = true
                        adapter.setRemovable(true, R.string.remove_song_from_playlist_desc)
                    }
                    CollectionKind.TAG -> launch {
                        // Se colecta tagsViewModel.tags entero (no solo la actual) porque es lo que
                        // ya observa el resto de la app para esa lista, y aquí hace falta systemKey,
                        // que displayTitle no trae.
                        tagsViewModel.tags.collect { tags ->
                            val tag = tags.firstOrNull { it.id == viewModel.currentKey?.toLongOrNull() }
                            val editable = tag != null && tagsViewModel.isEditable(tag)
                            btnAddSongs.isVisible = editable
                            adapter.setRemovable(editable, R.string.remove_song_from_tag_desc)
                        }
                    }
                    else -> Unit
                }
                launch {
                    combine(viewModel.songs, CoverArt.revision) { list, _ -> list }.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        summaryText.text = joinNonBlank(
                            resources.getQuantityString(R.plurals.song_count, list.size, list.size),
                            TimeFormat.hhmmss(list.sumOf { it.duration })
                        )
                    }
                }
                // "Posición actual"/REANUDAR: SOLO para una Lista (ver PlaylistResumeStore). Se
                // recalcula con cada cambio de canciones/contexto de reproducción, no solo una vez,
                // para que el texto siga la pista mientras esta misma lista suena (el botón se
                // oculta entonces, pero el texto de arriba sigue vivo) y para detectar al vuelo
                // cuando deja de sonar.
                launch {
                    combine(
                        viewModel.songs,
                        playerViewModel.currentCollectionKind,
                        playerViewModel.currentPlaylistName,
                        playerViewModel.currentSong
                    ) { list, kind, playingName, _ -> Triple(list, kind, playingName) }
                        .collect { (list, kind, playingName) ->
                            val playlistName = viewModel.currentKey
                            val savedSong = if (viewModel.currentKind == CollectionKind.LISTA && playlistName != null) {
                                val savedId = PlaylistResumeStore.getLastSongId(playlistName)
                                list.firstOrNull { it.id == savedId }
                            } else {
                                null
                            }
                            if (savedSong == null) {
                                resumeBlock.visibility = View.GONE
                                return@collect
                            }
                            resumeBlock.visibility = View.VISIBLE
                            resumeText.text = getString(R.string.playlist_current_position, savedSong.title)
                            val playingThisPlaylist = kind == CollectionKind.LISTA && playingName == playlistName
                            btnResume.visibility = if (playingThisPlaylist) View.GONE else View.VISIBLE
                            btnResume.setOnClickListener {
                                val index = list.indexOfFirst { it.id == savedSong.id }
                                if (index >= 0) {
                                    playerViewModel.playCollection(list, index, playlistName, CollectionKind.LISTA)
                                }
                            }
                        }
                }
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        // Mismo acento que el resto de la app: el de lo que suena, no hay carátula
                        // propia de la que sacar uno (ver el comentario equivalente en
                        // DetailDialogFragment).
                        applyAccent(accent, headerBox, toolbar, summaryIcon, summaryText, resumeIcon, resumeText, btnResume)
                        scrollbar.setAccentColor(accent)
                        // El botón "+" es chrome de la app (como btnCreateTag de TagsFragment), no
                        // el color propio de la etiqueta: sí le aplica la regla de amarillo dinámico.
                        btnAddSongs.backgroundTintList = ColorStateList.valueOf(accent)
                        AccentTint.contentOnAccent(btnAddSongs, accent)
                    }
                }
            }
        }
    }

    private fun applyAccent(
        accent: Int,
        headerBox: View,
        toolbar: MaterialToolbar,
        summaryIcon: ImageView,
        summaryText: TextView,
        resumeIcon: ImageView,
        resumeText: TextView,
        btnResume: MaterialButton
    ) {
        val background = DynamicColor.asBackground(accent)
        val onBackground = DynamicColor.onColor(background)

        headerBox.setBackgroundColor(background)
        toolbar.setTitleTextColor(onBackground)
        toolbar.setNavigationIconTint(onBackground)
        toolbar.menu.findItem(R.id.action_shuffle)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_collection_menu)?.icon?.setTint(onBackground)
        summaryIcon.setColorFilter(onBackground)
        summaryText.setTextColor(onBackground)
        // Solo se pintan de golpe con el resto de la cabecera: su visibilidad/texto los maneja el
        // otro `launch` de arriba (ver PlaylistResumeStore), este solo repinta el color.
        resumeIcon.setColorFilter(onBackground)
        resumeText.setTextColor(onBackground)
        btnResume.setTextColor(onBackground)
        btnResume.iconTint = ColorStateList.valueOf(onBackground)
    }

    /** Menú de 3 puntos: añadir la colección entera a la cola o a otra lista (ver
     * menu_collection_actions.xml). Solo llega a mostrarse para un Género (ver `onViewCreated`,
     * que oculta el icono que lo abre para Lista/Etiqueta). */
    private fun showCollectionMenu(toolbar: MaterialToolbar, adapter: CollectionSongsAdapter) {
        val anchor = toolbar.findViewById<View>(R.id.action_collection_menu) ?: toolbar
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_collection_actions, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_collection_to_queue -> {
                        playerViewModel.addToQueue(adapter.currentSongs())
                        true
                    }
                    R.id.action_add_collection_to_playlist -> { showAddCollectionToPlaylist(adapter); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Abre el diálogo de "añadir canciones de golpe" que toque según [CollectionKind.currentKind]:
     *  [AddSongsToPlaylistDialogFragment] para una lista, [AddSongsToTagDialogFragment] para una
     *  etiqueta (con el botón "+" ya oculto para género y para una etiqueta calculada, ver el
     *  `launch` de `tagsViewModel.tags` de más arriba, esto no debería llegar a llamarse para
     *  ninguno de los dos). */
    private fun showAddSongs() {
        when (viewModel.currentKind) {
            CollectionKind.LISTA -> {
                val name = viewModel.currentKey ?: return
                if (parentFragmentManager.findFragmentByTag(AddSongsToPlaylistDialogFragment.TAG) == null) {
                    AddSongsToPlaylistDialogFragment.newInstance(name)
                        .show(parentFragmentManager, AddSongsToPlaylistDialogFragment.TAG)
                }
            }
            CollectionKind.TAG -> {
                if (parentFragmentManager.findFragmentByTag(AddSongsToTagDialogFragment.TAG) == null) {
                    AddSongsToTagDialogFragment.newInstance(currentTagId(), viewModel.displayTitle.value)
                        .show(parentFragmentManager, AddSongsToTagDialogFragment.TAG)
                }
            }
            else -> Unit
        }
    }

    /** [viewModel.currentKey] como `Long`, válido mientras se esté mirando una etiqueta (ver
     *  [CollectionDetailViewModel] sobre por qué la clave es el id como texto solo para
     *  [CollectionKind.TAG]). Solo lo llaman sitios que ya comprobaron `currentKind == TAG` antes -el
     *  botón "+" y la X de cada fila, ambos ocultos para lista/género/etiqueta calculada-, así que el
     *  `!!` no debería saltar nunca en la práctica. */
    private fun currentTagId(): Long = viewModel.currentKey!!.toLong()

    /** Igual que `SongsFragment.showAddToPlaylist`. */
    private fun showAddToPlaylist(song: Song) {
        if (parentFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filename = File(song.filePath).name
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(listOf(filename))
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(listOf(filename), names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Igual que `DetailDialogFragment.showAddAlbumToPlaylist`, pero con toda la colección. */
    private fun showAddCollectionToPlaylist(adapter: CollectionSongsAdapter) {
        if (parentFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filenames = adapter.currentSongs().map { File(it.filePath).name }
            if (filenames.isEmpty()) return@launch
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(filenames)
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(filenames, names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo (igual que en
     * DetailDialogFragment/SongsFragment). */
    private fun showDeleteSongDialog(song: Song) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ -> viewModel.deleteSong(song) }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_KEY = "key"
        private const val TAG = "collectionDetail"
        private const val EDITOR_TAG = "metadataEditor"

        private fun show(manager: FragmentManager, kind: CollectionKind, key: String) {
            // Guarda anti-duplicado: dos toques rápidos abrirían dos fichas apiladas.
            if (manager.findFragmentByTag(TAG) != null) return
            CollectionDetailDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, kind.name)
                    putString(ARG_KEY, key)
                }
            }.show(manager, TAG)
        }

        fun showPlaylist(from: Fragment, name: String) = show(from.parentFragmentManager, CollectionKind.LISTA, name)

        fun showGenre(from: Fragment, name: String) = show(from.parentFragmentManager, CollectionKind.GENRE, name)

        /** [id], no nombre: ver la decisión de diseño de usar el id como clave de una etiqueta. */
        fun showTag(from: Fragment, id: Long) = show(from.parentFragmentManager, CollectionKind.TAG, id.toString())
    }
}
