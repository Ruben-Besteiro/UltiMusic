package com.untar.ultimusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Expone la lista de canciones observando la base de datos (vía [LibraryRepository]). Así las
 * ediciones se ven al instante y los datos sobreviven al cierre de la app. La reconciliación con
 * el filesystem (altas/bajas de archivos) se dispara con [loadIfNeeded]/[reload].
 */
class SongsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Solo lectura; es lo que exponemos a la UI. */
    val songs: StateFlow<List<Song>> = repository.songs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress = _progress.asStateFlow()

    private var reconciled = false

    /**
     * Reconciliación en curso, si la hay. `onCreate` y `onResume` llaman a [loadIfNeeded] casi a la
     * vez en el arranque en frío; sin este job, las dos llamadas pasan el check de [reconciled]
     * (que no se pone a `true` hasta que termina, y eso tarda segundos) y se lanzan dos escaneos
     * completos en paralelo.
     */
    private var reconcileJob: Job? = null

    /** Reconcilia solo la primera vez (p. ej. al conceder el permiso), y nunca si ya hay una en curso. */
    fun loadIfNeeded() {
        if (reconciled || reconcileJob != null) return
        reload()
    }

    /** Borra una canción de verdad (archivo y fila de la base de datos). */
    fun delete(song: Song) {
        viewModelScope.launch { repository.deleteSong(song) }
    }

    /** Reconcilia siempre (detecta archivos nuevos/borrados). Cancela cualquier reconciliación en curso. */
    fun reload() {
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            _loading.value = true
            _progress.value = 0
            runCatching {
                repository.reconcile { current, total ->
                    _progress.value = if (total > 0) (current * 100) / total else 0
                }
            }
            reconciled = true
            _loading.value = false
            reconcileJob = null
        }
    }
}
