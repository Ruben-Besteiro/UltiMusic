package com.untar.ultimusic.ui.collection

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
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.PersonKind
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.joinNonBlank
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ficha intermedia de una lista o un género: cabecera teñida con el acento de lo que suena (igual
 * que [DetailDialogFragment]) con el resumen de la colección (nº de canciones + duración) debajo,
 * y sus canciones en una lista visualmente igual que la pestaña Canciones (ver
 * [CollectionSongsAdapter]). Se abre al tocar una lista o un género en vez de saltar directamente
 * al iPod (ver [com.untar.ultimusic.ui.library.GenresFragment] y
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
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerCollectionSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
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

        toolbar.title = viewModel.currentKey

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
            onDeleteSong = { song -> showDeleteSongDialog(song) },
            onGoToAlbum = { song -> song.album?.let { DetailDialogFragment.showAlbum(this, it.id) } },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let { DetailDialogFragment.showPerson(this, PersonKind.ARTIST, it.id) }
            },
            onGoToProducer = { song ->
                song.producers.firstOrNull()?.let { DetailDialogFragment.showPerson(this, PersonKind.PRODUCER, it.id) }
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
        toolbar.inflateMenu(R.menu.menu_collection_detail)
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
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        // Mismo acento que el resto de la app: el de lo que suena, no hay carátula
                        // propia de la que sacar uno (ver el comentario equivalente en
                        // DetailDialogFragment).
                        applyAccent(accent, headerBox, toolbar, summaryIcon, summaryText)
                        scrollbar.setAccentColor(accent)
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
        summaryText: TextView
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
    }

    /** Menú de 3 puntos: añadir la colección entera a la cola o a otra lista, y —solo en una
     * lista— eliminarla (ver menu_collection_actions.xml). */
    private fun showCollectionMenu(toolbar: MaterialToolbar, adapter: CollectionSongsAdapter) {
        val anchor = toolbar.findViewById<View>(R.id.action_collection_menu) ?: toolbar
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_collection_actions, menu)
            menu.findItem(R.id.action_delete_playlist)?.isVisible = viewModel.currentKind == CollectionKind.LISTA
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_collection_to_queue -> {
                        playerViewModel.addToQueue(adapter.currentSongs())
                        true
                    }
                    R.id.action_add_collection_to_playlist -> { showAddCollectionToPlaylist(adapter); true }
                    R.id.action_delete_playlist -> { showDeletePlaylistDialog(); true }
                    else -> false
                }
            }
            show()
        }
    }

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

    /** Confirmación antes de borrar la lista (solo el archivo: los audios no se tocan, igual que
     * `PlaylistsFragment.showDeleteDialog`). Cierra la ficha al terminar: ya no queda nada que ver. */
    private fun showDeletePlaylistDialog() {
        val name = viewModel.currentKey ?: return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.playlist_delete)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.playlist_delete_confirm), name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.playlist_delete) { _, _ ->
                playlistsViewModel.delete(name)
                dismiss()
            }
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
    }
}
