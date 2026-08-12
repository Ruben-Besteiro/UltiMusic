package com.untar.ultimusic.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.model.GreylistFolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado de los "ajustes visuales" para [SettingsDialogFragment]: la lista gris. Va aparte de
 * [com.untar.ultimusic.ui.PlayerViewModel] (que es sobre audio/reproducción, los "ajustes
 * auditivos") porque esto es sobre la biblioteca/carátulas, y sigue el mismo patrón que
 * [com.untar.ultimusic.ui.library.LibraryViewModel]: envuelve [LibraryRepository] y expone sus
 * flujos como [StateFlow].
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    val greylistFolders: StateFlow<List<GreylistFolder>> = repository.greylistFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addGreylistFolder(path: String) {
        viewModelScope.launch { repository.addGreylistFolder(path) }
    }

    fun removeGreylistFolder(path: String) {
        viewModelScope.launch { repository.removeGreylistFolder(path) }
    }

    fun setGreylistFolderExcluded(path: String, excluded: Boolean) {
        viewModelScope.launch { repository.setGreylistFolderExcluded(path, excluded) }
    }
}
