package com.untarlamanteca.ultimusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.untarlamanteca.ultimusic.data.MusicRepository
import com.untarlamanteca.ultimusic.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Este archivo se encarga de almacenar la lista de canciones que leímos con el MusicRepository
 * Así sobreviven a cambios de orientación entre otras cosas
 * Actualmente es la única forma de almacenamiento de modelos que hay
 */

class SongsViewModel : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()        /** Esto es de solo lectura y es lo que exponemos **/

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private var loaded = false

    /** Escanea solo la primera vez (p. ej. al conceder el permiso) */
    fun loadIfNeeded() {
        if (loaded) return
        reload()
    }

    /** Escanea siempre */
    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            _songs.value = MusicRepository.scanSongs()
            loaded = true
            _loading.value = false
        }
    }
}
