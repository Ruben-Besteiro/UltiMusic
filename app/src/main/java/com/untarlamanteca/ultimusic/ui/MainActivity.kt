package com.untarlamanteca.ultimusic.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.LibraryRepository
import com.untarlamanteca.ultimusic.ui.player.IPodNanoDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // La cola de reproducción vive en PlayerViewModel (ámbito de actividad), compartida por el
    // mini-reproductor, SongsFragment y la ventana del iPod. Al pinchar una canción se inserta en
    // la posición 0 y la reproducción siempre mira la posición actual de la cola.

    private val songsViewModel: SongsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

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
     * termine aunque la Activity se destruya.
     */
    override fun onStop() {
        super.onStop()
        val repository = LibraryRepository.get(this)
        appScope.launch { repository.exportDatabaseCopy() }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        // De momento ningún botón de la barra superior hace nada.
        toolbar.setNavigationOnClickListener { }
        toolbar.setOnMenuItemClickListener { true }
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
        val title = findViewById<TextView>(R.id.miniTitle)
        val playPause = findViewById<ImageButton>(R.id.btnPlayPause)
        val expand = findViewById<ImageButton>(R.id.btnExpand)
        val progress = findViewById<SeekBar>(R.id.songProgress)

        playPause.setOnClickListener { playerViewModel.togglePlayPause() }
        // La flecha de expandir abre la ventana del iPod a pantalla completa.
        expand.setOnClickListener {
            IPodNanoDialogFragment().show(supportFragmentManager, "ipod")
        }

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
        } else if (!permissionDialogPending) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }
}
