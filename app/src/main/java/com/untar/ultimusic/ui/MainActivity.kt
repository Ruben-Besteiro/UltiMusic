package com.untar.ultimusic.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
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
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.ui.player.IPodNanoDialogFragment
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.ui.search.SearchDialogFragment
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Fracción de la altura de pantalla que hay que arrastrar el mini-reproductor hacia arriba para
 * que, al soltar, el iPod termine de abrirse en vez de volver a esconderse (ver
 * [MainActivity.setupMiniPlayerDrag]).
 */
private const val OPEN_COMMIT_FRACTION = 0.4f

class MainActivity : AppCompatActivity() {

    // La cola de reproducción vive en PlayerViewModel (ámbito de actividad), compartida por el
    // mini-reproductor, SongsFragment y la ventana del iPod. Al pinchar una canción se inserta en
    // la posición 0 y la reproducción siempre mira la posición actual de la cola.

    private val songsViewModel: SongsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

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
        setupPager()
        setupMiniPlayer()
        setupDynamicColor()
        checkStoragePermission()       /** Lo primero que hacemos es pedir permiso de almacenamiento **/
    }

    private fun checkStoragePermission() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()   /** Si el permiso está, creamos los modelos de las canciones **/
        }
        else {
            AlertDialog.Builder(this)
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
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        // De los botones de la barra superior, de momento solo la lupa hace algo.
        toolbar.setNavigationOnClickListener { }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_search) showSearch()
            true
        }
    }

    /**
     * Abre el buscador de la biblioteca. La guarda del `findFragmentByTag` evita que dos toques
     * seguidos apilen dos buscadores idénticos, igual que en la ficha de detalle.
     */
    private fun showSearch() {
        if (supportFragmentManager.findFragmentByTag(SearchDialogFragment.TAG) == null) {
            SearchDialogFragment().show(supportFragmentManager, SearchDialogFragment.TAG)
        }
    }

    private fun setupPager() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        viewPager.adapter = MainPagerAdapter(this, tabTitles)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun setupMiniPlayer() {
        val miniPlayer = findViewById<View>(R.id.miniPlayer)
        val title = findViewById<TextView>(R.id.miniTitle)
        val playPause = findViewById<ImageButton>(R.id.btnPlayPause)
        val expand = findViewById<ImageButton>(R.id.btnExpand)
        val progress = findViewById<SeekBar>(R.id.songProgress)

        playPause.setOnClickListener { playerViewModel.togglePlayPause() }
        // La flecha de expandir abre la ventana del iPod a pantalla completa, con su animación de
        // ventana normal (ver [IPodNanoDialogFragment.onStart]).
        expand.setOnClickListener { IPodNanoDialogFragment().show(supportFragmentManager, "ipod") }
        // También se puede abrir arrastrando el mini-reproductor hacia arriba, siguiendo el dedo en
        // vivo (ver [setupMiniPlayerDrag]).
        setupMiniPlayerDrag(miniPlayer)

        // Arrastrar/tocar la barra mueve la reproducción. Mientras el usuario la toca, no dejamos
        // que la actualización periódica pise su posición.
        var userSeeking = false
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                playerViewModel.seekToFraction(sb.progress / 1000f)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playerViewModel.currentSong.collect { song ->
                        title.text = song?.title ?: getString(R.string.nothing_playing)
                        // Sin nada en reproducción no hay reproductor que expandir: ocultamos la flecha.
                        expand.isVisible = song != null
                    }
                }
                launch {
                    playerViewModel.isPlaying.collect { isPlaying ->
                        playPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
                launch {
                    playerViewModel.progress.collect { p ->
                        if (!userSeeking) {
                            progress.progress =
                                if (p.durationMs > 0) ((p.positionMs * 1000) / p.durationMs).toInt()
                                else 0
                        }
                    }
                }
                // Si al ir a reproducir resulta que el archivo ya no está, lo decimos (antes el
                // reproductor se quedaba mudo en 0:00 sin explicar por qué) y además sacamos la
                // canción de todas las playlists: dejarla ahí solo serviría para que reapareciera
                // en la lista y volviera a fallar en cuanto se pulsara.
                launch {
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
        }
    }

    /**
     * Arrastrar el mini-reproductor hacia arriba abre el iPod en vivo, siguiendo el dedo: la
     * ventana se crea ya al primer movimiento (escondida bajo la pantalla) y
     * [IPodNanoDialogFragment.followOpenDrag] la va destapando según se arrastra, así que a mitad
     * de arrastre se ve la mitad del iPod, igual que al cerrarlo arrastrando hacia abajo (ver
     * [IPodNanoDialogFragment.animateClose]). Al soltar, [IPodNanoDialogFragment.finishOpenDrag]
     * decide si se completa la apertura o si se vuelve a esconder, según si se ha superado
     * [OPEN_COMMIT_FRACTION] de la altura de la pantalla.
     *
     * No se reutiliza [com.untar.ultimusic.ui.common.attachVerticalDrag] porque esa utilidad
     * traslada la propia vista que se engancha; aquí, en cambio, hay que trasladar una ventana
     * distinta (la del iPod, que ni siquiera existe hasta que se empieza a arrastrar), así que hace
     * falta su propio manejador de toques.
     */
    private fun setupMiniPlayerDrag(miniPlayer: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var dragging = false
        var dragFragment: IPodNanoDialogFragment? = null

        miniPlayer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    dragging = false
                    dragFragment = null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    if (!dragging && dy < 0 && abs(dy) > touchSlop) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Se crea ya en el primer movimiento, escondida bajo la pantalla (fracción
                        // 0), para poder seguir el dedo desde el principio (ver
                        // [IPodNanoDialogFragment.forDrag]).
                        dragFragment = IPodNanoDialogFragment.forDrag().also {
                            it.show(supportFragmentManager, "ipod")
                            supportFragmentManager.executePendingTransactions()
                            it.followOpenDrag(0f)
                        }
                    }
                    if (dragging) {
                        val fraction = (-dy / resources.displayMetrics.heightPixels).coerceIn(0f, 1f)
                        dragFragment?.followOpenDrag(fraction)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val dy = event.rawY - startY
                        val fraction = (-dy / resources.displayMetrics.heightPixels).coerceIn(0f, 1f)
                        dragFragment?.finishOpenDrag(fraction > OPEN_COMMIT_FRACTION)
                        dragFragment = null
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * Tiñe con el color de la canción que suena todo lo que antes era amarillo fijo: el indicador y
     * el texto de la pestaña activa, la barra de progreso y los botones del mini-reproductor.
     *
     * El color lo calcula [PlayerViewModel] a partir de la carátula (ver
     * [com.untar.ultimusic.util.DynamicColor]); aquí solo se aplica. Mientras no suena
     * nada vale el amarillo de siempre, así que la app arranca igual que antes.
     */
    private fun setupDynamicColor() {
        val root = findViewById<View>(android.R.id.content)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val progress = findViewById<SeekBar>(R.id.songProgress)
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
                    progress.progressTintList = tint
                    AccentTint.icons(root, accent, R.id.btnPlayPause, R.id.btnExpand)
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
