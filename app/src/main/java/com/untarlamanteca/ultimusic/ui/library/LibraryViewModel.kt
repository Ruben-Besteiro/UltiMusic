package com.untarlamanteca.ultimusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untarlamanteca.ultimusic.data.LibraryRepository
import com.untarlamanteca.ultimusic.model.AlbumSummary
import com.untarlamanteca.ultimusic.model.PersonSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Alimenta las tres pestañas de agrupación: Álbumes, Artistas y Productores.
 *
 * Es el equivalente de [com.untarlamanteca.ultimusic.ui.SongsViewModel] para esas pestañas, y como
 * él vive en el ámbito de la ACTIVIDAD (`by activityViewModels()`): así los tres fragmentos del
 * ViewPager comparten una única instancia y, sobre todo, las listas no se recalculan cada vez que
 * el usuario desliza entre pestañas y el ViewPager destruye y recrea los fragmentos.
 *
 * No hace ningún escaneo: eso lo dispara `SongsViewModel.loadIfNeeded()`. Aquí solo se observa la
 * base de datos, así que en cuanto la reconciliación o una edición cambian algo, las tres listas se
 * repintan solas.
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
}
