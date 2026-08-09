package com.untar.ultimusic.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
import com.untar.ultimusic.ui.common.MiniPlayerController
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.ui.search.SearchBarController
import com.untar.ultimusic.ui.search.SearchViewModel
import com.untar.ultimusic.ui.settings.SettingsDialogFragment
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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
    // activityViewModels(). Aquí solo se usa para sacar de las playlists las canciones cuyo archivo
    // ha desaparecido; al pasar por el ViewModel, su `tick` se encarga de repintar las pestañas.
    private val playlistsViewModel: PlaylistsViewModel by viewModels()

    private val tabTitles: List<String> by lazy {
        listOf(
            getString(R.string.tab_songs),
            getString(R.string.tab_albums),
            getString(R.string.tab_artists),
            getString(R.string.tab_producers),
            getString(R.string.tab_genres),
            getString(R.string.tab_playlists)
        )
    }

    /** En caso de ser true, mostramos el "por favor concede el permiso de almacenamiento" **/
    private var permissionDialogPending = true

    /** Scope de proceso para tareas best-effort que deben sobrevivir a la Activity (exportar la BD). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupSearch()
        setupPager()
        setupMiniPlayer()
        setupDynamicColor()
        checkStoragePermission()       /** Lo primero que hacemos es pedir permiso de almacenamiento **/
        requestNotificationPermissionIfNeeded()
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

    private fun checkStoragePermission() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()   /** Si el permiso está, creamos los modelos de las canciones **/
        }
        else {
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_message)
                .setPositiveButton(R.string.permission_grant) { _, _ ->
                    permissionDialogPending = false
                    requestStoragePermission()
                }
                .setNegativeButton(R.string.permission_cancel) { _, _ ->
                    permissionDialogPending = false
                    loadIfPermitted()       /** No permite -> muestra un toast **/
                }
                .show()
            AccentTint.buttons(dialog, playerViewModel.accentColor.value)
        }
    }

    /** Si el permiso no está, lo pedimos **/
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                grantStoragePermissionNewPhones.launch(intent)
            } catch (e: Exception) {
                grantStoragePermissionNewPhones.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            grantStoragePermissionOldPhones.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val grantStoragePermissionNewPhones =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            loadIfPermitted()
        }

    private val grantStoragePermissionOldPhones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) songsViewModel.loadIfNeeded()
            else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }

    
    /** OBTENEMOS LA LISTA DE CANCIONES **/
    override fun onResume() {
        super.onResume()
        loadIfPermitted()
    }

    /**
     * Al pasar a segundo plano, exportamos una copia visible de la base de datos a
     * ~/UltiMusic/databases (best-effort, prioridad baja). El scope de la app garantiza que
     * termine aunque la Activity se destruya. También detenemos el monitor de cambios del
     * filesystem y guardamos qué estaba sonando: si Android mata el proceso mientras la app está
     * en segundo plano (frecuente pasado un rato), sin esto `PlayerViewModel` se recrearía en
     * blanco y la app "olvidaría" la canción, aunque la base de datos siga intacta.
     */
    override fun onStop() {
        super.onStop()
        playerViewModel.savePlaybackState()
        val repository = LibraryRepository.get(this)
        repository.stopWatchingLibraryChanges()
        appScope.launch { repository.exportDatabaseCopy() }
    }

    private fun setupToolbar() {
        // El engranaje de la izquierda abre los ajustes; la ayuda y "más opciones" de la derecha,
        // de momento, no hacen nada. La barra de búsqueda del centro la engancha setupSearch().
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
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
            btnMore = findViewById(R.id.btnMore),
            searchLeadingIcon = findViewById<ImageView>(R.id.searchLeadingIcon),
            searchInput = findViewById<EditText>(R.id.searchInput),
            btnSearchClear = findViewById(R.id.btnSearchClear),
            contentArea = findViewById<ViewGroup>(R.id.contentArea),
            browseContent = findViewById(R.id.browseContent),
            searchResultsContainer = findViewById(R.id.searchResultsContainer),
            recycler = findViewById<RecyclerView>(R.id.recyclerSearchResults),
            emptyView = findViewById<TextView>(R.id.searchEmptyView),
            viewModel = searchViewModel,
            playerViewModel = playerViewModel,
            lifecycleOwner = this
        ).bind()
    }

    private fun setupPager() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        viewPager.adapter = MainPagerAdapter(this, tabTitles)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
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
                // canción de todas las playlists: dejarla ahí solo serviría para que reapareciera
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
        val mutedText = ContextCompat.getColor(this, R.color.um_on_surface_muted)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.accentColor.collect { accent ->
                    val tint = ColorStateList.valueOf(accent)
                    tabLayout.setSelectedTabIndicatorColor(accent)
                    // Dos colores: el de las pestañas inactivas (gris) y el de la activa (acento).
                    tabLayout.setTabTextColors(mutedText, accent)
                    // Sin esto, el ripple al tocar una pestaña se queda con el colorPrimary del
                    // tema (amarillo fijo) en vez de seguir el acento.
                    tabLayout.tabRippleColor = tint
                }
            }
        }
    }

    // --- Permisos de almacenamiento ---

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    // Se llama desde el onResume = recargamos la lista de canciones cada vez que volvemos a la aplicación
    private fun loadIfPermitted() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()
            LibraryRepository.get(this).startWatchingLibraryChanges()
        } else if (!permissionDialogPending) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }
}
