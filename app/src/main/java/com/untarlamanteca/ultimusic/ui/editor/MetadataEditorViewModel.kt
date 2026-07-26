package com.untarlamanteca.ultimusic.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untarlamanteca.ultimusic.data.LibraryRepository
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado y guardado del editor de metadatos de una canción.
 *
 * Vive en el ámbito del propio diálogo (no de la actividad), así que se destruye al cerrarlo. Su
 * trabajo es: encontrar la canción que se está editando dentro del flujo de la biblioteca, ofrecer
 * las listas de nombres para el autocompletado, y escribir los cambios en la base de datos.
 */
class MetadataEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Id de la canción en edición; lo fija el fragmento nada más crearse. */
    private val _songId = MutableStateFlow(NO_SONG)

    /**
     * La canción, sacada del mismo flujo reactivo que la lista. Es null hasta que Room emite la
     * primera vez (por eso el formulario se rellena dentro de un `collect` y no en `onViewCreated`).
     */
    val song: StateFlow<Song?> = repository.songs
        .map { list -> list.firstOrNull { it.id == _songId.value } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artistNames: StateFlow<List<String>> = repository.artistNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albumTitles: StateFlow<List<String>> = repository.albumTitles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val producerNames: StateFlow<List<String>> = repository.producerNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pista y disco actuales (viven en la tabla de cruce; se leen una vez al abrir). */
    private val _trackAndDisc = MutableStateFlow<Pair<Int?, Int?>?>(null)
    val trackAndDisc = _trackAndDisc.asStateFlow()

    /** Se pone a true cuando el guardado termina, para que el diálogo se cierre. */
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    fun setSongId(id: Long) {
        if (_songId.value != NO_SONG) return
        _songId.value = id
        viewModelScope.launch { _trackAndDisc.value = repository.trackAndDisc(id) }
    }

    /**
     * Escribe todos los cambios. La imagen se importa AQUÍ (no al elegirla) para no dejar archivos
     * sueltos en el almacenamiento si el usuario acaba cerrando el editor sin guardar.
     */
    fun save(form: EditorForm, pickedImage: Uri?) {
        val current = song.value ?: return
        viewModelScope.launch {
            val imageName = if (pickedImage != null) {
                runCatching { repository.importCoverImage(pickedImage, current.id) }
                    .onSuccess { current.imageName?.let { old -> repository.deleteCoverImage(old) } }
                    .getOrElse { current.imageName }
            } else {
                current.imageName
            }

            val entity = SongEntity(
                id = current.id,
                filePath = current.filePath,
                title = form.title,
                duration = current.duration,
                year = form.year,
                genres = form.genres,
                lyrics = form.lyrics,
                language = form.language,
                imageName = imageName,
                comment = form.comment,
                ogTitle = form.ogTitle,
                ogArtist = form.ogArtist,
                ogAlbum = form.ogAlbum,
                ogYear = form.ogYear
            )

            repository.saveSongEdits(
                song = entity,
                artistNames = form.artists,
                albumTitles = form.albums,
                producerNames = form.producers,
                trackNumber = form.trackNumber,
                discNumber = form.discNumber
            )
            _saved.value = true
        }
    }

    private companion object {
        const val NO_SONG = -1L
    }
}

/**
 * Los valores del formulario ya limpios (recortados, con los vacíos convertidos a null y los campos
 * multivalor partidos por comas). Sirve para que el ViewModel no tenga que saber nada de EditTexts.
 */
data class EditorForm(
    val title: String,
    val albums: List<String>,
    val artists: List<String>,
    val producers: List<String>,
    val year: Int?,
    val genres: List<String>,
    val lyrics: String?,
    val language: String?,
    val comment: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
