package com.untarlamanteca.ultimusic.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.untarlamanteca.ultimusic.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Progreso de reproducción de la canción actual. */
data class PlaybackProgress(val positionMs: Long = 0L, val durationMs: Long = 0L)

/**
 * Posee el [ExoPlayer] y expone el estado de reproducción. Con ámbito de actividad, lo
 * comparten el mini-reproductor de la actividad y el fragmento de canciones.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress = _progress.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    _progress.value = PlaybackProgress(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.takeIf { it > 0 } ?: 0L
                    )
                }
                delay(500)
            }
        }
    }

    /** Reproduce la canción indicada desde el principio. */
    fun play(song: Song) {
        _currentSong.value = song
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(song.filePath))))
        player.prepare()
        player.play()
    }

    /** Reproduce una canción al azar de la lista dada. */
    fun playRandom(songs: List<Song>) {
        if (songs.isNotEmpty()) play(songs.random())
    }

    /** Alterna play/pausa. Si no hay nada cargado, no hace nada. */
    fun togglePlayPause() {
        if (_currentSong.value == null) return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onCleared() {
        player.release()
    }
}
