package com.untar.ultimusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.SortPreferences
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortOption
import com.untar.ultimusic.util.sortedByOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Expone la lista de canciones observando la base de datos (vía [LibraryRepository]). Así las
 * ediciones se ven al instante y los datos sobreviven al cierre de la app. La reconciliación con
 * el filesystem (altas/bajas de archivos) se dispara con [loadIfNeeded]/[reload].
 */
class SongsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Criterio de orden de la pestaña, persistido (ver [SortPreferences]) y aplicado en Kotlin sobre
     *  lo que ya llega ordenado por SQL, porque Room no admite un `ORDER BY` dinámico. Lo cambia
     *  [setSort], que abre [com.untar.ultimusic.ui.sort.SortDialogFragment]. */
    private val _sort = MutableStateFlow(SortPreferences.get(LibraryTab.SONGS))
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    /** Solo lectura; es lo que exponemos a la UI, ya reordenada. */
    val songs: StateFlow<List<Song>> = combine(repository.songs, _sort) { list, option ->
        list.sortedByOption(option)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(option: SortOption) {
        _sort.value = option
        SortPreferences.save(LibraryTab.SONGS, option)
    }

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress = _progress.asStateFlow()

    /**
     * Ids de las canciones marcadas en la pestaña Canciones (pulsación larga; ver [SongsAdapter] y
     * [SongsFragment]). No vacío = selección múltiple activa, que es lo que [MainActivity] mira
     * para sustituir el ojo de ordenar de la toolbar por un menú de 3 puntos (ver su
     * `setupToolbar`). Vive aquí -no en un estado propio del fragmento- porque `MainActivity` y
     * `SongsFragment` comparten esta MISMA instancia (`by activityViewModels()` / `by viewModels()`
     * de ámbito de actividad), que es justo lo que hace falta para que la toolbar (de la actividad)
     * se entere de lo que pasa en la lista (del fragmento).
     */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** Empieza una selección con una sola canción marcada (pulsación larga sobre ella). */
    fun startSelection(songId: Long) {
        _selectedIds.value = setOf(songId)
    }

    /** Marca o desmarca una canción; si se queda sin ninguna, la selección múltiple termina sola. */
    fun toggleSelection(songId: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(songId)) remove(songId)
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

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

    /** Igual que [delete] pero para varias a la vez (borrado desde la selección múltiple). */
    fun delete(songs: List<Song>) {
        viewModelScope.launch { songs.forEach { repository.deleteSong(it) } }
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
            // Al terminar cada reconciliación, no solo la primera: es la señal más parecida a
            // "abrir la aplicación" que hay, y refreshYouTubeStatsIfDue ya se encarga de no repetir
            // el refresco más de una vez al día por su cuenta.
            repository.refreshYouTubeStatsIfDue()
            reconciled = true
            _loading.value = false
            reconcileJob = null
        }
    }
}
