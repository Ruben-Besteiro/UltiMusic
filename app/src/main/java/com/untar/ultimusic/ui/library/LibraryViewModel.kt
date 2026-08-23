package com.untar.ultimusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.SortPreferences
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.GenreSummary
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortOption
import com.untar.ultimusic.util.sortedByOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Alimenta las tres pestañas de agrupación: Álbumes, Artistas y Géneros.
 *
 * Es el equivalente de [com.untar.ultimusic.ui.SongsViewModel] para esas pestañas, y como
 * él vive en el ámbito de la ACTIVIDAD (`by activityViewModels()`): así los tres fragmentos del
 * ViewPager comparten una única instancia y, sobre todo, las listas no se recalculan cada vez que
 * el usuario desliza entre pestañas y el ViewPager destruye y recrea los fragmentos.
 *
 * No hace ningún escaneo: eso lo dispara `SongsViewModel.loadIfNeeded()`. Aquí solo se observa la
 * base de datos, así que en cuanto la reconciliación o una edición cambian algo, las tres listas
 * se repintan solas.
 *
 * Cada lista lleva su PROPIO criterio de orden (ver [com.untar.ultimusic.ui.sort.SortDialogFragment]):
 * tres [MutableStateFlow] independientes, uno por pestaña, persistidos en [SortPreferences] y
 * combinados con el flujo de datos correspondiente antes de exponerlo.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    private val _albumsSort = MutableStateFlow(SortPreferences.get(LibraryTab.ALBUMS))
    val albumsSort: StateFlow<SortOption> = _albumsSort.asStateFlow()

    private val _artistsSort = MutableStateFlow(SortPreferences.get(LibraryTab.ARTISTS))
    val artistsSort: StateFlow<SortOption> = _artistsSort.asStateFlow()

    private val _genresSort = MutableStateFlow(SortPreferences.get(LibraryTab.GENRES))
    val genresSort: StateFlow<SortOption> = _genresSort.asStateFlow()

    val albums: StateFlow<List<AlbumSummary>> = combine(repository.albums, _albumsSort) { list, option ->
        list.sortedByOption(option)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<PersonSummary>> = combine(repository.artists, _artistsSort) { list, option ->
        list.sortedByOption(option)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genres: StateFlow<List<GenreSummary>> = combine(repository.genres, _genresSort) { list, option ->
        list.sortedByOption(option)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(tab: LibraryTab, option: SortOption) {
        when (tab) {
            LibraryTab.ALBUMS -> _albumsSort.value = option
            LibraryTab.ARTISTS -> _artistsSort.value = option
            LibraryTab.GENRES -> _genresSort.value = option
            LibraryTab.SONGS, LibraryTab.TAGS, LibraryTab.PLAYLISTS ->
                throw IllegalArgumentException("$tab no lo maneja LibraryViewModel")
        }
        SortPreferences.save(tab, option)
    }
}
