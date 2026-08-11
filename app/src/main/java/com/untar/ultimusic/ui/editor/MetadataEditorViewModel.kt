package com.untar.ultimusic.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** Se pone en no-null cuando el guardado termina, con la canción ya releída de Room (con todos
     * sus campos al día: carátula, letra, título...), para que el diálogo avise al usuario y pueda
     * refrescarla en [PlayerViewModel] si esta canción es la que suena — si no, lo editado no se
     * vería reflejado ahí hasta que sonara otra canción y volviera a esta (ver
     * [PlaybackService.refreshSong][com.untar.ultimusic.playback.PlaybackService.refreshSong]). Se
     * vuelve a null con [consumeSaved] en cuanto el diálogo reacciona. */
    private val _saved = MutableStateFlow<SaveResult?>(null)
    val saved = _saved.asStateFlow()

    /** El diálogo ya ha reaccionado a [saved]: se vuelve a poner a null para que un guardado
     * posterior (el editor ya no se cierra solo) también dispare la colecta. */
    fun consumeSaved() {
        _saved.value = null
    }

    fun setSongId(id: Long) {
        if (_songId.value != NO_SONG) return
        _songId.value = id
        viewModelScope.launch { _trackAndDisc.value = repository.trackAndDisc(id) }
    }

    /**
     * Escribe todos los cambios. La imagen se importa AQUÍ (no al elegirla) para no dejar archivos
     * sueltos en el almacenamiento si el usuario acaba cerrando el editor sin guardar. Lo mismo
     * para la miniatura de YouTube: se descarga aquí, solo si el enlace ha cambiado de verdad.
     */
    fun save(form: EditorForm, pickedImage: Uri?) {
        val current = song.value ?: return
        val titleChanged = form.title != current.title
        viewModelScope.launch {
            val imageName = when {
                pickedImage != null -> {
                    current.imageName?.let { old -> repository.deleteCoverImage(old) }
                    runCatching { repository.importCoverImage(pickedImage, form.title) }
                        .getOrElse { current.imageName }
                }
                current.imageName != null && titleChanged ->
                    repository.renameImage(current.imageName, form.title, "", "img")
                else -> current.imageName
            }

            var thumbnailName = current.videoThumbnailName
            when {
                form.videoUrl == null -> {
                    current.videoThumbnailName?.let { repository.deleteVideoThumbnail(it) }
                    thumbnailName = null
                }
                form.videoUrl != current.videoUrl -> {
                    current.videoThumbnailName?.let { repository.deleteVideoThumbnail(it) }
                    val videoId = YouTubeUrl.videoId(form.videoUrl)
                    thumbnailName = videoId?.let { repository.downloadVideoThumbnail(it, form.title) }
                }
                current.videoThumbnailName != null && titleChanged ->
                    thumbnailName =
                        repository.renameImage(current.videoThumbnailName, form.title, " (video)", "jpg")
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
                videoUrl = form.videoUrl,
                videoThumbnailName = thumbnailName,
                videoOffsetMs = form.videoOffsetMs,
                lyricsOffsetMs = form.lyricsOffsetMs,
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
            // Se relee de Room en vez de construirla a mano aquí: artistas/álbumes/productores son
            // relaciones que saveSongEdits acaba de resolver (nombre -> fila existente o nueva), y
            // reconstruir eso a mano duplicaría esa lógica. Por si acaso no apareciera (no debería:
            // se acaba de guardar), se cae de vuelta a un parche mínimo sobre lo que ya había.
            val fresh = repository.songs.first().firstOrNull { it.id == current.id }
                ?: current.copy(imageName = imageName, videoThumbnailName = thumbnailName)
            _saved.value = SaveResult(fresh)
        }
    }

    private companion object {
        const val NO_SONG = -1L
    }
}

/** Canción ya guardada y releída de Room, para que quien observe [MetadataEditorViewModel.saved]
 * pueda refrescarla entera en el reproductor si esta canción es la que suena. */
data class SaveResult(val song: Song)

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
    val videoUrl: String?,
    val videoOffsetMs: Long,
    val lyricsOffsetMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val ogTitle: String?,
    val ogArtist: String?,
    val ogAlbum: String?,
    val ogYear: Int?
)
