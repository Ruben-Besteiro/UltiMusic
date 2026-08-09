package com.untar.ultimusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.model.AlbumSummary
import com.untar.ultimusic.model.GenreSummary
import com.untar.ultimusic.model.PersonSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Alimenta las cuatro pestañas de agrupación: Álbumes, Artistas, Productores y Géneros.
 *
 * Es el equivalente de [com.untar.ultimusic.ui.SongsViewModel] para esas pestañas, y como
 * él vive en el ámbito de la ACTIVIDAD (`by activityViewModels()`): así los cuatro fragmentos del
 * ViewPager comparten una única instancia y, sobre todo, las listas no se recalculan cada vez que
 * el usuario desliza entre pestañas y el ViewPager destruye y recrea los fragmentos.
 *
 * No hace ningún escaneo: eso lo dispara `SongsViewModel.loadIfNeeded()`. Aquí solo se observa la
 * base de datos, así que en cuanto la reconciliación o una edición cambian algo, las cuatro listas
 * se repintan solas.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    val albums: StateFlow<List<AlbumSummary>> = repository.albums
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<PersonSummary>> = repository.artists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gemelo de [artists]: productores y artistas se tratan exactamente igual. */
    val producers: StateFlow<List<PersonSummary>> = repository.producers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genres: StateFlow<List<GenreSummary>> = repository.genres
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
