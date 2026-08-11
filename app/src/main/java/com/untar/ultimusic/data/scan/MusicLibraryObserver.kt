package com.untar.ultimusic.data.scan

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Vigila la fonoteca para detectar altas/bajas de canciones sin que el usuario tenga que reabrir
 * la app. `FileObserver` NUNCA es recursivo: solo avisa de cambios en el directorio exacto que se
 * le pasa, no en sus subcarpetas. Como el usuario organiza su música en subcarpetas (ver
 * [com.untar.ultimusic.data.db.entities.GreylistFolderEntity]), aquí se mantiene un `FileObserver`
 * por cada subcarpeta de la fonoteca, y se añaden/quitan vigilantes según se crean o borran
 * subcarpetas nuevas. Sin esto, una canción descargada dentro de una subcarpeta nunca dispara la
 * reconciliación y solo aparecería tras el siguiente arranque en frío de la app.
 */
class MusicLibraryObserver(
    private val root: File,
    private val scope: CoroutineScope,
    private val onChangesDetected: suspend () -> Unit
) {
    /** Un `DirWatcher` por cada carpeta vigilada, indexado por su ruta absoluta. */
    private val watchers = mutableMapOf<String, DirWatcher>()
    private var debounceJob: Job? = null

    fun startWatching() {
        watchRecursively(root)
    }

    fun stopWatching() {
        debounceJob?.cancel()
        watchers.values.forEach { it.stopWatching() }
        watchers.clear()
    }

    /** Añade un vigilante para [dir] y, recursivamente, para todas sus subcarpetas ya existentes. */
    private fun watchRecursively(dir: File) {
        val key = dir.absolutePath
        if (watchers.containsKey(key)) return
        watchers[key] = DirWatcher(dir).apply { startWatching() }
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) watchRecursively(child)
        }
    }

    /** Quita el vigilante de [dir] y el de cualquier subcarpeta suya que siguiera vigilada. Devuelve si había alguno. */
    private fun unwatch(dir: File): Boolean {
        val prefix = dir.absolutePath + File.separator
        val nested = watchers.keys.filter { it == dir.absolutePath || it.startsWith(prefix) }
        nested.forEach { watchers.remove(it)?.stopWatching() }
        return nested.isNotEmpty()
    }

    private fun scheduleReconcile() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            onChangesDetected()
        }
    }

    /** Vigilante de UNA carpeta (no recursivo por sí mismo; la recursividad la orquesta [MusicLibraryObserver]). */
    private inner class DirWatcher(private val dir: File) :
        FileObserver(dir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {

        override fun onEvent(event: Int, path: String?) {
            if (path == null) return
            val child = File(dir, path)

            if (child.isDirectory) {
                // Carpeta recién aparecida (creada o movida aquí): puede traer canciones ya
                // dentro, así que hay que vigilarla Y reconciliar, no solo lo primero.
                watchRecursively(child)
                scheduleReconcile()
                return
            }

            // El archivo (o carpeta) ya no existe: si teníamos vigilantes para esa ruta, era una
            // carpeta con canciones dentro y hay que reconciliar para darlas de baja.
            val wasWatchedDir = unwatch(child)
            val isAudioFile = AUDIO_EXTENSIONS.any { ext -> path.lowercase().endsWith(".$ext") }
            if (isAudioFile || wasWatchedDir) scheduleReconcile()
        }
    }

    companion object {
        private const val DEBOUNCE_DELAY_MS = 2000L
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mkv"
        )
    }
}
