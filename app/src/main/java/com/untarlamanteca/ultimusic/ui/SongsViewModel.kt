package com.untarlamanteca.ultimusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untarlamanteca.ultimusic.data.LibraryRepository
import com.untarlamanteca.ultimusic.model.Song
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

    private var reconciled = false

    /** Reconcilia solo la primera vez (p. ej. al conceder el permiso). */
    fun loadIfNeeded() {
        if (reconciled) return
        reload()
    }

    /** Reconcilia siempre (detecta archivos nuevos/borrados). */
    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repository.reconcile() }
            reconciled = true
            _loading.value = false
        }
    }
}
