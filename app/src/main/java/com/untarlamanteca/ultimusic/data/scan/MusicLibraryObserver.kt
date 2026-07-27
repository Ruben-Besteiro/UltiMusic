package com.untarlamanteca.ultimusic.data.scan

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MusicLibraryObserver(
    private val watchPath: File,
    private val scope: CoroutineScope,
    private val onChangesDetected: suspend () -> Unit
) : FileObserver(watchPath, CREATE or DELETE or MOVED_FROM or MOVED_TO) {

    private var debounceJob: Job? = null
    private val DEBOUNCE_DELAY_MS = 2000L

    override fun onEvent(event: Int, path: String?) {
        if (path == null) return

        val isAudioFile = AUDIO_EXTENSIONS.any { ext ->
            path.lowercase().endsWith(".$ext")
        }
        if (!isAudioFile) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            onChangesDetected()
        }
    }

    override fun startWatching() {
        super.startWatching()
    }

    override fun stopWatching() {
        debounceJob?.cancel()
        super.stopWatching()
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mkv"
        )
    }
}
