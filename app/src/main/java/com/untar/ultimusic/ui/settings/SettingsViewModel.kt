package com.untar.ultimusic.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.ArtistGroupingPreferences
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.model.LibraryRoot
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado de los "ajustes visuales" para [SettingsDialogFragment]: las carpetas raíz de la fonoteca y
 * la lista gris. Va aparte de [com.untar.ultimusic.ui.PlayerViewModel] (que es sobre audio/
 * reproducción, los "ajustes auditivos") porque esto es sobre la biblioteca/carátulas, y sigue el
 * mismo patrón que [com.untar.ultimusic.ui.library.LibraryViewModel]: envuelve [LibraryRepository] y
 * expone sus flujos como [StateFlow].
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

    // --- Carpetas raíz de la fonoteca (ajustes > Carpetas de la fonoteca) ---

    val libraryRoots: StateFlow<List<LibraryRoot>> = repository.libraryRoots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Por qué no se pudo añadir una carpeta raíz nueva, para que la UI elija el aviso adecuado. */
    enum class AddLibraryRootResult { SUCCESS, ALREADY_COVERED }

    /**
     * Valida y añade una carpeta raíz nueva, ya concedida con el selector del sistema
     * (`ActivityResultContracts.OpenDocumentTree`, ver [SettingsDialogFragment]). Rechaza:
     * - una carpeta ya cubierta por una raíz existente (la propia `UltiMusic`, o cualquiera de las
     *   guardadas en BD), o una subcarpeta suya: añadirla duplicaría escaneo/vigilancia sin ningún
     *   beneficio;
     * - una carpeta que ya CONTENGA una raíz existente: el anidamiento en el otro sentido tiene el
     *   mismo problema.
     *
     * La comparación es por docPath (ver [SafStorage.docPathOfTree]), no por el `Uri` en sí: dos
     * `Uri` de árbol distintos pueden apuntar a la misma carpeta real si el usuario la concede dos
     * veces desde el selector.
     */
    fun tryAddLibraryRoot(uri: Uri): AddLibraryRootResult {
        val app = getApplication<Application>()
        val candidate = SafStorage.docPathOfTree(uri)
        if (candidate != null) {
            val existing = libraryRoots.value.mapNotNull { SafStorage.docPathOfTree(Uri.parse(it.path)) } +
                listOfNotNull(SafStorage.ultiMusicDocPath(app))
            fun isSameOrNested(a: String, b: String) = a == b || a.startsWith("$b/")
            if (existing.any { isSameOrNested(candidate, it) || isSameOrNested(it, candidate) }) {
                return AddLibraryRootResult.ALREADY_COVERED
            }
        }

        viewModelScope.launch { repository.addLibraryRoot(uri) }
        return AddLibraryRootResult.SUCCESS
    }

    fun removeLibraryRoot(uri: Uri) {
        viewModelScope.launch { repository.removeLibraryRoot(uri) }
    }

    // --- Agrupar artistas pequeños en "Otros" (ver ArtistGroupingPreferences) ---

    val artistGroupingThreshold: StateFlow<Int> = ArtistGroupingPreferences.threshold

    /** Guarda el umbral nuevo. Repository.artists reacciona al instante -[artistGroupingThreshold]
     *  es el mismo `StateFlow` que consulta, no hace falta recargar nada aquí. */
    fun setArtistGroupingThreshold(value: Int) {
        ArtistGroupingPreferences.set(value)
    }
}
