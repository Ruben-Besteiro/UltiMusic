package com.untar.ultimusic.ui.library

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.editor.AlbumEditorDialogFragment
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ficha de un álbum, un artista o un productor: cabecera teñida con el acento de lo que suena (el
 * mismo que lleva el resto de la aplicación, en su versión oscura de fondo) y, debajo, sus
 * canciones.
 *
 * Es un [DialogFragment] a pantalla completa por lo mismo que el editor de metadatos y la ventana
 * del iPod: se abre encima de la pantalla principal sin cambiar de Activity. A diferencia de esas
 * otras ventanas (buscador, editores, ajustes, iPod), esta SÍ lleva su propio mini-reproductor
 * abajo (ver [MiniPlayerController]): es la única pantalla a la que se entra a mirar/gestionar
 * canciones sin querer necesariamente controlar la reproducción, así que tiene sentido poder
 * pausarla, reanudarla o abrir el iPod sin volver antes a la pantalla principal.
 *
 * Los tres tipos comparten pantalla; cuál es se pasa como argumento y lo resuelve [DetailViewModel].
 */
class DetailDialogFragment : DialogFragment() {

    private val viewModel: DetailViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

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
            onSongClick = { position ->
                playerViewModel.playCollection(viewModel.songs, position, collectionKind = collectionKind())
            },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, EDITOR_TAG)
                }
            },
            onDeleteSong = { song -> showDeleteDialog(song) },
            onGoToAlbum = { song ->
                song.albums.firstOrNull()?.let { showAlbum(this, it.id) }
            },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let { showPerson(this, PersonKind.ARTIST, it.id) }
            },
            onGoToProducer = { song ->
                song.producers.firstOrNull()?.let { showPerson(this, PersonKind.PRODUCER, it.id) }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(viewModel.tracks.value.getOrNull(position)?.song?.title)
        }

        // Carrusel de álbumes: solo sale en la ficha de un artista/productor (ver
        // DetailViewModel.albums, que en un álbum va siempre vacío).
        val albumsAdapter = DetailAlbumsAdapter(
            onAlbumClick = { album -> showAlbum(this, album.id) }
        )
        recyclerAlbums.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recyclerAlbums.adapter = albumsAdapter

        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_library_detail)
        // El menú de 3 puntos solo tiene sentido en un álbum: sus acciones son "de álbum" (todas
        // las canciones a la vez, en orden de pista; editar SUS metadatos, que son distintos de los
        // de una canción). En artista/productor, ni pista ni un editor de álbum pintarían nada.
        toolbar.menu.findItem(R.id.action_album_menu)?.isVisible = viewModel.currentKind == DetailKind.ALBUM
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_shuffle -> {
                    playerViewModel.shuffleCollection(viewModel.songs, collectionKind = collectionKind())
                    true
                }
                R.id.action_album_menu -> {
                    showAlbumMenu(toolbar)
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Ver el comentario de CoverArt.revision: editar solo la carátula del álbum (o de
                    // una de sus canciones, de la que puede salir la de un artista/productor) no
                    // cambia nada en el DetailHeader que sale de Room si el nombre de archivo se
                    // reutiliza, así que sin esto la cabecera no se enteraría.
                    combine(viewModel.header, CoverArt.revision) { header, _ -> header }.collect { header ->
                        if (header != null) bindHeader(header, toolbar, cover, infoLines)
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
        toolbar.menu.findItem(R.id.action_album_menu)?.icon?.setTint(onBackground)
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
     * Traduce el [DetailKind] de esta ficha (álbum/artista/productor) al [CollectionKind] que
     * [PlayerViewModel] guarda al reproducirla, para que el iPod sepa qué palabra usar al anunciar
     * cuántas canciones quedan en la cola (ver [CollectionKind]).
     */
    private fun collectionKind(): CollectionKind? = when (viewModel.currentKind) {
        DetailKind.ALBUM -> CollectionKind.ALBUM
        DetailKind.ARTIST -> CollectionKind.ARTIST
        DetailKind.PRODUCER -> CollectionKind.PRODUCER
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

    /**
     * "Ir al artista" desde una ficha de álbum: el álbum en sí no guarda un artista con id (solo
     * el nombre ya resuelto para pintar, ver [DetailViewModel.toHeader]), así que se coge el primer
     * artista de la primera canción que lo tenga. La usan tanto el menú de 3 puntos como el toque en
     * la línea de artista de la cabecera (ver [bindHeader]).
     */
    private fun goToAlbumArtist() {
        viewModel.songs.firstNotNullOfOrNull { it.artists.firstOrNull() }
            ?.let { showPerson(this, PersonKind.ARTIST, it.id) }
    }

    /**
     * Menú de 3 puntos de la ficha de álbum. Se ancla al propio icono de la toolbar aprovechando que
     * AppCompat le da a cada acción visible ("showAsAction=always") una vista con el id de su
     * MenuItem, así el PopupMenu sale justo debajo del botón que se ha tocado.
     */
    private fun showAlbumMenu(toolbar: MaterialToolbar) {
        val anchor = toolbar.findViewById<View>(R.id.action_album_menu) ?: toolbar
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

        fun showPerson(from: Fragment, kind: PersonKind, id: Long) = show(
            from.childFragmentManager,
            when (kind) {
                PersonKind.ARTIST -> DetailKind.ARTIST
                PersonKind.PRODUCER -> DetailKind.PRODUCER
            },
            id
        )

        // Mismo par de arriba, pero para abrirla desde una Activity en vez de un Fragment: la
        // barra de búsqueda de MainActivity ya no vive dentro de un diálogo (ver
        // com.untar.ultimusic.ui.search.SearchBarController), así que no tiene un
        // parentFragmentManager del que tirar.
        fun showAlbum(from: FragmentActivity, albumId: Long) =
            show(from.supportFragmentManager, DetailKind.ALBUM, albumId)

        fun showPerson(from: FragmentActivity, kind: PersonKind, id: Long) = show(
            from.supportFragmentManager,
            when (kind) {
                PersonKind.ARTIST -> DetailKind.ARTIST
                PersonKind.PRODUCER -> DetailKind.PRODUCER
            },
            id
        )
    }
}
