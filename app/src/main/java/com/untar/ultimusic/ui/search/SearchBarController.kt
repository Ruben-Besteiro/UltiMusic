package com.untar.ultimusic.ui.search

import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListPopupWindow
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.SongTagsDialogFragment
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.SqlConsole
import com.untar.ultimusic.util.SqlPlan
import kotlinx.coroutines.launch
import java.io.File

private const val ANIM_DURATION_MS = 180L
private const val EDITOR_TAG = "metadataEditor"

/**
 * Convierte la barra de búsqueda de [com.untar.ultimusic.ui.MainActivity] en un buscador de verdad,
 * sin pasar por un diálogo aparte: antes tocar la lupa abría un `SearchDialogFragment` a pantalla
 * completa, con su propia caja de texto; ahora la caja ES la barra de la toolbar, y lo único que
 * cambia al "abrir" el buscador es qué hay debajo de ella.
 *
 * Al tocar la barra ([expand]):
 * - El engranaje, la ayuda y "más opciones" desaparecen (`GONE`); como la barra es el único hijo
 *   con `layout_weight` de esa fila, se estira sola para ocupar el hueco que dejan libre. El icono
 *   de la lupa pasa a ser una flecha "atrás" que colapsa el buscador.
 * - `browseContent` (pestañas + reproductor) se cambia por `searchResultsContainer` (la lista de
 *   resultados), igual que hacía el diálogo pero sin abrir una ventana nueva.
 * - El botón "atrás" del sistema queda enganchado para colapsar en vez de cerrar la Activity
 *   mientras el buscador esté abierto (ver [backCallback]).
 *
 * Las dos transiciones usan [TransitionManager] (de plataforma, no hace falta la librería
 * `androidx.transition`) para que el cambio de anchos y visibilidades se anime solo, en vez de dar
 * un salto brusco.
 *
 * Todo el filtrado real lo sigue haciendo [SearchViewModel]; esta clase solo pinta lo que él calcula
 * y decide qué pasa al pulsar cada resultado, exactamente igual que hacía el diálogo anterior
 * (canción: suena con las demás encontradas detrás; álbum/artista: abre su ficha encima).
 */
class SearchBarController(
    private val activity: FragmentActivity,
    private val topBar: ViewGroup,
    private val btnSettings: View,
    private val btnHelp: View,
    private val btnSort: View,
    private val searchLeadingIcon: ImageView,
    private val searchInput: EditText,
    private val btnSearchClear: View,
    private val btnSearchSql: TextView,
    private val searchBar: View,
    private val contentArea: ViewGroup,
    private val browseContent: View,
    private val searchResultsContainer: View,
    private val recycler: RecyclerView,
    private val emptyView: TextView,
    private val viewModel: SearchViewModel,
    private val playerViewModel: PlayerViewModel,
    private val lifecycleOwner: LifecycleOwner
) {
    private var expanded = false

    private lateinit var adapter: SearchAdapter

    /** Modo SQL: la caja es una consola contra la BD en vez de un buscador (ver [setSqlMode]). */
    private var sqlMode = false

    /** Último texto ya procesado al perder el foco, para no repetir (ni volver a preguntar) si no cambió. */
    private var lastHandledSql: String? = null

    private var suggestions = SqlConsole.Suggestions(0, emptyList())

    private val suggestionPopup: ListPopupWindow by lazy {
        ListPopupWindow(activity).apply {
            anchorView = searchBar
            isModal = false // no debe robarle el foco (ni el teclado) a la caja
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.um_surface)))
            setOnItemClickListener { _, _, position, _ -> applySuggestion(position) }
        }
    }

    /** Habilitado solo mientras el buscador está abierto (ver [expand] / [collapse]). */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapse()
    }

    fun bind() {
        activity.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)

        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = suggestionPopup.dismiss()
        })

        adapter = SearchAdapter(
            onSongClick = ::playFromResults,
            onAddToQueue = { song -> playerViewModel.addToQueue(song) },
            onAddToPlaylist = ::showAddToPlaylist,
            onEditMetadata = { song ->
                if (activity.supportFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
                    MetadataEditorDialogFragment.newInstance(song.id)
                        .show(activity.supportFragmentManager, EDITOR_TAG)
                }
            },
            onEditTags = { song ->
                if (activity.supportFragmentManager.findFragmentByTag(SongTagsDialogFragment.TAG) == null) {
                    SongTagsDialogFragment.newInstance(song.id)
                        .show(activity.supportFragmentManager, SongTagsDialogFragment.TAG)
                }
            },
            onDeleteSong = ::showDeleteDialog,
            onGoToAlbum = { song ->
                song.album?.let {
                    hideKeyboard()
                    DetailDialogFragment.showAlbum(activity, it.id)
                }
            },
            onGoToArtist = { song ->
                song.artists.firstOrNull()?.let {
                    hideKeyboard()
                    DetailDialogFragment.showArtist(activity, it.id)
                }
            },
            onAlbumClick = { album ->
                hideKeyboard()
                DetailDialogFragment.showAlbum(activity, album.id)
            },
            onPersonClick = { person ->
                hideKeyboard()
                DetailDialogFragment.showArtist(activity, person.id)
            }
        )
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        // Misma barra de scroll arrastrable que las pestañas, sin la burbuja de la letra: los
        // resultados no van en orden alfabético sino agrupados por secciones (ver ScrollbarDrag).
        val scrollbar = recycler.attachScrollbarDrag()
        scrollbar.highlightNowPlaying(activity, playerViewModel) { pos, song, _, _ ->
            when (val row = adapter.resultAt(pos)) {
                is SearchResult.SongRow -> row.song.id == song.id
                is SearchResult.AlbumRow -> song.albums.any { it.album.id == row.album.id }
                is SearchResult.PersonRow -> song.artists.any { it.id == row.person.id }
                else -> false
            }
        }

        // Arrastrar una fila de canción hacia la derecha la añade a la cola, como en la pestaña
        // Canciones (ver SwipeToQueue.kt); las filas de cabecera/álbum/persona no responden (ver
        // SearchAdapter.songAt).
        attachSwipeToQueue(
            recycler,
            accentColor = { playerViewModel.accentTarget.value },
            canSwipe = { position -> adapter.songAt(position) != null }
        ) { position -> adapter.songAt(position)?.let { playerViewModel.addToQueue(it) } }

        // Tocar cualquier punto de la píldora (o la propia caja) expande el buscador y le da el
        // foco; la flecha/lupa de la izquierda, en cambio, colapsa cuando ya está expandido.
        searchInput.setOnClickListener { expand() }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expand()
                lastHandledSql = null
                if (sqlMode) updateSuggestions()
            } else if (sqlMode) {
                // Perder el foco es el momento en que se ejecuta el comando.
                suggestionPopup.dismiss()
                executeSql()
            }
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (sqlMode && actionId == EditorInfo.IME_ACTION_SEARCH) {
                suggestionPopup.dismiss()
                hideKeyboard()
                executeSql()
                true
            } else false
        }
        searchLeadingIcon.setOnClickListener { if (expanded) collapse() else expand() }
        btnSearchSql.setOnClickListener { setSqlMode(!sqlMode) }

        searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            if (sqlMode) updateSuggestions() else viewModel.setQuery(query)
            btnSearchClear.isVisible = query.isNotEmpty()
        }
        btnSearchClear.setOnClickListener {
            searchInput.setText("")
            searchInput.requestFocus()
        }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.results.collect { results ->
                        if (sqlMode) return@collect
                        adapter.submit(results)
                        showEmptyMessage(results.isEmpty())
                    }
                }
                launch { viewModel.sqlOutcome.collect { if (sqlMode) renderSql() } }
                launch { viewModel.sqlSchema.collect { if (sqlMode) updateSuggestions() } }
                launch {
                    // Ver el comentario de CoverArt.revision: los resultados salen de las mismas
                    // listas de Canciones/Álbumes/Artistas, así que sufren la misma laguna si solo
                    // cambia una carátula reutilizando su nombre de archivo. Va aparte de los
                    // resultados porque el adaptador ahora compara listas (DiffUtil): reenviarle la
                    // misma lista no repintaría nada.
                    CoverArt.revision.collect { adapter.refreshCovers() }
                }
                // Los títulos de sección y la barra de scroll van con el color de la canción que
                // suena, como en las pestañas.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        adapter.setAccent(accent)
                        scrollbar.setAccentColor(accent)
                        updateSqlToggleColor()
                    }
                }
            }
        }
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        backCallback.isEnabled = true

        TransitionManager.beginDelayedTransition(topBar, AutoTransition().setDuration(ANIM_DURATION_MS))
        btnSettings.visibility = View.GONE
        btnHelp.visibility = View.GONE
        btnSort.visibility = View.GONE
        searchLeadingIcon.setImageResource(R.drawable.ic_arrow_back)
        searchLeadingIcon.contentDescription = activity.getString(R.string.search_back)
        btnSearchSql.visibility = View.VISIBLE
        updateSqlToggleColor()

        TransitionManager.beginDelayedTransition(contentArea, AutoTransition().setDuration(ANIM_DURATION_MS))
        browseContent.visibility = View.GONE
        searchResultsContainer.visibility = View.VISIBLE

        showEmptyMessage(viewModel.results.value.isEmpty())
        searchInput.post { showKeyboard() }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        backCallback.isEnabled = false

        // Antes de soltar el foco: si no, el modo SQL intentaría ejecutar lo que hubiera escrito.
        setSqlMode(false)
        btnSearchSql.visibility = View.GONE

        hideKeyboard()
        searchInput.clearFocus()
        searchInput.setText("") // dispara doAfterTextChanged -> viewModel.setQuery("")

        TransitionManager.beginDelayedTransition(topBar, AutoTransition().setDuration(ANIM_DURATION_MS))
        btnSettings.visibility = View.VISIBLE
        btnHelp.visibility = View.VISIBLE
        btnSort.visibility = View.VISIBLE
        searchLeadingIcon.setImageResource(R.drawable.ic_search)
        searchLeadingIcon.contentDescription = activity.getString(R.string.action_search)

        TransitionManager.beginDelayedTransition(contentArea, AutoTransition().setDuration(ANIM_DURATION_MS))
        searchResultsContainer.visibility = View.GONE
        browseContent.visibility = View.VISIBLE
    }

    /** Mensaje del hueco central: cambia según si aún no se ha escrito nada o si no hay resultados. */
    private fun showEmptyMessage(noResults: Boolean) {
        emptyView.isVisible = noResults
        if (!noResults) return
        emptyView.setTextColor(activity.getColor(R.color.um_on_surface_muted))

        val query = viewModel.query.value.trim()
        emptyView.text =
            if (query.isEmpty()) activity.getString(R.string.search_prompt)
            else activity.getString(R.string.search_no_results, query)
    }

    // ------------------------------------------------------------------ modo SQL

    private fun setSqlMode(on: Boolean) {
        if (sqlMode == on) return
        sqlMode = on
        suggestionPopup.dismiss()
        lastHandledSql = null
        viewModel.clearSql()
        viewModel.setQuery("")
        searchInput.setText("")
        searchInput.setHint(if (on) R.string.sql_hint else R.string.search_hint)
        updateSqlToggleColor()
        if (on) viewModel.loadSqlSchema()
        if (expanded) {
            if (on) renderSql() else {
                adapter.submit(viewModel.results.value)
                showEmptyMessage(viewModel.results.value.isEmpty())
            }
        }
        if (on) updateSuggestions()
    }

    /** Activo = color dinámico; apagado = gris apagado. */
    private fun updateSqlToggleColor() {
        btnSearchSql.setTextColor(
            if (sqlMode) playerViewModel.accentTarget.value else activity.getColor(R.color.um_on_surface_muted)
        )
    }

    /** Pinta el resultado del último comando (o la ayuda si todavía no hay ninguno). */
    private fun renderSql() {
        val muted = activity.getColor(R.color.um_on_surface_muted)
        when (val outcome = viewModel.sqlOutcome.value) {
            is SqlOutcome.Rows -> {
                adapter.submit(outcome.rows)
                emptyView.isVisible = false
            }
            is SqlOutcome.Done -> showSqlText(outcome.message, muted)
            is SqlOutcome.Error -> showSqlText(outcome.message, activity.getColor(R.color.um_error))
            null -> showSqlText(activity.getString(R.string.sql_help), muted)
        }
    }

    private fun showSqlText(text: String, color: Int) {
        adapter.submit(emptyList())
        emptyView.text = text
        emptyView.setTextColor(color)
        emptyView.isVisible = true
    }

    /** Clasifica el comando escrito y lo ejecuta, pide confirmación o muestra por qué no vale. */
    private fun executeSql() {
        val sql = searchInput.text.toString()
        if (sql.isBlank() || sql == lastHandledSql) return
        lastHandledSql = sql

        when (val plan = SqlConsole.plan(sql)) {
            is SqlPlan.Invalid -> viewModel.showSqlError(
                if (plan.arg != null) activity.getString(plan.messageRes, plan.arg) else activity.getString(plan.messageRes)
            )
            SqlPlan.ReadOnly -> viewModel.runSql(sql)
            is SqlPlan.Write -> {
                val message = activity.getString(plan.explanation) + "\n\n" +
                    activity.getString(R.string.sql_confirm_command) + "\n" + sql.trim()
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.sql_confirm_title)
                    .setMessage(message)
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.sql_confirm_run) { _, _ -> viewModel.runSql(sql) }
                    .show()
                AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
            }
        }
    }

    /** Recalcula y enseña (o esconde) el desplegable según lo escrito hasta el cursor. */
    private fun updateSuggestions() {
        if (!sqlMode || !expanded || !searchInput.hasFocus()) {
            suggestionPopup.dismiss()
            return
        }
        val text = searchInput.text.toString()
        val cursor = searchInput.selectionStart.let { if (it < 0) text.length else it.coerceAtMost(text.length) }
        suggestions = SqlConsole.suggest(text.substring(0, cursor), viewModel.sqlSchema.value)
        if (suggestions.options.isEmpty()) {
            suggestionPopup.dismiss()
            return
        }
        suggestionPopup.setAdapter(SuggestionAdapter(suggestions.options))
        suggestionPopup.show()
    }

    private fun applySuggestion(position: Int) {
        val option = suggestions.options.getOrNull(position) ?: return
        val editable = searchInput.text ?: return
        val cursor = searchInput.selectionStart.let { if (it < 0) editable.length else it }
        val start = suggestions.replaceStart.coerceAtMost(cursor)
        editable.replace(start, cursor, option.text + if (option.text.endsWith("(")) "" else " ")
    }

    private inner class SuggestionAdapter(private val options: List<SqlConsole.Suggestion>) : BaseAdapter() {
        override fun getCount() = options.size
        override fun getItem(position: Int) = options[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_sql_suggestion, parent, false)
            val option = options[position]
            view.findViewById<TextView>(R.id.suggestionText).text = option.text
            view.findViewById<TextView>(R.id.suggestionHint).apply {
                text = option.hint.orEmpty()
                isVisible = option.hint != null
            }
            return view
        }
    }

    /**
     * Reproduce la canción pulsada dejando el resto de canciones encontradas detrás, en el mismo
     * orden en que se ven (igual que un álbum: `playCollection`, no `play`).
     */
    private fun playFromResults(song: Song) {
        val songs = viewModel.matchedSongs
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) playerViewModel.playCollection(songs, index)
    }

    /** Igual que en la pestaña «Canciones»: ver `SongsFragment.showAddToPlaylist`. */
    private fun showAddToPlaylist(song: Song) {
        if (activity.supportFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        lifecycleOwner.lifecycleScope.launch {
            val filename = File(song.filePath).name
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(listOf(filename))
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(listOf(filename), names, checked)
                .show(activity.supportFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad el archivo del dispositivo. */
    private fun showDeleteDialog(song: Song) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(activity.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewModel.deleteSong(song)
                val filename = File(song.filePath).name
                lifecycleOwner.lifecycleScope.launch {
                    PlaylistRepository.get().removeSongFromAll(filename)
                }
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
    }

    /** Recoge el teclado. Hace falta antes de abrir una ficha encima (álbum/artista). */
    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showKeyboard() {
        searchInput.requestFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }
}
