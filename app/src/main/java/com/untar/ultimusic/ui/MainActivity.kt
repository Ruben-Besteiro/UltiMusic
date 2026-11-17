package com.untar.ultimusic.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.data.scan.ScannedSong
import com.untar.ultimusic.model.Album
import com.untar.ultimusic.model.Artist
import com.untar.ultimusic.model.Producer
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.SongAlbumEntry
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.ui.common.BlurredBackground
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.SongTagsDialogFragment
import com.untar.ultimusic.ui.library.TagsViewModel
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.ui.search.SearchBarController
import com.untar.ultimusic.ui.search.SearchViewModel
import com.untar.ultimusic.recount.RecountReminder
import com.untar.ultimusic.ui.recount.RecountDialogFragment
import com.untar.ultimusic.ui.settings.SettingsDialogFragment
import com.untar.ultimusic.ui.preview.PreviewSearchDialogFragment
import com.untar.ultimusic.ui.sort.SortDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Clave del Bundle con la pestaña actual, para restaurarla al recrear la Activity (ver setupPager). */
private const val STATE_SELECTED_TAB = "selected_tab"

class MainActivity : AppCompatActivity() {

    // La cola de reproducción vive en PlayerViewModel (ámbito de actividad), compartida por el
    // mini-reproductor, SongsFragment y la ventana del iPod. Al pinchar una canción se inserta en
    // la posición 0 y la reproducción siempre mira la posición actual de la cola.

    private val songsViewModel: SongsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    // Ámbito de actividad: antes vivía en SearchDialogFragment (un diálogo aparte), pero al buscar
    // ahora dentro de la propia toolbar (ver SearchBarController) el ViewModel se mueve aquí con
    // ella. Se olvida solo si la Activity muere de verdad, no cada vez que se colapsa la búsqueda.
    private val searchViewModel: SearchViewModel by viewModels()

    // Ámbito de actividad, así que es la MISMA instancia que obtienen los fragmentos con
    // activityViewModels(). Aquí solo se usa para sacar de las listas las canciones cuyo archivo
    // ha desaparecido; al pasar por el ViewModel, su `tick` se encarga de repintar las pestañas.
    private val playlistsViewModel: PlaylistsViewModel by viewModels()

    // Ámbito de actividad, MISMA instancia que obtienen los fragmentos/diálogos con
    // activityViewModels(). Se declara aquí (y no en TagsFragment, donde vivía antes) solo para
    // conectarle el bind de abajo antes de que exista ningún fragmento — ver el comentario de
    // onCreate.
    private val tagsViewModel: TagsViewModel by viewModels()

    private val tabTitles: List<String> by lazy {
        listOf(
            getString(R.string.tab_songs),
            getString(R.string.tab_albums),
            getString(R.string.tab_artists),
            getString(R.string.tab_genres),
            getString(R.string.tab_tags),
            getString(R.string.tab_playlists)
        )
    }

    /** Un icono por pestaña, en el mismo orden que [tabTitles] y que [MainPagerAdapter]. Géneros
     *  estrena ic_radio (antes usaba ic_genre); Etiquetas hereda ese icono viejo de "etiqueta de
     *  ropa", que le pega más temáticamente. */
    private val tabIcons: List<Int> = listOf(
        R.drawable.ic_music_note,
        R.drawable.ic_album,
        R.drawable.ic_artist,
        R.drawable.ic_radio,
        R.drawable.ic_genre,
        R.drawable.ic_playlist
    )

    /** En caso de ser true, mostramos el "por favor concede el permiso de almacenamiento" **/
    private var permissionDialogPending = true

    /** Gris de las pestañas no seleccionadas de la barra de navegación (ver applyTabAppearance). */
    private val mutedTabColor: Int by lazy { ContextCompat.getColor(this, R.color.um_on_surface_muted) }

    /** Ancho del icono de cada pestaña (ver item_nav_tab.xml); ver redistributeTabPadding. */
    private val tabIconWidthDp = 24f

    /** Padding horizontal "de descanso" de cada pestaña y el mínimo al que se le puede bajar para
     *  hacerle sitio al nombre de la seleccionada; ver redistributeTabPadding. */
    private val tabPaddingRestDp = 16f
    private val tabPaddingMinDp = 4f

    /** Lo máximo que puede ensancharse la pestaña seleccionada respecto a su ancho en reposo
     *  (icono + padding) con el nombre en una línea; si haría falta más, se parte en dos. */
    private val tabMaxGrowthFactor = 1.5f

    /**
     * Último acento recibido de [PlayerViewModel.accentColor], usado para pintar la pestaña actual
     * tanto al construirla (setupPager) como al cambiar de canción (setupDynamicColor). Empieza
     * igual que [com.untar.ultimusic.util.DynamicColor.DEFAULT] (el amarillo de siempre, mientras
     * no suena nada) sin llamar a `ContextCompat` aquí porque este campo se inicializa antes de que
     * la Activity tenga Context (a diferencia de [mutedTabColor], que usa `by lazy`).
     */
    private var currentAccentColor: Int = 0xFFFFD000.toInt()

    /** Scope de proceso para tareas best-effort que deben sobrevivir a la Activity (exportar la BD). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.untar.ultimusic.ui.common.DialogOpenLock.install(supportFragmentManager)
        setContentView(R.layout.activity_main)
        com.untar.ultimusic.ui.common.DialogOpenLock.wrap(window)
        // Fondo con desenfoque suave donde no hay tarjetas encima (las tarjetas usan uno más fuerte).
        lifecycleScope.launch {
            val soft = withContext(Dispatchers.Default) { BlurredBackground.lightBackground(applicationContext) }
            findViewById<android.widget.ImageView>(R.id.mainBackground).setImageBitmap(soft)
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            findViewById<View>(R.id.statusBarScrim).layoutParams.height = systemBars.top
            findViewById<View>(R.id.navBarScrim).layoutParams.height = systemBars.bottom
            findViewById<View>(R.id.statusBarScrim).requestLayout()
            findViewById<View>(R.id.navBarScrim).requestLayout()
            insets
        }

        onBackPressedDispatcher.addCallback(this, selectionBackCallback)
        // La etiqueta predefinida "En ninguna lista" necesita saber qué canciones están en alguna
        // Lista (ver TagsViewModel/LibraryRepository.tagSummaries). Se conecta AQUÍ, antes de que
        // exista ningún fragmento, porque tagsViewModel ahora se observa desde más sitios que la
        // pestaña Etiquetas (Canciones, un álbum/artista, una lista, el buscador, el iPod...) y
        // cualquiera de ellos puede abrirse sin haber visitado nunca esa pestaña.
        tagsViewModel.bindPlaylistFilenames { playlistsViewModel.allFilenamesInPlaylists }
        setupToolbar()
        setupSearch()
        setupPager(savedInstanceState?.getInt(STATE_SELECTED_TAB, 0) ?: 0)
        setupMiniPlayer()
        setupDynamicColor()
        checkStoragePermission()       /** Lo primero que hacemos es pedir permiso de almacenamiento **/
        requestNotificationPermissionIfNeeded()
        handleViewIntent(intent)       /** "Abrir con UltiMusic" desde un gestor de archivos **/
        handleRecountIntent(intent)    /** Toque en la notificación de UltiMusic Recount **/
        offerRecountIfDue()            /** Última semana de diciembre: se ofrece solo **/
    }

    /**
     * Con `launchMode="singleTask"` (ver el manifiesto), si la app ya estaba abierta el sistema NO
     * crea una `MainActivity` nueva: reutiliza esta y el archivo llega aquí en vez de a [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        handleRecountIntent(intent)
    }

    /**
     * UltiMusic Recount, cuando llega de fuera: el usuario ha tocado la notificación de la última
     * semana de diciembre (ver [RecountReminder]). El extra se consume al leerlo para que, si el
     * sistema reentrega el mismo `Intent` -al girar la pantalla, por ejemplo-, la pantalla no se
     * vuelva a abrir sola encima de lo que estuviera haciendo.
     */
    private fun handleRecountIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(RecountReminder.EXTRA_OPEN_RECOUNT, false) != true) return
        intent.removeExtra(RecountReminder.EXTRA_OPEN_RECOUNT)
        openRecount()
    }

    /**
     * El aviso de arranque de la última semana de diciembre. Es la otra mitad del recordatorio, y
     * existe además de la notificación porque no depende de ningún permiso: si el usuario denegó
     * `POST_NOTIFICATIONS`, esta es la única forma de que se entere.
     *
     * "Ahora no" no marca nada a propósito: el aviso vuelve a salir en el siguiente arranque hasta
     * que se entre al Recount o hasta que se acabe el año. Lo que sí lo apaga -las dos vías a la
     * vez- es ENTRAR, y de eso se encarga [openRecount].
     */
    private fun offerRecountIfDue() {
        if (!RecountReminder.shouldRemind()) return
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.recount_reminder_title, year))
            .setMessage(R.string.recount_reminder_text)
            .setPositiveButton(R.string.recount_reminder_see) { _, _ -> openRecount() }
            .setNegativeButton(R.string.recount_reminder_later, null)
            .show()
        // Este aviso sale en el arranque, ANTES de que el servicio se enlace (ver
        // PlayerViewModel.accentColor y bindService en el propio ViewModel): un AccentTint.buttons
        // de una sola lectura de accentColor.value casi siempre pillaría el valor por defecto
        // (el amarillo fijo), aunque hubiera una canción sonando de fondo. Se sigue el acento en
        // vivo mientras el diálogo esté en pantalla -igual que la pestaña en setupDynamicColor- y
        // se corta el collect al cerrarlo.
        val accentJob = lifecycleScope.launch {
            playerViewModel.accentColor.collect { accent -> AccentTint.buttons(dialog, accent) }
        }
        dialog.setOnDismissListener { accentJob.cancel() }
    }

    /**
     * Abre UltiMusic Recount y da por visto el aviso de este año, que es lo que hace desaparecer a la
     * vez la notificación y el diálogo de arranque. Entrar desde Ajustes pasa por
     * [SettingsDialogFragment][com.untar.ultimusic.ui.settings.SettingsDialogFragment] y no por aquí,
     * pero también marca el año como visto por su cuenta.
     */
    private fun openRecount() {
        RecountReminder.markSeen(this)
        RecountDialogFragment.show(supportFragmentManager)
    }

    /**
     * Permiso de la notificación de reproducción ([com.untar.ultimusic.playback.PlaybackService]).
     * Solo hace falta pedirlo a partir de Android 13 (API 33): en versiones anteriores, mostrar una
     * notificación no requiere permiso del usuario. Se pide directamente, sin diálogo de aviso
     * previo (a diferencia del de almacenamiento): es de bajo riesgo y, si se deniega, la aplicación
     * sigue funcionando exactamente igual, solo que sin notificación ni controles en la pantalla de
     * bloqueo.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        grantNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val grantNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Se deniegue o
            no, no hay nada más que hacer aquí: ver el porqué en requestNotificationPermissionIfNeeded. */ }

    /**
     * Pide la carpeta `UltiMusic` con el selector del sistema (ver [SafStorage]): es la ÚNICA carpeta
     * obligatoria, sin ella no hay fonoteca. Tras concederla, se ofrece —una sola vez en la vida de la
     * instalación, ver [offerLegacyRootsRelinkIfNeeded]— re-conceder también Download/Music/las
     * raíces propias que tuviera de antes de esta migración, para re-enlazar lo ya catalogado.
     */
    private fun checkStoragePermission() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()   /** Si el permiso está, creamos los modelos de las canciones **/
            offerExtraFolderStep()
        }
        else {
            // Primer diálogo: una sola salida (sin "Cancelar"), porque sin UltiMusic no hay
            // fonoteca ni se puede mirar si hay una BD que restaurar (ver grantUltiMusicRoot).
            // Se intenta crear ~/UltiMusic ya ahora, y el selector se abre dentro de ella (o en ~
            // si no se pudo crear): al usuario solo le queda pulsar "Usar esta carpeta".
            val initialUri = SafStorage.initialPickerUri()
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_message)
                .setCancelable(false)
                .setPositiveButton(R.string.permission_grant) { _, _ ->
                    permissionDialogPending = false
                    grantUltiMusicRoot.launch(initialUri)
                }
                .show()
            AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
        }
    }

    /**
     * Segundo paso del primer arranque, DESPUÉS de conceder `UltiMusic` (y de decidir si se
     * restaura una BD, ver [LibraryRepository.grantUltiMusicRoot]): si está pendiente, el diálogo
     * "Ahora elige tu carpeta de música"; si no, la oferta de siempre de re-enlazar carpetas de
     * antes de la migración a SAF.
     */
    private fun offerExtraFolderStep() {
        if (SafStorage.isMusicFolderPromptPending(this)) showMusicFolderPrompt()
        else offerLegacyRootsRelinkIfNeeded()
    }

    private fun showMusicFolderPrompt() {
        SafStorage.setMusicFolderPromptPending(this, false)
        SafStorage.markOfferedLegacyRootsRelink(this)   // este diálogo ya cumple la función de la oferta vieja
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(R.string.music_folder_prompt_message)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_grant) { _, _ -> grantExtraLibraryRoot.launch(null) }
            .setNegativeButton(R.string.music_folder_prompt_cancel, null)
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    private val grantUltiMusicRoot =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                checkStoragePermission()   // el diálogo no tiene "Cancelar": vuelve a salir
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                // Antes de nada, porque la restauración puede reiniciar el proceso: tras el reinicio
                // el segundo diálogo sigue pendiente (ver offerExtraFolderStep).
                SafStorage.setMusicFolderPromptPending(this@MainActivity, true)
                val restartNeeded = LibraryRepository.get(this@MainActivity).grantUltiMusicRoot(uri)
                if (restartNeeded) {
                    // Se encontró una copia de la BD más completa que la recién creada: entra en
                    // su lugar al arrancar (ver UltiMusicDatabase.restoreFromBackupIfNeeded).
                    packageManager.getLaunchIntentForPackage(packageName)?.let {
                        startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@launch
                }
                loadIfPermitted()
                offerExtraFolderStep()
            }
        }

    /**
     * Tras conceder `UltiMusic`, si queda alguna canción catalogada ANTES de la migración a SAF sin
     * re-enlazar (ver [LibraryRepository.hasUnlinkedLegacySongs]), ofrece re-conceder también
     * Download/Music/una raíz propia con el mismo selector. Se pregunta como mucho una vez en la vida
     * de la instalación ([SafStorage.hasOfferedLegacyRootsRelink]): quien la salta siempre puede
     * añadir una carpeta más tarde a mano desde Ajustes > Carpetas de la fonoteca, con el mismo
     * selector.
     */
    private fun offerLegacyRootsRelinkIfNeeded() {
        if (SafStorage.hasOfferedLegacyRootsRelink(this)) return
        lifecycleScope.launch {
            if (!LibraryRepository.get(this@MainActivity).hasUnlinkedLegacySongs()) return@launch
            SafStorage.markOfferedLegacyRootsRelink(this@MainActivity)
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.relink_legacy_roots_title)
                .setMessage(R.string.relink_legacy_roots_message)
                .setPositiveButton(R.string.relink_legacy_roots_action) { _, _ -> grantExtraLibraryRoot.launch(null) }
                .setNegativeButton(R.string.relink_legacy_roots_skip, null)
                .show()
            AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
        }
    }

    private val grantExtraLibraryRoot =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch { LibraryRepository.get(this@MainActivity).addLibraryRoot(uri) }
        }


    /** OBTENEMOS LA LISTA DE CANCIONES **/
    override fun onResume() {
        super.onResume()
        loadIfPermitted()
        // Estando la aplicación en segundo plano, el sistema puede haber matado el PlaybackService
        // (no está en primer plano mientras no suene nada). Lo revivimos aquí, al volver, en vez de
        // esperar a que el usuario toque una canción: así la cola y el mini-reproductor ya están de
        // vuelta cuando mira. Si el Service sigue vivo —el caso normal—, esto no hace nada.
        playerViewModel.ensureServiceRunning()
    }

    /**
     * Al pasar a segundo plano, exportamos una copia visible de la base de datos a
     * ~/UltiMusic/databases (best-effort, prioridad baja). El scope de la app garantiza que
     * termine aunque la Activity se destruya. También retiramos nuestro interés en el monitor de
     * cambios del filesystem (ver el recuento de referencias en
     * [LibraryRepository.startWatchingLibraryChanges]) y guardamos qué estaba sonando: si Android
     * mata el proceso mientras la app está en segundo plano (frecuente pasado un rato), sin esto
     * `PlayerViewModel` se recrearía en blanco y la app "olvidaría" la canción, aunque la base de
     * datos siga intacta. El monitor en sí puede seguir vivo pese a esto: si hay una sesión de
     * reproducción activa, [com.untar.ultimusic.playback.PlaybackService] también lo tiene pedido, y
     * no se para hasta que él también lo suelte.
     */
    override fun onStop() {
        super.onStop()
        playerViewModel.savePlaybackState()
        val repository = LibraryRepository.get(this)
        repository.stopWatchingLibraryChanges(LibraryRepository.LibraryWatchRequester.ACTIVITY)
        appScope.launch { repository.exportDatabaseCopy() }
    }

    private fun setupToolbar() {
        // El engranaje de la izquierda abre los ajustes; el "+" de la derecha, el buscador de
        // fragmentos. La barra de búsqueda del centro la engancha setupSearch().
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnHelp).setOnClickListener { showPreviewSearch() }

        val btnSort = findViewById<ImageButton>(R.id.btnSort)
        btnSort.setOnClickListener {
            if (songsViewModel.selectedIds.value.isEmpty()) showSort() else showSelectionMenu(btnSort)
        }

        val searchBar = findViewById<View>(R.id.searchBar)
        val tvSelectionCount = findViewById<TextView>(R.id.tvSelectionCount)

        // Mientras haya canciones marcadas (pulsación larga en la pestaña Canciones, ver
        // SongsAdapter/SongsFragment): el ojo de ordenar se convierte en el menú de 3 puntos de la
        // selección y la barra de búsqueda deja su hueco a "N seleccionadas". songsViewModel es la
        // MISMA instancia de ámbito de actividad que usa SongsFragment, así que esto se entera al
        // instante de cada marca/desmarca.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                songsViewModel.selectedIds.collect { ids ->
                    val selecting = ids.isNotEmpty()
                    btnSort.setImageResource(if (selecting) R.drawable.ic_more_vert else R.drawable.ic_eye)
                    btnSort.contentDescription =
                        getString(if (selecting) R.string.song_selection_more else R.string.action_sort)
                    selectionBackCallback.isEnabled = selecting
                    searchBar.visibility = if (selecting) View.GONE else View.VISIBLE
                    tvSelectionCount.visibility = if (selecting) View.VISIBLE else View.GONE
                    if (selecting) {
                        tvSelectionCount.text =
                            resources.getQuantityString(R.plurals.song_selection_count, ids.size, ids.size)
                    }
                }
            }
        }
    }

    /**
     * Atrás, mientras hay una selección múltiple activa, la limpia en vez de salir de la
     * aplicación (MainActivity es la raíz). Se activa/desactiva en el `collect` de
     * [setupToolbar] según [SongsViewModel.selectedIds].
     */
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            songsViewModel.clearSelection()
        }
    }

    /** Abre el buscador de fragmentos (ver [PreviewSearchDialogFragment]). El guard evita apilar
     * dos si se toca el botón dos veces seguidas antes de que aparezca el primero. */
    private fun showPreviewSearch() {
        if (supportFragmentManager.findFragmentByTag(PreviewSearchDialogFragment.TAG) != null) return
        PreviewSearchDialogFragment.newInstance()
            .show(supportFragmentManager, PreviewSearchDialogFragment.TAG)
    }

    /**
     * Abre el diálogo de ordenar (ver [SortDialogFragment]) de la pestaña que se esté viendo AHORA
     * en el ViewPager, no una fija: es lo que espera el usuario al tocar el ojo mientras mira, por
     * ejemplo, la pestaña de Artistas. Las posiciones del ViewPager y de [LibraryTab] van en el mismo
     * orden a propósito (ver [MainPagerAdapter]), así que basta con el índice de `values()`.
     */
    private fun showSort() {
        if (supportFragmentManager.findFragmentByTag(SortDialogFragment.TAG) != null) return
        val position = findViewById<ViewPager2>(R.id.viewPager).currentItem
        val tab = LibraryTab.values().getOrNull(position) ?: return
        SortDialogFragment.newInstance(tab).show(supportFragmentManager, SortDialogFragment.TAG)
    }

    /**
     * Menú de 3 puntos de la selección múltiple de canciones (ver menu_song_selection.xml): sin
     * "Ir al...", porque no tiene sentido para varias canciones a la vez. Cada acción limpia la
     * selección al lanzarse -no al terminar-, igual que tocar una canción suelta cierra su propio
     * menú de 3 puntos: lo marcado ya ha hecho su papel de decirle a la acción sobre qué canciones
     * actuar.
     */
    private fun showSelectionMenu(anchor: View) {
        val ids = songsViewModel.selectedIds.value
        val selected = songsViewModel.songs.value.filter { it.id in ids }
        if (selected.isEmpty()) return
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_song_selection, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_to_queue -> {
                        songsViewModel.clearSelection()
                        playerViewModel.addToQueue(selected)
                        true
                    }
                    R.id.action_add_to_playlist -> { showAddToPlaylistForSelection(selected); true }
                    R.id.action_edit_metadata -> { showMetadataEditorForSelection(selected); true }
                    R.id.action_edit_tags -> { showEditTagsForSelection(selected); true }
                    R.id.action_share_song -> { songsViewModel.clearSelection(); com.untar.ultimusic.util.ShareSong.share(this@MainActivity, selected); true }
                    R.id.action_delete_song -> { showDeleteDialogForSelection(selected); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Igual que `SongsFragment.showAddToPlaylist`, pero para varias canciones a la vez: el propio
     *  diálogo ya está pensado para una lista de archivos (ver [AddToPlaylistDialogFragment]). */
    private fun showAddToPlaylistForSelection(selected: List<Song>) {
        songsViewModel.clearSelection()
        if (supportFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        lifecycleScope.launch {
            val filenames = selected.map { File(it.filePath).name }
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(filenames)
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(filenames, names, checked)
                .show(supportFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /** Abre el editor de metadatos en modo múltiple (ver [MetadataEditorDialogFragment]). Misma
     *  etiqueta que usa `SongsFragment` para una canción suelta: es la misma pantalla, solo cambia
     *  cuántos ids se le pasan. */
    private fun showMetadataEditorForSelection(selected: List<Song>) {
        songsViewModel.clearSelection()
        if (supportFragmentManager.findFragmentByTag("metadataEditor") == null) {
            MetadataEditorDialogFragment.newInstance(selected.map { it.id })
                .show(supportFragmentManager, "metadataEditor")
        }
    }

    /** Abre el editor de etiquetas en modo múltiple (ver [SongTagsDialogFragment]): al principio solo
     *  salen las etiquetas comunes a TODAS las seleccionadas, y lo que se añada/quite ahí se aplica a
     *  todas de golpe al pulsar "Aceptar", no en directo como en el caso de una sola canción. */
    private fun showEditTagsForSelection(selected: List<Song>) {
        songsViewModel.clearSelection()
        if (supportFragmentManager.findFragmentByTag(SongTagsDialogFragment.TAG) == null) {
            SongTagsDialogFragment.newInstance(selected.map { it.id })
                .show(supportFragmentManager, SongTagsDialogFragment.TAG)
        }
    }

    /** Confirmación antes de borrar de verdad los archivos del dispositivo, igual que
     *  `SongsFragment.showDeleteDialog` pero con el mensaje en plural (ver delete_songs_confirm). */
    private fun showDeleteDialogForSelection(selected: List<Song>) {
        songsViewModel.clearSelection()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete_song)
            .setMessage(resources.getQuantityString(R.plurals.delete_songs_confirm, selected.size, selected.size))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                songsViewModel.delete(selected)
                playlistsViewModel.forgetSongs(selected.map { File(it.filePath).name })
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentTarget.value)
    }

    /**
     * Abre la pantalla de ajustes. La guarda del `findFragmentByTag` evita que dos toques seguidos
     * apilen dos pantallas idénticas.
     */
    private fun showSettings() {
        if (supportFragmentManager.findFragmentByTag(SettingsDialogFragment.TAG) == null) {
            SettingsDialogFragment().show(supportFragmentManager, SettingsDialogFragment.TAG)
        }
    }

    /**
     * Convierte la barra de búsqueda de la toolbar en un buscador de verdad: ver
     * [SearchBarController] para el porqué de cada pieza.
     */
    private fun setupSearch() {
        SearchBarController(
            activity = this,
            topBar = findViewById<ViewGroup>(R.id.toolbar),
            btnSettings = findViewById(R.id.btnSettings),
            btnHelp = findViewById(R.id.btnHelp),
            btnSort = findViewById(R.id.btnSort),
            searchLeadingIcon = findViewById<ImageView>(R.id.searchLeadingIcon),
            searchInput = findViewById<EditText>(R.id.searchInput),
            btnSearchClear = findViewById(R.id.btnSearchClear),
            btnSearchSql = findViewById<TextView>(R.id.btnSearchSql),
            searchBar = findViewById(R.id.searchBar),
            contentArea = findViewById<ViewGroup>(R.id.contentArea),
            browseContent = findViewById(R.id.browseContent),
            searchResultsContainer = findViewById(R.id.searchResultsContainer),
            recycler = findViewById<RecyclerView>(R.id.recyclerSearchResults),
            emptyView = findViewById<TextView>(R.id.searchEmptyView),
            lyricsRow = findViewById(R.id.searchLyricsRow),
            lyricsCheck = findViewById(R.id.searchLyricsCheck),
            viewModel = searchViewModel,
            playerViewModel = playerViewModel,
            lifecycleOwner = this
        ).bind()
    }

    /**
     * A diferencia de una TabLayout normal, cada pestaña lleva una vista propia (ver
     * item_nav_tab.xml): así el icono puede subir solo para dejarle sitio al nombre en la pestaña
     * actual, algo que `tab.setText/setIcon` no permite. El color (acento vs. gris apagado) lo pone
     * [applyTabAppearance], llamado aquí al construir cada pestaña y luego en cada cambio de
     * selección; el de la pestaña actual se re-tiñe además cada vez que cambia el acento (ver
     * setupDynamicColor).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        findViewById<TabLayout>(R.id.tabLayout)?.let { outState.putInt(STATE_SELECTED_TAB, it.selectedTabPosition) }
    }

    /**
     * [initialTab]: la pestaña en la que estaba la Activity antes de recrearse (p. ej. al cambiar
     * de idioma). ViewPager2 restaura él solo su página, pero las pestañas son vistas nuestras: si
     * se construyesen siempre con Canciones marcada, la barra quedaría a medias (icono de Listas
     * en acento, Canciones levantada sin nombre). Por eso se construyen ya con la buena marcada.
     */
    private fun setupPager(initialTab: Int) {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val initial = initialTab.coerceIn(0, tabTitles.size - 1)
        viewPager.adapter = MainPagerAdapter(this, tabTitles)
        // Como Phonograph (pager.setOffscreenPageLimit(count - 1)): todas las páginas se crean de
        // entrada, así que al tocar una pestaña lejana no se infla ningún fragmento en mitad del
        // scroll suave. Con saltos > 3 páginas (Canciones -> Etiquetas) ViewPager2 encola un
        // smoothScroll; si el layout se invalidaba entonces, la página acababa siendo otra distinta
        // a la de la pestaña marcada.
        viewPager.offscreenPageLimit = tabTitles.size - 1
        // Sin TabLayoutMediator: su clic en una pestaña llama a setCurrentItem(pos, smooth), que
        // para saltos > 3 páginas hace el truco del salto a medias + scroll encolado. Aquí el clic
        // anima con un fake drag (ver animatePagerTo) y la duración de Phonograph.
        for (position in tabTitles.indices) {
            val tab = tabLayout.newTab()
            val customView = LayoutInflater.from(this).inflate(R.layout.item_nav_tab, tabLayout, false)
            customView.findViewById<ImageView>(R.id.navIcon).setImageResource(tabIcons[position])
            customView.findViewById<TextView>(R.id.navLabel).text = tabTitles[position]
            tab.customView = customView
            applyTabAppearance(tab, selected = position == initial, currentAccentColor)
            tabLayout.addTab(tab, position == initial)
        }
        viewPager.setCurrentItem(initial, false)
        // Deslizar con el dedo -> marca la pestaña de la página que queda.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (tabLayout.selectedTabPosition != position && pagerAnimator == null) {
                    tabLayout.getTabAt(position)?.select()
                }
            }
        })

        redistributeTabPadding(tabLayout, selectedPosition = initial)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (viewPager.currentItem != tab.position) animatePagerTo(viewPager, tab.position)
                applyTabAppearance(tab, true, currentAccentColor)
                redistributeTabPadding(tabLayout, tab.position)
                // La selección múltiple es solo de la pestaña Canciones (posición 0, ver
                // SongsViewModel.selectedIds): si se sale de ella, se limpia sola en vez de
                // quedarse "colgada" con el menú de 3 puntos puesto en otra pestaña.
                if (tab.position != 0) songsViewModel.clearSelection()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = applyTabAppearance(tab, false, currentAccentColor)
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private var pagerAnimator: android.animation.ValueAnimator? = null

    /**
     * Mismo scroll que el ViewPager clásico de Phonograph al tocar una pestaña: duración
     * `(páginas + 1) * 100` ms con tope de 600, e interpolador quíntico `(t-1)^5 + 1`. Se hace con
     * fake drag porque ViewPager2 no deja fijar la duración de setCurrentItem(smooth). Todas las
     * páginas existen ya (offscreenPageLimit), así que el recorrido no crea fragmentos.
     */
    private fun animatePagerTo(viewPager: ViewPager2, target: Int) {
        pagerAnimator?.cancel()   // su onAnimationEnd cierra el fake drag anterior
        val pages = Math.abs(target - viewPager.currentItem)
        if (pages == 0 || viewPager.width == 0 || !viewPager.beginFakeDrag()) {
            viewPager.setCurrentItem(target, false)
            return
        }
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val sign = (if (target > viewPager.currentItem) -1f else 1f) * (if (rtl) -1f else 1f)
        val total = sign * pages * viewPager.width
        var done = 0f
        pagerAnimator = android.animation.ValueAnimator.ofFloat(0f, total).apply {
            duration = minOf((pages + 1) * 100L, 600L)
            interpolator = android.animation.TimeInterpolator { t -> val u = t - 1f; u * u * u * u * u + 1f }
            addUpdateListener {
                val v = it.animatedValue as Float
                viewPager.fakeDragBy(v - done)
                done = v
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (viewPager.isFakeDragging) viewPager.endFakeDrag()
                    if (pagerAnimator === animation) pagerAnimator = null
                }
            })
            start()
        }
    }

    /** Icono (y, si `selected`, nombre debajo) en gris apagado o en el color dinámico del acento. */
    private fun applyTabAppearance(tab: TabLayout.Tab, selected: Boolean, accent: Int) {
        val view = tab.customView ?: return
        val icon = view.findViewById<ImageView>(R.id.navIcon)
        val label = view.findViewById<TextView>(R.id.navLabel)
        val color = if (selected) accent else mutedTabColor
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        label.visibility = if (selected) View.VISIBLE else View.GONE
        label.setTextColor(color)
    }

    /**
     * Al aparecer el nombre de la pestaña seleccionada, su ancho crece (el layout de
     * item_nav_tab.xml es vertical y el texto suele ser más ancho que el icono de 24dp). Sin esto
     * esa pestaña empujaría a las demás -y, en modo scrollable, Material acabaría desplazando toda
     * la tira para dejarla visible, sacando la primera pestaña casi de la pantalla-. En su lugar,
     * le quitamos el mismo padding horizontal a cada pestaña NO seleccionada para hacerle sitio
     * exacto al texto: el ancho total de la barra no cambia, así que nada se desplaza ni hay que
     * recortar el nombre (el padding tiene un suelo en [tabPaddingMinDp] por si algún nombre fuera
     * tan largo que ni así cupiese).
     */
    private fun redistributeTabPadding(tabLayout: TabLayout, selectedPosition: Int) {
        val density = resources.displayMetrics.density
        val restPaddingPx = tabPaddingRestDp * density
        val minPaddingPx = tabPaddingMinDp * density
        val iconWidthPx = tabIconWidthDp * density

        val selectedLabel = tabLayout.getTabAt(selectedPosition)?.customView
            ?.findViewById<TextView>(R.id.navLabel) ?: return
        // Si el nombre en una línea haría la pestaña más de 1.5x su ancho en reposo, se parte en
        // dos (ver splitTabTitle). El ancho de la etiqueta se fija a mano al medido, en vez de
        // dejarlo a wrap_content, para que coincida exactamente con el hueco que se le abre.
        val restTabWidthPx = iconWidthPx + 2 * restPaddingPx
        val maxSingleLinePx = tabMaxGrowthFactor * restTabWidthPx - 2 * restPaddingPx
        val title = tabTitles.getOrNull(selectedPosition) ?: selectedLabel.text.toString()
        val lines = splitTabTitle(title, selectedLabel.paint, maxSingleLinePx)
        selectedLabel.text = lines.joinToString("\n")
        val labelWidthPx = Math.ceil(lines.maxOf { selectedLabel.paint.measureText(it) }.toDouble()).toInt()
        selectedLabel.layoutParams = selectedLabel.layoutParams.apply { width = labelWidthPx }
        val growthPx = (labelWidthPx - iconWidthPx).coerceAtLeast(0f)

        val othersCount = tabLayout.tabCount - 1
        val reductionPerSidePx = if (othersCount > 0) growthPx / othersCount / 2f else 0f
        val unselectedPaddingPx = (restPaddingPx - reductionPerSidePx).coerceAtLeast(minPaddingPx).toInt()
        val restPaddingIntPx = restPaddingPx.toInt()

        for (i in 0 until tabLayout.tabCount) {
            val view = tabLayout.getTabAt(i)?.customView ?: continue
            val paddingPx = if (i == selectedPosition) restPaddingIntPx else unselectedPaddingPx
            view.setPadding(paddingPx, view.paddingTop, paddingPx, view.paddingBottom)
        }
    }

    /**
     * Devuelve [title] en una sola línea si cabe en [maxWidthPx]; si no, en dos. Si el nombre
     * tiene espacios se corta por el espacio más equilibrado ("Daftar / Putar"); si es una sola
     * palabra, por sílabas con el separador silábico del sistema para el idioma de la app
     * ("Eti- / quetas", ver [hyphenateBySyllables]). Si el sistema no tiene datos de separación
     * para ese idioma, por el punto que deja las dos mitades más parejas, también con guión. Solo
     * se corta entre grafemas completos (BreakIterator) para no separar un acento o una marca
     * combinante de su letra, y en escrituras sin guiones (chino, japonés, tailandés...) se parte
     * sin él.
     */
    private fun splitTabTitle(title: String, paint: android.text.TextPaint, maxWidthPx: Float): List<String> {
        if (paint.measureText(title) <= maxWidthPx) return listOf(title)

        fun cost(a: String, b: String) = maxOf(paint.measureText(a), paint.measureText(b))

        val spaces = title.indices.filter { title[it] == ' ' }
        if (spaces.isNotEmpty()) {
            val best = spaces.minBy { cost(title.substring(0, it), title.substring(it + 1)) }
            return listOf(title.substring(0, best).trimEnd(), title.substring(best + 1).trimStart())
        }

        if (usesHyphenation(title)) hyphenateBySyllables(title, paint)?.let { return it }

        val boundaries = java.text.BreakIterator.getCharacterInstance().run {
            setText(title)
            generateSequence(first()) { next().takeIf { b -> b != java.text.BreakIterator.DONE } }
                .filter { it in 1 until title.length }
                .toList()
        }
        if (boundaries.isEmpty()) return listOf(title)
        val hyphen = if (usesHyphenation(title)) "-" else ""
        val best = boundaries.minBy { cost(title.substring(0, it) + hyphen, title.substring(it)) }
        return listOf(title.substring(0, best) + hyphen, title.substring(best))
    }

    /**
     * Parte una sola palabra en dos líneas por una sílaba, usando el mismo separador silábico
     * que TextView (patrones por idioma del sistema, según el `textLocale` del [paint], que es el
     * de la app). Se prueba un StaticLayout cada vez más ancho, desde la mitad de la palabra, y se
     * queda el primero que la deja en dos líneas con guión: así sale el corte silábico más
     * equilibrado. Si el sistema corta sin guión (no tiene patrones para el idioma y parte "a la
     * desesperada" por cualquier letra) se descarta y devuelve null.
     *
     * Las líneas se devuelven ya como texto ("Eti-", "quetas") para ponerlas con "\n" en la
     * etiqueta, en vez de dejar que la etiqueta haga su propia separación: así el corte es
     * exactamente el medido.
     */
    private fun hyphenateBySyllables(word: String, paint: android.text.TextPaint): List<String>? {
        val fullWidth = paint.measureText(word)
        val stepPx = resources.displayMetrics.density.coerceAtLeast(1f)
        var width = fullWidth / 2f
        while (width < fullWidth) {
            val layout = android.text.StaticLayout.Builder
                .obtain(word, 0, word.length, paint, Math.ceil(width.toDouble()).toInt())
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_FULL)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setIncludePad(false)
                .build()
            if (layout.lineCount == 2) {
                val breakAt = layout.getLineEnd(0)
                val first = word.substring(0, breakAt)
                // El guión no está en el texto: el layout lo dibuja aparte, así que se nota en
                // que la línea mide más que su texto.
                val hyphenated = layout.getLineWidth(0) > paint.measureText(first) + 0.5f
                if (hyphenated && breakAt in 1 until word.length) {
                    return listOf("$first-", word.substring(breakAt))
                }
            }
            width += stepPx
        }
        return null
    }

    /** false para escrituras que no parten palabras con guión (CJK, tailandés, etc.). */
    private fun usesHyphenation(text: String): Boolean {
        val noHyphen = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR
        )
        val first = text.firstOrNull { Character.isLetter(it) } ?: return true
        return Character.UnicodeScript.of(first.code) !in noHyphen
    }

    /**
     * El enganche del propio mini-reproductor (título, play/pausa, arrastre para abrir el iPod,
     * color dinámico) vive en [MiniPlayerController], compartido con
     * [com.untar.ultimusic.ui.library.DetailDialogFragment]. Aquí solo queda lo que es específico
     * de la actividad: avisar si el archivo que tocaba sonar ha desaparecido.
     */
    private fun setupMiniPlayer() {
        val miniPlayer = findViewById<View>(R.id.miniPlayer)
        val progress = findViewById<SeekBar>(R.id.songProgress)
        MiniPlayerController(miniPlayer, progress, playerViewModel, supportFragmentManager, this).bind()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Si al ir a reproducir resulta que el archivo ya no está, lo decimos (antes el
                // reproductor se quedaba mudo en 0:00 sin explicar por qué) y además sacamos la
                // canción de todas las listas: dejarla ahí solo serviría para que reapareciera
                // en la lista y volviera a fallar en cuanto se pulsara.
                playerViewModel.missingFile.collect { song ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.song_file_missing, song.title),
                        Toast.LENGTH_LONG
                    ).show()
                    playlistsViewModel.forgetSong(File(song.filePath).name)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.currentSongAddedToQueue.collect {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.cannot_add_current_song_to_queue,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.cannotAddToEmptyQueue.collect {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.cannot_add_to_empty_queue,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // El mensaje ya viene formateado desde PlaybackService (necesita el título de la
                // canción y, si se sabe, el motivo del fallo).
                playerViewModel.playbackError.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.songsAddedToQueue.collect { count ->
                    Toast.makeText(
                        this@MainActivity,
                        resources.getQuantityString(R.plurals.toast_added_to_queue, count, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Tiñe con el color de la canción que suena el indicador y el texto de la pestaña activa. El
     * resto de lo que antes era amarillo fijo (barra de progreso y botones del mini-reproductor) lo
     * tiñe [MiniPlayerController], compartido con la ficha de detalle.
     *
     * El color lo calcula [PlayerViewModel] a partir de la carátula (ver
     * [com.untar.ultimusic.util.DynamicColor]); aquí solo se aplica. Mientras no suena
     * nada vale el amarillo de siempre, así que la app arranca igual que antes.
     */
    private fun setupDynamicColor() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.accentColor.collect { accent ->
                    currentAccentColor = accent
                    // Sin esto, el ripple al tocar una pestaña se queda con el colorPrimary del
                    // tema (amarillo fijo) en vez de seguir el acento.
                    tabLayout.tabRippleColor = ColorStateList.valueOf(accent)
                    // La barra superior, la de pestañas y las franjas de las barras del sistema NO
                    // siguen el acento: se quedan en el gris oscuro (um_surface) y los botones de
                    // arriba en el gris claro (um_on_background) que traen de activity_main.xml.
                    // Solo repintamos la pestaña actual: las demás ya están en gris y no cambian.
                    tabLayout.getTabAt(tabLayout.selectedTabPosition)?.let { tab ->
                        applyTabAppearance(tab, selected = true, accent)
                    }
                }
            }
        }
    }

    // --- Permiso de almacenamiento (Storage Access Framework, ver SafStorage) ---

    private fun hasStoragePermission(): Boolean {
        val treeUri = SafStorage.ultiMusicTreeUri(this) ?: return false
        return SafStorage.isRootReadable(this, treeUri)
    }

    // Se llama desde el onResume = recargamos la lista de canciones cada vez que volvemos a la aplicación
    private fun loadIfPermitted() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()
            LibraryRepository.get(this).startWatchingLibraryChanges(LibraryRepository.LibraryWatchRequester.ACTIVITY)
        } else if (!permissionDialogPending) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    // --- "Abrir con UltiMusic" desde un gestor de archivos ---

    /**
     * Reproduce el archivo que llega en un intent VIEW. Si ya está catalogado en la fonoteca (mismo
     * docPath que alguna fila, ver [SafStorage.docPathOf]), se reproduce esa canción tal cual (con su
     * portada, artistas, letra…); si no —el caso normal, porque puede venir de cualquier carpeta o
     * app, no solo de una concedida—, se leen sus etiquetas al vuelo directamente sobre el `Uri` (ver
     * [MusicScanner.readTagsFromUri]) y se reproduce suelta, sin escribir nada en la base de datos ni
     * copiar el archivo a ningún sitio: `content://` se reproduce y se lee directamente, sin pasar
     * por MediaStore (ver CLAUDE.md).
     */
    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW } ?: return
        lifecycleScope.launch {
            val docPath = if (uri.scheme == "content") SafStorage.docPathOf(uri) else null
            val song = docPath?.let { LibraryRepository.get(this@MainActivity).songByPath(it) }
                ?: withContext(Dispatchers.IO) {
                    MusicScanner.readTagsFromUri(this@MainActivity, uri)?.toAdHocSong()
                }
            if (song == null) {
                Toast.makeText(this@MainActivity, R.string.cannot_open_file, Toast.LENGTH_LONG).show()
                return@launch
            }
            playerViewModel.play(song)
        }
    }

    /**
     * Convierte las etiquetas leídas al vuelo en una canción "suelta" (id 0, sin persistir): solo
     * para que [PlayerViewModel] y la notificación tengan algo que enseñar mientras suena.
     */
    private fun ScannedSong.toAdHocSong(): Song = Song(
        filePath = filePath,
        title = title,
        artists = artist?.let { listOf(Artist(name = it, imageName = null)) } ?: emptyList(),
        albums = album?.let {
            listOf(
                SongAlbumEntry(
                    album = Album(title = it, artists = emptyList(), year = year, genres = genres, imageName = null),
                    trackNumber = trackNumber,
                    discNumber = discNumber
                )
            )
        } ?: emptyList(),
        producers = producer?.let { listOf(Producer(name = it, imageName = null)) } ?: emptyList(),
        duration = duration,
        year = year,
        genres = genres,
        lyrics = null,
        language = null,
        imageName = null,
        comment = null,
        videoUrl = null,
        videoThumbnailName = null,
        videoOffsetMs = 0L,
        lyricsOffsetMs = 0L,
        ogTitle = null,
        ogArtist = null,
        ogYear = null,
        youtubeViewCount = null
    )
}
