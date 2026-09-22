package com.untar.ultimusic.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de selección múltiple por pulsación larga: ids marcados, no vacío = selección activa.
 * Nació en la pestaña Canciones (ver [com.untar.ultimusic.ui.SongsViewModel]) y lo reutiliza
 * cualquier otra lista de canciones de la aplicación (ficha de álbum/artista, ficha de
 * lista/género/etiqueta, cola del iPod) para no repetir la misma máquina de estados en cada
 * ViewModel.
 */
class SongSelection {
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** Empieza una selección con un solo id marcado (pulsación larga sobre él). */
    fun start(id: Long) {
        _selectedIds.value = setOf(id)
    }

    /** Marca o desmarca un id; si se queda sin ninguno, la selección múltiple termina sola. */
    fun toggle(id: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun clear() {
        _selectedIds.value = emptySet()
    }
}
