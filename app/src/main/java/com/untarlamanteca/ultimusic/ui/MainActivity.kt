package com.untarlamanteca.ultimusic.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ImageButton
import android.widget.ProgressBar
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
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.untarlamanteca.ultimusic.R
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val songsViewModel: SongsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    private val tabTitles: List<String> by lazy {
        listOf(
            getString(R.string.tab_songs),
            getString(R.string.tab_albums),
            getString(R.string.tab_artists),
            getString(R.string.tab_genres),
            getString(R.string.tab_playlists)
        )
    }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            loadIfPermitted()
        }

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) songsViewModel.loadIfNeeded()
            else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }

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
        ensureStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes, si ya hay permiso, cargamos.
        if (hasStoragePermission()) songsViewModel.loadIfNeeded()
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
        val progress = findViewById<ProgressBar>(R.id.songProgress)

        playPause.setOnClickListener { playerViewModel.togglePlayPause() }
        // La flecha de expandir no hace nada de momento.
        expand.setOnClickListener { }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playerViewModel.currentSong.collect { song ->
                        title.text = song?.title ?: getString(R.string.nothing_playing)
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
                        progress.progress =
                            if (p.durationMs > 0) ((p.positionMs * 1000) / p.durationMs).toInt()
                            else 0
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

    private fun loadIfPermitted() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureStoragePermission() {
        if (hasStoragePermission()) {
            songsViewModel.loadIfNeeded()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(R.string.permission_needed_message)
            .setPositiveButton(R.string.permission_grant) { _, _ -> requestStoragePermission() }
            .setNegativeButton(R.string.permission_cancel, null)
            .show()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
