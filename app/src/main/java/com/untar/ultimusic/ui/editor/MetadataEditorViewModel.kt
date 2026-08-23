package com.untar.ultimusic.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado y guardado del editor de metadatos de una canción.
 *
 * Vive en el ámbito del propio diálogo (no de la actividad), así que se destruye al cerrarlo. Su
 * trabajo es: encontrar la canción que se está editando dentro del flujo de la biblioteca, ofrecer
 * las listas de nombres para el autocompletado, y escribir los cambios en la base de datos.
 */
class MetadataEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Ids de las canciones en edición (una sola en el caso normal); los fija el fragmento nada
     *  más crearse (ver [setSongIds]). Más de uno = edición múltiple (ver
     *  [MetadataEditorDialogFragment.isMultiEdit]). */
    private val _songIds = MutableStateFlow<List<Long>>(emptyList())

    /**
     * Las canciones, sacadas del mismo flujo reactivo que la lista, en el mismo orden que
     * [_songIds]. Vacío hasta que Room emite la primera vez (por eso el formulario se rellena
     * dentro de un `collect` y no en `onViewCreated`).
     */
    val songs: StateFlow<List<Song>> = repository.songs
        .map { list ->
            val byId = list.associateBy { it.id }
            _songIds.value.mapNotNull { byId[it] }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Comodidad para el caso normal (una sola canción): la primera de [songs], o null.
     *
     * `Eagerly` y no `WhileSubscribed`: nadie colecta este flujo (solo se lee `.value`, en [save] y
     * en [MetadataEditorDialogFragment.openLyricsPicker]), así que con `WhileSubscribed` nunca
     * arrancaría a colectar [songs] y `.value` se quedaría en `null` para siempre -que es justo lo
     * que dejaba roto el botón de guardar en la edición de una sola canción. */
    val song: StateFlow<Song?> = songs
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val artistNames: StateFlow<List<String>> = repository.artistNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albumTitles: StateFlow<List<String>> = repository.albumTitles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val producerNames: StateFlow<List<String>> = repository.producerNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Se pone en no-null cuando el guardado termina, con la canción ya releída de Room (con todos
     * sus campos al día: carátula, letra, título...), para que el diálogo avise al usuario y pueda
     * refrescarla en [PlayerViewModel] si esta canción es la que suena — si no, lo editado no se
     * vería reflejado ahí hasta que sonara otra canción y volviera a esta (ver
     * [PlaybackService.refreshSong][com.untar.ultimusic.playback.PlaybackService.refreshSong]). Se
     * vuelve a null con [consumeSaved] en cuanto el diálogo reacciona. */
    private val _saved = MutableStateFlow<SaveResult?>(null)
    val saved = _saved.asStateFlow()

    /** Igual que [_saved] pero para el guardado múltiple (ver [saveMulti]): la lista completa de
     *  canciones ya releídas de Room. */
    private val _savedMulti = MutableStateFlow<List<Song>?>(null)
    val savedMulti = _savedMulti.asStateFlow()

    /** True mientras [save]/[saveMulti] están escribiendo (carátula, miniatura del vídeo, fila en
     * Room). El diálogo lo consulta al intentar salir para no dejar el guardado a medias: ver
     * [MetadataEditorDialogFragment.attemptClose]. */
    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    /** El diálogo ya ha reaccionado a [saved]: se vuelve a poner a null para que un guardado
     * posterior (el editor ya no se cierra solo) también dispare la colecta. */
    fun consumeSaved() {
        _saved.value = null
    }

    /** Igual que [consumeSaved] pero para [savedMulti]. */
    fun consumeSavedMulti() {
        _savedMulti.value = null
    }

    fun setSongIds(ids: List<Long>) {
        if (_songIds.value.isNotEmpty()) return
        _songIds.value = ids
    }

    /**
     * Escribe todos los cambios. La imagen se importa AQUÍ (no al elegirla) para no dejar archivos
     * sueltos en el almacenamiento si el usuario acaba cerrando el editor sin guardar. Lo mismo
     * para la miniatura de YouTube: se descarga aquí, solo si el enlace ha cambiado de verdad.
     *
     * Envuelto en [NonCancellable]: si el usuario pulsa "Guardar" y cierra el editor enseguida (antes
     * de que la copia de la carátula o la escritura en Room hayan terminado), `viewModelScope` se
     * cancela al destruirse este ViewModel -y con él, sin esto, la corrutina a medias-, dejando la
     * carátula sin importar o la fila sin actualizar aunque el usuario ya hubiera pulsado "Guardar".
     * Con [NonCancellable] el guardado sigue corriendo hasta el final aunque la pantalla ya se haya
     * cerrado.
     */
    fun save(form: EditorForm, pickedImage: Uri?) {
        val current = song.value ?: return
        // Se marca ANTES de lanzar la corrutina (no dentro): así, aunque el diálogo intente cerrarse
        // en el mismo instante en que se pulsa "Guardar", ya ve isSaving a true.
        _isSaving.value = true
        viewModelScope.launch {
            withContext(NonCancellable) {
                val result = writeSong(current, form, pickedImage)
                // Ver CoverArt.revision: sin esto, una carátula que reutiliza el nombre de archivo
                // de la anterior no cambiaría nada en el Song que sale de Room, y las listas de la
                // app no se enterarían de que hay una imagen nueva que cargar.
                CoverArt.touch()
                // Se relee de Room en vez de construirla a mano aquí: artistas/álbumes/productores
                // son relaciones que saveSongEdits acaba de resolver (nombre -> fila existente o
                // nueva), y reconstruir eso a mano duplicaría esa lógica. Por si acaso no apareciera
                // (no debería: se acaba de guardar), se cae de vuelta a un parche mínimo sobre lo
                // que ya había.
                val fresh = repository.songs.first().firstOrNull { it.id == current.id }
                    ?: current.copy(imageName = result.imageName, videoThumbnailName = result.thumbnailName)
                _saved.value = SaveResult(fresh)
                _isSaving.value = false
            }
        }
    }

    /**
     * Igual que [save] pero para varias canciones a la vez (edición múltiple, ver
     * [MetadataEditorDialogFragment.isMultiEdit]): [form] trae lo que hay AHORA MISMO en cada
     * campo del formulario, y [edited] qué campos ha tocado de verdad el usuario. Solo esos se
     * escriben en cada canción -mezclados con [Song.mergeUnedited]-; el resto se deja tal cual
     * estaba en ELLA (no en la primera de la selección, cada una con lo suyo), que es justo lo
     * que promete la etiqueta "Varios valores" del campo que no se ha tocado (ver
     * [MetadataEditorDialogFragment.fillMultiField]).
     *
     * [pickedImage] (si lo hay) es la única excepción: una portada elegida en esta sesión se aplica
     * a TODAS las canciones seleccionadas, igual que el resto de campos tocados.
     */
    fun saveMulti(form: EditorForm, edited: EditedFields, pickedImage: Uri?) {
        val targets = songs.value
        if (targets.isEmpty()) return
        _isSaving.value = true
        viewModelScope.launch {
            withContext(NonCancellable) {
                targets.forEach { current -> writeSong(current, current.mergeUnedited(form, edited), pickedImage) }
                CoverArt.touch()
                val ids = targets.map { it.id }.toSet()
                _savedMulti.value = repository.songs.first().filter { it.id in ids }
                _isSaving.value = false
            }
        }
    }

    /** Lo que devuelve [writeSong]: el nombre final de la carátula y de la miniatura del vídeo,
     *  para el parche de reserva de [save] si la relectura de Room no encontrara la fila (ver ahí). */
    private data class WriteResult(val imageName: String?, val thumbnailName: String?)

    /**
     * Escribe TODOS los cambios de una canción: la carátula (o su renombrado si solo cambia el
     * título), la miniatura de YouTube, y la fila de Room con sus relaciones de álbum/artistas/
     * productores. Es el núcleo de [save] y, en bucle sobre cada canción seleccionada, de
     * [saveMulti].
     *
     * La imagen se importa AQUÍ (no al elegirla) para no dejar archivos sueltos en el
     * almacenamiento si el usuario acaba cerrando el editor sin guardar. Lo mismo para la
     * miniatura de YouTube: se descarga aquí, solo si el enlace ha cambiado de verdad.
     *
     * La llama siempre [save]/[saveMulti], envueltos en [NonCancellable]: si el usuario pulsa
     * "Guardar" y cierra el editor enseguida (antes de que la copia de la carátula o la escritura
     * en Room hayan terminado), `viewModelScope` se cancela al destruirse este ViewModel -y con él,
     * sin esto, la corrutina a medias-, dejando la carátula sin importar o la fila sin actualizar
     * aunque el usuario ya hubiera pulsado "Guardar". Con [NonCancellable] el guardado sigue
     * corriendo hasta el final aunque la pantalla ya se haya cerrado.
     */
    private suspend fun writeSong(current: Song, form: EditorForm, pickedImage: Uri?): WriteResult {
        val titleChanged = form.title != current.title
        val videoUrlChanged = form.videoUrl != current.videoUrl

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
            videoUrlChanged -> {
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
            // El álbum lo resuelve saveSongEdits a partir de albumTitle; este valor es
            // provisional y se sobrescribe ahí.
            albumId = current.album?.id,
            trackNumber = form.trackNumber,
            discNumber = form.discNumber,
            ogTitle = form.ogTitle,
            ogArtist = form.ogArtist,
            ogAlbum = form.ogAlbum,
            ogYear = form.ogYear,
            // El editor no toca las visitas de YouTube (las pone solo el refresco diario, ver
            // LibraryRepository.refreshYouTubeStatsIfDue): sin esto, cada edición de metadatos
            // las borraría de vuelta a null hasta el siguiente refresco.
            youtubeViewCount = current.youtubeViewCount
        )

        repository.saveSongEdits(
            song = entity,
            artistNames = form.artists,
            albumTitle = form.album,
            producerNames = form.producers
        )
        // El vídeo ha cambiado de verdad (uno nuevo, o distinto del que tenía antes): sin esto,
        // sus visitas se quedarían en null (o en las del vídeo viejo) hasta el refresco diario,
        // aunque el usuario acabe de asignarlo — ver el javadoc de refreshYouTubeStatsForSong
        // sobre por qué esta es la única edición que se salta el tope de una vez al día.
        if (videoUrlChanged && form.videoUrl != null) {
            repository.refreshYouTubeStatsForSong(current.id, form.videoUrl)
        }
        return WriteResult(imageName, thumbnailName)
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
    val album: String?,
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

/**
 * Qué campos del formulario ha tocado de verdad el usuario en una edición múltiple (ver
 * [MetadataEditorDialogFragment.buildEditedFields]): son los únicos que [Song.mergeUnedited]
 * aplica a cada canción seleccionada. Uno a uno con los campos de [EditorForm].
 */
data class EditedFields(
    val title: Boolean,
    val album: Boolean,
    val artists: Boolean,
    val producers: Boolean,
    val year: Boolean,
    val genres: Boolean,
    val lyrics: Boolean,
    val language: Boolean,
    val comment: Boolean,
    val videoUrl: Boolean,
    val videoOffsetMs: Boolean,
    val lyricsOffsetMs: Boolean,
    val trackNumber: Boolean,
    val discNumber: Boolean,
    val ogTitle: Boolean,
    val ogArtist: Boolean,
    val ogAlbum: Boolean,
    val ogYear: Boolean
)

/**
 * Para una edición múltiple: mezcla [form] (lo que hay ahora en el formulario) con `this` (los
 * valores propios de la canción), quedándose con lo del formulario SOLO en los campos que diga
 * [edited] y con lo propio en el resto. Así cada canción conserva lo suyo en cualquier campo que
 * se quedara en "Varios valores" sin que el usuario lo tocara (ver [MetadataEditorViewModel.saveMulti]).
 */
private fun Song.mergeUnedited(form: EditorForm, edited: EditedFields): EditorForm = EditorForm(
    title = if (edited.title) form.title else title,
    album = if (edited.album) form.album else album?.title,
    artists = if (edited.artists) form.artists else artists.map { it.name },
    producers = if (edited.producers) form.producers else producers.map { it.name },
    year = if (edited.year) form.year else year,
    genres = if (edited.genres) form.genres else genres,
    lyrics = if (edited.lyrics) form.lyrics else lyrics,
    language = if (edited.language) form.language else language,
    comment = if (edited.comment) form.comment else comment,
    videoUrl = if (edited.videoUrl) form.videoUrl else videoUrl,
    videoOffsetMs = if (edited.videoOffsetMs) form.videoOffsetMs else videoOffsetMs,
    lyricsOffsetMs = if (edited.lyricsOffsetMs) form.lyricsOffsetMs else lyricsOffsetMs,
    trackNumber = if (edited.trackNumber) form.trackNumber else trackNumber,
    discNumber = if (edited.discNumber) form.discNumber else discNumber,
    ogTitle = if (edited.ogTitle) form.ogTitle else ogTitle,
    ogArtist = if (edited.ogArtist) form.ogArtist else ogArtist,
    ogAlbum = if (edited.ogAlbum) form.ogAlbum else ogAlbum,
    ogYear = if (edited.ogYear) form.ogYear else ogYear
)
