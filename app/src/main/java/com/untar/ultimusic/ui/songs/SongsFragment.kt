package com.untar.ultimusic.ui.songs

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.ui.settings.SettingsViewModel
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.SafStorage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.attachScrollbarDrag
import com.untar.ultimusic.ui.common.highlightNowPlaying
import com.untar.ultimusic.ui.common.attachSwipeToQueue
import com.untar.ultimusic.ui.common.sectionLetter
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.SongTagsDialogFragment
import com.untar.ultimusic.ui.library.TagsViewModel
import com.untar.ultimusic.ui.SongsViewModel
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.joinNonBlank
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Primer fragmento: lista de canciones. */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    private val songsViewModel: SongsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()
    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    /** Botón "Elegir carpeta de música" del aviso de "no hay canciones": mismo selector y misma
     *  validación que Ajustes > Carpetas de la fonoteca. */
    private val addMusicFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            when (settingsViewModel.tryAddLibraryRoot(uri)) {
                SettingsViewModel.AddLibraryRootResult.SUCCESS -> Unit
                SettingsViewModel.AddLibraryRootResult.ALREADY_COVERED -> Toast.makeText(
                    requireContext(), R.string.settings_library_root_error_nested, Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val addFolderButton = view.findViewById<MaterialButton>(R.id.btnAddMusicFolder)
        val loadingSpinner = view.findViewById<ProgressBar>(R.id.loadingSpinner)

        // Con null Android abriría el selector en la última carpeta usada (`UltiMusic`): se pide que
        // empiece en la raíz del almacenamiento interno, igual que Ajustes.
        addFolderButton.setOnClickListener {
            addMusicFolderLauncher.launch(SafStorage.musicPickerInitialUri())
        }
        settingsViewModel.libraryRoots   // arranca ya la carga de raíces (ver SettingsViewModel)

        /** Aviso de "no hay canciones" con su botón, o (con [text] distinto) solo el texto. */
        fun showEmpty(text: CharSequence?, withButton: Boolean) {
            if (text == null) {
                emptyView.visibility = View.GONE
            } else {
                emptyView.text = text
                emptyView.visibility = View.VISIBLE
            }
            addFolderButton.visibility = if (withButton) View.VISIBLE else View.GONE
        }

        val adapter = SongsAdapter(
            // Con selección activa, tocar una fila la marca/desmarca en vez de reproducir; si no,
            // se reproduce como siempre (ver SongsViewModel.selectedIds).
            onSongClick = { song ->
                if (songsViewModel.selectedIds.value.isEmpty()) playerViewModel.play(song)
                else songsViewModel.toggleSelection(song.id)
            },
            // Mantener pulsada una canción empieza la selección con ella ya marcada. Si ya había
            // una selección en marcha, se limita a marcar/desmarcar esta también.
            onSongLongClick = { song ->
                if (songsViewModel.selectedIds.value.isEmpty()) songsViewModel.startSelection(song.id)
                else songsViewModel.toggleSelection(song.id)
            },
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = { song -> showAddToPlaylist(song) },
            onEditMetadata = { song ->
                if (parentFragmentManager.findFragmentByTag("metadataEditor") == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(parentFragmentManager, "metadataEditor")
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
                song.album?.let { DetailDialogFragment.showAlbum(this, it.id) }
            },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let { DetailDialogFragment.showArtist(this, it.id) }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter

        // -1 porque la posición 0 del RecyclerView es la cabecera de resumen (ver SongsAdapter),
        // no una canción.
        val scrollbar = recycler.attachScrollbarDrag { position ->
            sectionLetter(adapter.songAt(position)?.title)
        }

        scrollbar.highlightNowPlaying(viewLifecycleOwner, playerViewModel) { pos, song, kind, name ->
            adapter.songAt(pos)?.id == song.id
        }

        // Arrastrar una fila hacia la derecha la añade a la cola, como el propio "Añadir a cola" del
        // menú de 3 puntos (ver SwipeToQueue.kt); la cabecera (posición 0) no responde.
        attachSwipeToQueue(
            recycler,
            accentColor = { playerViewModel.accentTarget.value },
            canSwipe = { position -> position > 0 }
        ) { position ->
            adapter.songAt(position)?.let { playerViewModel.addToQueue(it) }
        }

        /** Pintamos las canciones en la pantalla **/
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Editar SOLO la carátula de una canción -reutilizando su nombre de archivo, el
                    // caso normal- no cambia nada en el Song que sale de Room (ver
                    // CoverArt.revision), así que el diff del adaptador no lo vería: se repinta lo
                    // visible aparte, sin rehacer la lista.
                    CoverArt.revision.collect { adapter.refreshCovers() }
                }
                launch {
                    songsViewModel.songs.collect { list ->
                        adapter.submit(list)
                        adapter.setSummary(
                            joinNonBlank(
                                resources.getQuantityString(R.plurals.song_count, list.size, list.size),
                                TimeFormat.hhmmss(list.sumOf { it.duration })
                            )
                        )
                        // Antes ponía R.string.nothing_playing (el texto del reproductor vacío): si la
                        // lista llegaba vacía DESPUÉS de acabar el escaneo, pisaba al aviso bueno.
                        if (list.isEmpty() && !songsViewModel.loading.value) {
                            showEmpty(noSongsText(emptyView), withButton = true)
                        } else if (list.isNotEmpty()) {
                            showEmpty(null, withButton = false)
                        }
                    }
                }
                launch {
                    // `loading` y `progress` van COMBINADOS, no en dos collect por separado: cada
                    // uno es un StateFlow independiente (ver SongsViewModel), y leer el `.value` de
                    // uno desde dentro del collect del otro no garantiza que ya esté al día -en un
                    // dispositivo real se veía el texto "Actualizando..." sin el porcentaje, pisado
                    // por esa carrera-. Combinados, cada emisión trae los dos valores ya
                    // sincronizados entre sí.
                    combine(songsViewModel.loading, songsViewModel.progress) { loading, percent ->
                        loading to percent
                    }.collect { (loading, percent) ->
                        if (loading) {
                            showEmpty(
                                String.format(
                                    requireContext().getString(R.string.updating_database_percent),
                                    percent
                                ),
                                withButton = false
                            )
                        } else if (songsViewModel.songs.value.isEmpty()) {
                            showEmpty(noSongsText(emptyView), withButton = true)
                        } else {
                            // La reconciliación puede terminar sin cambiar ninguna fila (biblioteca ya al
                            // día): el flujo `songs` no reemite y el otro collector nunca se dispara, así
                            // que hay que ocultar el aviso aquí también o se queda clavado en pantalla.
                            showEmpty(null, withButton = false)
                        }
                    }
                }
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        scrollbar.setAccentColor(accent)
                        loadingSpinner.indeterminateTintList = ColorStateList.valueOf(accent)
                        addFolderButton.backgroundTintList = ColorStateList.valueOf(accent)
                        addFolderButton.setTextColor(DynamicColor.onColor(accent))
                        adapter.setAccentColor(accent)
                    }
                }
                launch {
                    songsViewModel.selectedIds.collect { ids -> adapter.setSelection(ids) }
                }
                launch {
                    tagsViewModel.songTagsById.collect { byId -> adapter.setTags(byId) }
                }
            }
        }
    }

    /**
     * Carga (en una corrutina, porque toca disco) qué listas existen y en cuáles ya está la
     * canción, y abre el diálogo de casillas de pertenencia con esos datos.
     */
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
                songsViewModel.delete(song)
                playlistsViewModel.forgetSong(File(song.filePath).name)
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
    }

    /**
     * Texto de "no hay canciones" con el `%1$s` del string cambiado por el icono ic_add (el botón
     * de + de la barra superior), del tamaño de la letra y del mismo color que el texto.
     */
    private fun noSongsText(textView: TextView): CharSequence {
        val placeholder = "%1\$s"
        val raw = getString(R.string.no_songs).replace("%%", "%")
        val start = raw.indexOf(placeholder)
        if (start < 0) return raw
        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_add)?.mutate()
            ?: return raw.replace(placeholder, "+")
        val size = (textView.textSize * 1.2f).toInt()
        icon.setBounds(0, 0, size, size)
        icon.setTint(textView.currentTextColor)
        val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DynamicDrawableSpan.ALIGN_CENTER
        } else {
            DynamicDrawableSpan.ALIGN_BOTTOM
        }
        return SpannableStringBuilder(raw).apply {
            setSpan(ImageSpan(icon, align), start, start + placeholder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
