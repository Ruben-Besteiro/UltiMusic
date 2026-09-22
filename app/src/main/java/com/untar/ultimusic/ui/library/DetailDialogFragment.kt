package com.untar.ultimusic.ui.library

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.attachSwipeToQueue
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.SongsViewModel
import com.untar.ultimusic.ui.editor.AlbumEditorDialogFragment
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.sort.SortDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.LibraryTab
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ficha de un álbum o un artista: cabecera teñida con el acento de lo que suena (el mismo que
 * lleva el resto de la aplicación, en su versión oscura de fondo) y, debajo, sus canciones.
 *
 * Es un [DialogFragment] a pantalla completa por lo mismo que el editor de metadatos y la ventana
 * del iPod: se abre encima de la pantalla principal sin cambiar de Activity. A diferencia de esas
 * otras ventanas (buscador, editores, ajustes, iPod), esta SÍ lleva su propio mini-reproductor
 * abajo (ver [MiniPlayerController]): es la única pantalla a la que se entra a mirar/gestionar
 * canciones sin querer necesariamente controlar la reproducción, así que tiene sentido poder
 * pausarla, reanudarla o abrir el iPod sin volver antes a la pantalla principal.
 *
 * Los dos tipos comparten pantalla; cuál es se pasa como argumento y lo resuelve [DetailViewModel].
 */
class DetailDialogFragment : DialogFragment() {

    private val viewModel: DetailViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val songsViewModel: SongsViewModel by activityViewModels()

    /** Color de fondo actual de la cabecera; se guarda para reteñir sus líneas al recrearlas. */
    private var currentHeaderColor: Int = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
        val args = requireArguments()
        viewModel.setTarget(
            DetailKind.valueOf(args.getString(ARG_KIND)!!),
            args.getLong(ARG_ID)
        )
        // Ver DetailViewModel.bindSongsSort: el ojo de esta ficha (solo para un artista) comparte
        // criterio con la pestaña Canciones, así que se apunta al SongsViewModel COMPARTIDO de la
        // actividad, no a uno propio de esta ficha.
        viewModel.bindSongsSort { songsViewModel.sort }
    }

    /** El botón atrás del sistema, con una selección múltiple activa (ver [DetailViewModel.selectedIds]),
     *  la limpia en vez de cerrar la ficha entera — igual que [MainActivity.selectionBackCallback]
     *  con la pestaña Canciones. */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        object : ComponentDialog(requireContext(), theme) {
            @Suppress("DEPRECATION")
            override fun onBackPressed() {
                if (viewModel.selectedIds.value.isNotEmpty()) viewModel.clearSelection() else super.onBackPressed()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_library_detail, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        // Sin esto, el sistema dejaría la vista por debajo de la barra de estado aunque el tema la
        // pinte transparente, y la cabecera de color no llegaría hasta arriba del todo.
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.detailRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.detailToolbar)
        val headerBox = view.findViewById<View>(R.id.detailHeader)
        val cover = view.findViewById<ShapeableImageView>(R.id.detailCover)
        val infoLines = view.findViewById<LinearLayout>(R.id.detailInfoLines)
        val recyclerAlbums = view.findViewById<RecyclerView>(R.id.recyclerDetailAlbums)
        val albumsDivider = view.findViewById<View>(R.id.detailAlbumsDivider)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerDetailSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val miniPlayer = view.findViewById<View>(R.id.miniPlayer)
        val miniProgress = view.findViewById<SeekBar>(R.id.songProgress)
        MiniPlayerController(miniPlayer, miniProgress, playerViewModel, parentFragmentManager, viewLifecycleOwner)
            .bind()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // El hueco de arriba se lo come la toolbar (que está DENTRO de la cabecera de color),
            // así que ese color llega hasta detrás de la hora y la batería.
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        val adapter = DetailSongsAdapter(
            // Se fija en onCreate (ver setTarget), antes de que este método se ejecute.
            currentKind = viewModel.currentKind!!,
            albumId = viewModel.currentAlbumId,
            // Con selección activa, tocar una fila la marca/desmarca en vez de reproducir; igual
            // que SongsFragment (ver el comentario de DetailSongsAdapter sobre selección múltiple).
            onSongClick = { position ->
                if (viewModel.selectedIds.value.isEmpty()) {
                    playerViewModel.playCollection(viewModel.songs, position, collectionKind = collectionKind())
                } else {
                    viewModel.tracks.value.getOrNull(position)?.let { viewModel.toggleSelection(it.id) }
                }
            },
            onSongLongClick = { position ->
                viewModel.tracks.value.getOrNull(position)?.let { song ->
                    if (viewModel.selectedIds.value.isEmpty()) viewModel.startSelection(song.id)
                    else viewModel.toggleSelection(song.id)
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
            onDeleteSong = { song -> showDeleteDialog(song) },
            onGoToAlbum = { song ->
                song.album?.let { showAlbum(this, it.id) }
            },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let { showArtist(this, it.id) }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(viewModel.tracks.value.getOrNull(position)?.title)
        }

        // Arrastrar una fila hacia la derecha la añade a la cola, como el propio "Añadir a cola" del
        // menú de 3 puntos (ver SwipeToQueue.kt); aquí todas las filas son canciones, sin cabecera.
        attachSwipeToQueue(recycler, accentColor = { playerViewModel.accentColor.value }) { position ->
            viewModel.tracks.value.getOrNull(position)?.let { playerViewModel.addToQueue(it) }
        }

        // Carrusel de álbumes: solo sale en la ficha de un artista (ver DetailViewModel.albums, que
        // en un álbum va siempre vacío).
        val albumsAdapter = DetailAlbumsAdapter(
            onAlbumClick = { album -> showAlbum(this, album.id) }
        )
        recyclerAlbums.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recyclerAlbums.adapter = albumsAdapter

        toolbar.setNavigationOnClickListener {
            if (viewModel.selectedIds.value.isEmpty()) dismiss() else viewModel.clearSelection()
        }
        toolbar.inflateMenu(R.menu.menu_library_detail)
        // El menú de 3 puntos sale en los dos casos, con contenido distinto (ver
        // showAlbumMenu/showArtistMenu más abajo).
        toolbar.menu.findItem(R.id.action_more_menu)?.isVisible = true
        // El ojo de ordenar y el de discografía, al revés: solo en la de un artista (ver
        // menu_library_detail.xml).
        toolbar.menu.findItem(R.id.action_sort_artist_songs)?.isVisible = viewModel.currentKind == DetailKind.ARTIST
        // La discografía (MusicBrainz) necesita UN artista real: "Otros" agrupa a varios a la vez,
        // así que no tiene una que pedir (ver DetailViewModel.currentArtistId).
        toolbar.menu.findItem(R.id.action_discography)?.isVisible =
            viewModel.currentKind == DetailKind.ARTIST && viewModel.currentArtistId != PersonSummary.OTHERS_ARTIST_ID
        toolbar.menu.findItem(R.id.action_discography_album)?.isVisible = viewModel.currentKind == DetailKind.ALBUM
        toolbar.menu.findItem(R.id.action_play_album)?.isVisible = viewModel.currentKind == DetailKind.ALBUM
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_discography -> {
                    val artistId = viewModel.currentArtistId
                    val artistName = viewModel.header.value?.title
                    if (artistId != null && artistName != null) {
                        DiscographyDialogFragment.show(this, artistId, artistName)
                    }
                    true
                }
                R.id.action_discography_album -> {
                    showAlbumDiscography()
                    true
                }
                R.id.action_play_album -> {
                    if (viewModel.songs.isNotEmpty()) {
                        playerViewModel.playCollection(viewModel.songs, 0, collectionKind = collectionKind())
                    }
                    true
                }
                R.id.action_shuffle -> {
                    playerViewModel.shuffleCollection(viewModel.songs, collectionKind = collectionKind())
                    true
                }
                R.id.action_more_menu -> {
                    // Con selección activa este mismo botón (icono de 3 puntos, ver
                    // menu_library_detail.xml) pasa a abrir el menú de la selección múltiple en vez
                    // del de álbum/artista (ver el `launch` de selectedIds más abajo, que oculta el
                    // resto de items mientras tanto).
                    if (viewModel.selectedIds.value.isEmpty()) {
                        when (viewModel.currentKind) {
                            DetailKind.ALBUM -> showAlbumMenu(toolbar)
                            // "Otros" no es un artista de verdad -no hay nada que renombrar, la
                            // única acción de este menú- así que ni se abre (ver
                            // DetailViewModel.currentArtistId).
                            DetailKind.ARTIST ->
                                if (viewModel.currentArtistId != PersonSummary.OTHERS_ARTIST_ID) showArtistMenu(toolbar)
                            null -> Unit
                        }
                    } else {
                        showSelectionMenu(toolbar.findViewById<View>(R.id.action_more_menu) ?: toolbar)
                    }
                    true
                }
                R.id.action_sort_artist_songs -> {
                    if (parentFragmentManager.findFragmentByTag(SortDialogFragment.TAG) == null) {
                        SortDialogFragment.newInstance(LibraryTab.SONGS).show(parentFragmentManager, SortDialogFragment.TAG)
                    }
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Ver el comentario de CoverArt.revision: editar solo la carátula del álbum (o de
                    // una de sus canciones, de la que puede salir la de un artista) no cambia nada
                    // en el DetailHeader que sale de Room si el nombre de archivo se reutiliza, así
                    // que sin esto la cabecera no se enteraría.
                    combine(viewModel.header, CoverArt.revision) { header, _ -> header }.collect { header ->
                        if (header != null) bindHeader(header, toolbar, cover, infoLines)
                        // Con selección activa el título se lo pisa el `launch` de selectedIds de
                        // más abajo (cuenta de seleccionadas); si esto llega DESPUÉS mientras se
                        // sigue seleccionando (p. ej. cambia la carátula, ver CoverArt.revision),
                        // hay que devolvérselo o se perdería la cuenta a medio seleccionar.
                        if (header != null && viewModel.selectedIds.value.isNotEmpty()) {
                            toolbar.title = resources.getQuantityString(
                                R.plurals.song_selection_count,
                                viewModel.selectedIds.value.size, viewModel.selectedIds.value.size
                            )
                        }
                    }
                }
                launch {
                    combine(viewModel.tracks, CoverArt.revision) { list, _ -> list }.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    combine(viewModel.albums, CoverArt.revision) { list, _ -> list }.collect { list ->
                        albumsAdapter.submit(list)
                        val visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                        recyclerAlbums.visibility = visibility
                        albumsDivider.visibility = visibility
                    }
                }
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        // Toda la ficha sigue el acento de LO QUE SUENA, el mismo que lleva el
                        // resto de la aplicación, no el de la carátula de esta ficha: así no
                        // desentona con el mini-reproductor de abajo ni con el resto de pantallas.
                        // applyAccent lo pasa por [DynamicColor.asBackground] para el fondo de la
                        // cabecera; la barra de scroll lo usa a plena intensidad.
                        applyAccent(accent, headerBox, toolbar, infoLines)
                        scrollbar.setAccentColor(accent)
                        adapter.setAccentColor(accent)
                    }
                }
                // Selección múltiple por pulsación larga (ver DetailSongsAdapter/DetailViewModel):
                // el botón de 3 puntos pasa a abrir su menú (ver R.id.action_more_menu de más arriba)
                // y el resto de items de la barra se ocultan mientras dure, igual que la barra
                // principal cambia el ojo de ordenar por el mismo menú (ver
                // MainActivity.setupToolbar).
                launch {
                    viewModel.selectedIds.collect { ids ->
                        adapter.setSelection(ids)
                        val selecting = ids.isNotEmpty()
                        toolbar.menu.findItem(R.id.action_shuffle)?.isVisible = !selecting
                        toolbar.menu.findItem(R.id.action_sort_artist_songs)?.isVisible =
                            !selecting && viewModel.currentKind == DetailKind.ARTIST
                        toolbar.menu.findItem(R.id.action_discography)?.isVisible =
                            !selecting && viewModel.currentKind == DetailKind.ARTIST &&
                            viewModel.currentArtistId != PersonSummary.OTHERS_ARTIST_ID
                        toolbar.menu.findItem(R.id.action_discography_album)?.isVisible =
                            !selecting && viewModel.currentKind == DetailKind.ALBUM
                        toolbar.menu.findItem(R.id.action_play_album)?.isVisible =
                            !selecting && viewModel.currentKind == DetailKind.ALBUM
                        toolbar.title = if (selecting) {
                            resources.getQuantityString(R.plurals.song_selection_count, ids.size, ids.size)
                        } else {
                            viewModel.header.value?.title
                        }
                    }
                }
            }
        }
    }

    private fun bindHeader(
        header: DetailHeader,
        toolbar: MaterialToolbar,
        cover: ShapeableImageView,
        infoLines: LinearLayout
    ) {
        toolbar.title = header.title
        cover.load(CoverArt.cover(requireContext(), header.cover), CoverLoader.get(requireContext())) {
            error(R.drawable.cover_placeholder)
        }

        // Las líneas de datos se crean a mano porque cuántas hay depende del tipo de ficha (un
        // álbum tiene año, un artista no) y de qué metadatos existan. Se vacía y se rehace entero:
        // son tres o cuatro vistas, no compensa la complejidad de reutilizarlas.
        infoLines.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        header.lines.forEachIndexed { index, line ->
            val row = inflater.inflate(R.layout.item_detail_info, infoLines, false)
            row.findViewById<ImageView>(R.id.infoIcon).setImageResource(line.icon)
            row.findViewById<TextView>(R.id.infoText).text = line.text
            // Segunda forma de "ir al artista" además del menú de 3 puntos: tocar la línea de
            // artista, que en la cabecera de un álbum siempre es la primera (ver
            // DetailViewModel.toHeader).
            if (index == 0 && viewModel.currentKind == DetailKind.ALBUM) {
                row.setOnClickListener { goToAlbumArtist() }
            }
            infoLines.addView(row)
        }
        // Si el acento ya se había calculado, hay que reteñir las líneas recién creadas.
        applyTextColor(infoLines, DynamicColor.onColor(currentHeaderColor))
    }

    /** Tiñe la cabecera (headerBox + toolbar + líneas de info) con [accent] — el de lo que suena,
     * no el de la carátula de esta ficha (ver el `collect` de `playerViewModel.accentColor` más
     * arriba). */
    private fun applyAccent(
        accent: Int,
        headerBox: View,
        toolbar: MaterialToolbar,
        infoLines: LinearLayout
    ) {
        // El acento a plena intensidad detrás de un texto sería ilegible: para el FONDO se usa su
        // versión apagada y oscura, y el color del texto se elige según lo claro que quede.
        val background = DynamicColor.asBackground(accent)
        val onBackground = DynamicColor.onColor(background)
        currentHeaderColor = background

        headerBox.setBackgroundColor(background)
        toolbar.setTitleTextColor(onBackground)
        toolbar.setNavigationIconTint(onBackground)
        toolbar.menu.findItem(R.id.action_shuffle)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_more_menu)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_sort_artist_songs)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_discography)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_discography_album)?.icon?.setTint(onBackground)
        toolbar.menu.findItem(R.id.action_play_album)?.icon?.setTint(onBackground)
        applyTextColor(infoLines, onBackground)
    }

    private fun applyTextColor(infoLines: LinearLayout, color: Int) {
        for (i in 0 until infoLines.childCount) {
            val row = infoLines.getChildAt(i)
            row.findViewById<ImageView>(R.id.infoIcon)?.setColorFilter(color)
            row.findViewById<TextView>(R.id.infoText)?.setTextColor(color)
        }
    }

    /**
     * Traduce el [DetailKind] de esta ficha (álbum/artista) al [CollectionKind] que
     * [PlayerViewModel] guarda al reproducirla, para que el iPod sepa qué palabra usar al anunciar
     * cuántas canciones quedan en la cola (ver [CollectionKind]).
     */
    private fun collectionKind(): CollectionKind? = when (viewModel.currentKind) {
        DetailKind.ALBUM -> CollectionKind.ALBUM
        DetailKind.ARTIST -> CollectionKind.ARTIST
        null -> null
    }

    /** Igual que en la pestaña «Canciones»: ver `SongsFragment.showAddToPlaylist`. */
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

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewModel.deleteSong(song)
                val filename = File(song.filePath).name
                viewLifecycleOwner.lifecycleScope.launch {
                    PlaylistRepository.get().removeSongFromAll(filename)
                }
            }
            .show()
        // Mismo acento que el resto de la ficha: el de lo que suena (ver el comentario de
        // playerViewModel.accentColor.collect más arriba).
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /** Menú de 3 puntos de la selección múltiple (ver menu_song_selection.xml, el mismo que usa
     *  [com.untar.ultimusic.ui.MainActivity.showSelectionMenu] para la pestaña Canciones): sin "Ir
     *  al...", que no tiene sentido para varias canciones a la vez. */
    private fun showSelectionMenu(anchor: View) {
        val ids = viewModel.selectedIds.value
        val selected = viewModel.tracks.value.filter { it.id in ids }
        if (selected.isEmpty()) return
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_song_selection, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_to_queue -> {
                        viewModel.clearSelection()
                        playerViewModel.addToQueue(selected)
                        true
                    }
                    R.id.action_add_to_playlist -> { showAddToPlaylistForSelection(selected); true }
                    R.id.action_edit_metadata -> { showMetadataEditorForSelection(selected); true }
                    R.id.action_edit_tags -> { showEditTagsForSelection(selected); true }
                    R.id.action_delete_song -> { showDeleteDialogForSelection(selected); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Igual que [showAddToPlaylist] pero para varias canciones a la vez (ver
     *  `MainActivity.showAddToPlaylistForSelection`). */
    private fun showAddToPlaylistForSelection(selected: List<Song>) {
        viewModel.clearSelection()
        if (parentFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filenames = selected.map { File(it.filePath).name }
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(filenames)
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(filenames, names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    private fun showMetadataEditorForSelection(selected: List<Song>) {
        viewModel.clearSelection()
        if (parentFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
            MetadataEditorDialogFragment.newInstance(selected.map { it.id })
                .show(parentFragmentManager, EDITOR_TAG)
        }
    }

    private fun showEditTagsForSelection(selected: List<Song>) {
        viewModel.clearSelection()
        if (parentFragmentManager.findFragmentByTag(SongTagsDialogFragment.TAG) == null) {
            SongTagsDialogFragment.newInstance(selected.map { it.id })
                .show(parentFragmentManager, SongTagsDialogFragment.TAG)
        }
    }

    /** Igual que [showDeleteDialog] pero para varias canciones a la vez (ver
     *  `MainActivity.showDeleteDialogForSelection`). */
    private fun showDeleteDialogForSelection(selected: List<Song>) {
        viewModel.clearSelection()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(resources.getQuantityString(R.plurals.delete_songs_confirm, selected.size, selected.size))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val playlists = PlaylistRepository.get()
                    for (song in selected) {
                        viewModel.deleteSong(song)
                        playlists.removeSongFromAll(File(song.filePath).name)
                    }
                }
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /**
     * "Ir al artista" desde una ficha de álbum: el álbum en sí no guarda un artista con id (solo
     * el nombre ya resuelto para pintar, ver [DetailViewModel.toHeader]), así que se coge el primer
     * artista de la primera canción que lo tenga. La usan tanto el menú de 3 puntos como el toque en
     * la línea de artista de la cabecera (ver [bindHeader]).
     */
    private fun goToAlbumArtist() {
        viewModel.songs.firstNotNullOfOrNull { it.artists.firstOrNull() }
            ?.let { showArtist(this, it.id) }
    }

    /**
     * Discografía de la ficha de ÁLBUM (botón de disco+lupa de la toolbar, ver
     * `menu_library_detail.xml`): mismo diálogo que el de la ficha de artista
     * ([DiscographyDialogFragment]), pero acotado a este único álbum en vez de a todo el catálogo
     * del artista (ver [DiscographyDialogFragment.showForAlbum]). El artista sale igual que en
     * [goToAlbumArtist]: el álbum en sí no guarda uno con id, así que se coge el primero que traiga
     * alguna de sus canciones.
     */
    private fun showAlbumDiscography() {
        val artist = viewModel.songs.firstNotNullOfOrNull { it.artists.firstOrNull() } ?: return
        val albumTitle = viewModel.header.value?.title ?: return
        DiscographyDialogFragment.showForAlbum(this, artist.id, artist.name, albumTitle)
    }

    /**
     * Menú de 3 puntos de la ficha de álbum. Se ancla al propio icono de la toolbar aprovechando que
     * AppCompat le da a cada acción visible ("showAsAction=always") una vista con el id de su
     * MenuItem, así el PopupMenu sale justo debajo del botón que se ha tocado.
     */
    private fun showAlbumMenu(toolbar: MaterialToolbar) {
        val anchor = toolbar.findViewById<View>(R.id.action_more_menu) ?: toolbar
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_album_actions, menu)
            // Solo tiene sentido si alguna canción del álbum trae un artista enlazado (ver el mismo
            // filtro en SongsAdapter para el menú de una canción suelta).
            menu.findItem(R.id.action_go_to_artist)?.isVisible =
                viewModel.songs.any { it.artists.isNotEmpty() }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_album_to_playlist -> { showAddAlbumToPlaylist(); true }
                    R.id.action_add_album_to_queue -> {
                        playerViewModel.addToQueue(viewModel.songs)
                        true
                    }
                    R.id.action_go_to_artist -> { goToAlbumArtist(); true }
                    R.id.action_edit_album -> {
                        val albumId = viewModel.currentAlbumId ?: return@setOnMenuItemClickListener true
                        if (parentFragmentManager.findFragmentByTag(ALBUM_EDITOR_TAG) == null) {
                            AlbumEditorDialogFragment.newInstance(albumId)
                                .show(parentFragmentManager, ALBUM_EDITOR_TAG)
                        }
                        true
                    }
                    R.id.action_delete_album -> { showDeleteAlbumDialog(); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Menú de 3 puntos de la ficha de artista: de momento solo "Renombrar" (ver
     *  menu_artist_actions.xml/showRenameArtistDialog). */
    private fun showArtistMenu(toolbar: MaterialToolbar) {
        val anchor = toolbar.findViewById<View>(R.id.action_more_menu) ?: toolbar
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_artist_actions, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename_artist -> { showRenameArtistDialog(); true }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * Diálogo con un campo de texto para renombrar el artista de esta ficha, precargado con su
     * nombre actual (ver [viewModel.header]). Cambia [ArtistEntity.name][com.untar.ultimusic.data.db.entities.ArtistEntity.name]
     * -una fila propia de Room, no texto suelto por canción- así que se refleja solo en TODA la app
     * (esta ficha incluida, que sigue el mismo id) en cuanto se guarda, sin más que hacer aquí. Mismo
     * patrón de campo de texto que `PlaylistsFragment.showNameDialog`, sin su validación de nombre de
     * archivo (un artista no es un nombre de fichero).
     */
    private fun showRenameArtistDialog() {
        val currentName = viewModel.header.value?.title ?: return
        val accent = playerViewModel.accentColor.value
        val input = EditText(requireContext()).apply {
            setText(currentName)
            setSelection(text.length)
            hint = getString(R.string.artist_name_hint)
            setSingleLine()
            backgroundTintList = AccentTint.underline(requireContext(), accent)
        }
        val padding = (resources.displayMetrics.density * 20).toInt()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_rename)
            .setView(input, padding, padding / 2, padding, 0)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != currentName) viewModel.renameArtist(name)
            }
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = input.text.isNotBlank()
            input.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
            val muted = ContextCompat.getColor(requireContext(), R.color.um_on_surface_muted)
            ok.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(muted, accent)
                )
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ColorStateList.valueOf(accent))
        }
        dialog.show()
    }

    /**
     * "Añadir a lista" para el álbum entero: mismo diálogo de casillas que para una canción
     * suelta (ver [com.untar.ultimusic.ui.songs.SongsFragment.showAddToPlaylist]), pero con TODOS
     * los nombres de archivo del álbum, en el orden de sus números de pista. Una casilla sale
     * marcada cuando esa lista ya contiene el álbum COMPLETO.
     */
    private fun showAddAlbumToPlaylist() {
        if (parentFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filenames = viewModel.songs.map { File(it.filePath).name }
            if (filenames.isEmpty()) return@launch
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(filenames)
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(filenames, names, checked)
                .show(parentFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar TODAS las canciones del álbum del dispositivo. */
    private fun showDeleteAlbumDialog() {
        val title = viewModel.header.value?.title ?: return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.delete_album_confirm), title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteAllSongs()
                    dismiss()
                }
            }
            .show()
        // Mismo acento que el resto de la ficha: el de lo que suena (ver el comentario de
        // playerViewModel.accentColor.collect más arriba).
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_ID = "id"
        private const val TAG = "libraryDetail"
        private const val EDITOR_TAG = "metadataEditor"
        private const val ALBUM_EDITOR_TAG = "albumEditor"

        private fun show(manager: FragmentManager, kind: DetailKind, id: Long) {
            // Guarda anti-duplicado: dos toques rápidos abrirían dos fichas apiladas.
            if (manager.findFragmentByTag(TAG) != null) return
            DetailDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, kind.name)
                    putLong(ARG_ID, id)
                }
            }.show(manager, TAG)
        }

        // childFragmentManager, no parentFragmentManager: cuando `from` es OTRA DetailDialogFragment
        // (navegando de un artista a un álbum suyo, o de un álbum a uno de sus artistas), las dos
        // fichas compartirían el mismo parentFragmentManager y por tanto el mismo TAG, y la guarda
        // anti-duplicado de arriba impediría abrir la segunda (encontraría la primera ya puesta con
        // ese tag). Con childFragmentManager cada ficha abre la siguiente en un manager propio, así
        // que se pueden apilar sin límite y el botón atrás de cada una solo cierra la suya.
        fun showAlbum(from: Fragment, albumId: Long) = show(from.childFragmentManager, DetailKind.ALBUM, albumId)

        fun showArtist(from: Fragment, artistId: Long) =
            show(from.childFragmentManager, DetailKind.ARTIST, artistId)

        // Mismo par de arriba, pero para abrirla desde una Activity en vez de un Fragment: la
        // barra de búsqueda de MainActivity ya no vive dentro de un diálogo (ver
        // com.untar.ultimusic.ui.search.SearchBarController), así que no tiene un
        // parentFragmentManager del que tirar.
        fun showAlbum(from: FragmentActivity, albumId: Long) =
            show(from.supportFragmentManager, DetailKind.ALBUM, albumId)

        fun showArtist(from: FragmentActivity, artistId: Long) =
            show(from.supportFragmentManager, DetailKind.ARTIST, artistId)
    }
}
