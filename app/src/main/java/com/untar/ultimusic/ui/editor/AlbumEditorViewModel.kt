package com.untar.ultimusic.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado y guardado del editor de metadatos de un ÁLBUM. Gemelo de [MetadataEditorViewModel], pero
 * con los campos que tiene un álbum (título, artista(s), año, géneros y portada) en vez de los de
 * una canción suelta: sin letra, idioma, comentario, vídeo, posición en el álbum ni campos `og*`
 * (esos son de la canción, no del álbum entero).
 *
 * Vive en el ámbito del propio diálogo, así que se destruye al cerrarlo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Id del álbum en edición; lo fija el fragmento nada más crearse. */
    private val _albumId = MutableStateFlow(NO_ALBUM)

    /**
     * El álbum (fila cruda + sus artistas), sacado del mismo flujo reactivo que la ficha de detalle.
     * Es null hasta que Room emite la primera vez (por eso el formulario se rellena dentro de un
     * `collect`, y no en `onViewCreated`).
     */
    val album: StateFlow<Pair<AlbumEntity, List<String>>?> = _albumId
        .flatMapLatest { id -> if (id == NO_ALBUM) flowOf(null) else repository.albumEntityForEdit(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artistNames: StateFlow<List<String>> = repository.artistNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Se pone en no-null cuando el guardado termina, con la carátula final resultante, para que
     * el diálogo avise al usuario. Se vuelve a null con [consumeSaved] en cuanto reacciona. */
    private val _saved = MutableStateFlow<AlbumSaveResult?>(null)
    val saved = _saved.asStateFlow()

    fun consumeSaved() {
        _saved.value = null
    }

    fun setAlbumId(id: Long) {
        if (_albumId.value != NO_ALBUM) return
        _albumId.value = id
    }

    /**
     * Escribe todos los cambios. La imagen se importa AQUÍ (no al elegirla), igual que en el editor
     * de canciones, para no dejar archivos sueltos si el usuario cierra el editor sin guardar.
     *
     * Envuelto en [NonCancellable] por el mismo motivo que
     * [com.untar.ultimusic.ui.editor.MetadataEditorViewModel.save]: cerrar el editor justo después de
     * pulsar "Guardar" no debe cortar a medias la copia de la carátula ni la escritura en Room. Por
     * el mismo motivo se leen aquí dentro, y no en el diálogo, las canciones ya actualizadas del
     * álbum (ver [AlbumSaveResult.songs]): si esa lectura fuera en el Fragment, cerrar el diálogo
     * justo tras guardar podría cortarla a medias porque `viewLifecycleOwner.lifecycleScope` sí se
     * cancela con la vista, a diferencia de este `viewModelScope` protegido.
     */
    fun save(form: AlbumEditorForm, pickedImage: Uri?) {
        val (current, _) = album.value ?: return
        val titleChanged = form.title != current.title
        viewModelScope.launch {
            withContext(NonCancellable) {
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

                // tagTitle y tagAlbumArtist NO se tocan: son la clave con la que la reconciliación
                // reconoce este álbum al volver a escanear, y son independientes del título editable
                // (igual que el título de una canción no afecta a su ruta de archivo).
                val entity = AlbumEntity(
                    id = current.id,
                    title = form.title,
                    tagTitle = current.tagTitle,
                    tagAlbumArtist = current.tagAlbumArtist,
                    year = form.year,
                    genres = form.genres,
                    imageName = imageName
                )

                repository.saveAlbumEdits(entity, form.artists)
                // Ver CoverArt.revision: sin esto, una portada que reutiliza el nombre de archivo de
                // la anterior no cambiaría nada en el AlbumEntity que sale de Room.
                CoverArt.touch()
                // Ya reescritas: es la misma consulta de Room que rellena la ficha de detalle del
                // álbum, así que ya ve el título/artistas/año/portada recién guardados.
                val songs = repository.albumTracks(current.id).first().map { it.song }
                _saved.value = AlbumSaveResult(current.id, imageName, songs)
            }
        }
    }

    private companion object {
        const val NO_ALBUM = -1L
    }
}

/**
 * Resultado de un guardado, para que el diálogo pueda avisar al usuario y, además, refrescar en el
 * reproductor las canciones de este álbum que estén sonando o esperando en cola (ver
 * [AlbumEditorDialogFragment.refreshQueuedAlbumSongs]) — el mismo motivo por el que
 * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment] llama a
 * [com.untar.ultimusic.ui.PlayerViewModel.refreshSong] tras guardar una canción suelta, pero aquí
 * puede afectar a varias a la vez.
 */
data class AlbumSaveResult(val albumId: Long, val imageName: String?, val songs: List<Song>)

/** Los valores del formulario ya limpios (recortados, con los vacíos convertidos a null y los
 * campos multivalor partidos por comas). */
data class AlbumEditorForm(
    val title: String,
    val artists: List<String>,
    val year: Int?,
    val genres: List<String>
)
