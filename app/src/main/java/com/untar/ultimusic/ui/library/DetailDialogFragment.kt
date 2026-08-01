package com.untar.ultimusic.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
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
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ficha de un álbum, un artista o un productor: cabecera teñida con el color de su carátula y,
 * debajo, sus canciones.
 *
 * Es un [DialogFragment] a pantalla completa por lo mismo que el editor de metadatos y la ventana
 * del iPod: se abre encima de la pantalla principal sin cambiar de Activity, así que el
 * mini-reproductor de abajo sigue visible y funcionando.
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
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerDetailSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // El hueco de arriba se lo come la toolbar (que está DENTRO de la cabecera de color),
            // así que ese color llega hasta detrás de la hora y la batería.
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        val adapter = DetailSongsAdapter(
            onSongClick = { position -> playerViewModel.playCollection(viewModel.songs, position) },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, EDITOR_TAG)
                }
            },
            onDeleteSong = { song -> showDeleteDialog(song) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(viewModel.tracks.value.getOrNull(position)?.song?.title)
        }

        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_library_detail)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_shuffle) {
                playerViewModel.shuffleCollection(viewModel.songs)
                true
            } else false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.header.collect { header ->
                        if (header != null) bindHeader(header, toolbar, cover, infoLines)
                    }
                }
                launch {
                    viewModel.tracks.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.accentColor.collect { accent ->
                        applyAccent(accent, headerBox, toolbar, infoLines)
                        // Aquí el acento NO es el de lo que suena sino el de la propia ficha (sale
                        // de su carátula), que es el que lleva toda esta ventana: la barra de
                        // scroll va con el resto para no desentonar dentro del diálogo.
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
        for (line in header.lines) {
            val row = inflater.inflate(R.layout.item_detail_info, infoLines, false)
            row.findViewById<ImageView>(R.id.infoIcon).setImageResource(line.icon)
            row.findViewById<TextView>(R.id.infoText).text = line.text
            infoLines.addView(row)
        }
        // Si el acento ya se había calculado, hay que reteñir las líneas recién creadas.
        applyTextColor(infoLines, DynamicColor.onColor(currentHeaderColor))
    }

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
        applyTextColor(infoLines, onBackground)
    }

    private fun applyTextColor(infoLines: LinearLayout, color: Int) {
        for (i in 0 until infoLines.childCount) {
            val row = infoLines.getChildAt(i)
            row.findViewById<ImageView>(R.id.infoIcon)?.setColorFilter(color)
            row.findViewById<TextView>(R.id.infoText)?.setTextColor(color)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(getString(R.string.delete_song_confirm, song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewModel.deleteSong(song)
                val filename = File(song.filePath).name
                viewLifecycleOwner.lifecycleScope.launch {
                    PlaylistRepository.get().removeSongFromAll(filename)
                }
            }
            .show()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_ID = "id"
        private const val TAG = "libraryDetail"
        private const val EDITOR_TAG = "metadataEditor"

        private fun show(from: Fragment, kind: DetailKind, id: Long) {
            val manager = from.parentFragmentManager
            // Guarda anti-duplicado: dos toques rápidos abrirían dos fichas apiladas.
            if (manager.findFragmentByTag(TAG) != null) return
            DetailDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, kind.name)
                    putLong(ARG_ID, id)
                }
            }.show(manager, TAG)
        }

        fun showAlbum(from: Fragment, albumId: Long) = show(from, DetailKind.ALBUM, albumId)

        fun showPerson(from: Fragment, kind: PersonKind, id: Long) = show(
            from,
            when (kind) {
                PersonKind.ARTIST -> DetailKind.ARTIST
                PersonKind.PRODUCER -> DetailKind.PRODUCER
            },
            id
        )
    }
}
