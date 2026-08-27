package com.untar.ultimusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.SortPreferences
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.SystemTagKey
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortOption
import com.untar.ultimusic.util.TextSearch
import com.untar.ultimusic.util.sortedByOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Alimenta la pestaña de Etiquetas. Vive en el ámbito de la ACTIVIDAD (`by activityViewModels()`),
 * igual que [LibraryViewModel], pero es un ViewModel APARTE en vez de una cuarta pestaña ahí: la
 * etiqueta predefinida "En ninguna lista" necesita saber qué canciones están en alguna Lista, un
 * dato que vive en [com.untar.ultimusic.data.playlist.PlaylistRepository] (archivos `.txt`, ajeno a
 * la base de datos). Meter esa dependencia en [LibraryViewModel] la acoplaría a Listas para siempre,
 * afectando también a Álbumes/Artistas/Géneros, que no la necesitan.
 *
 * Como [com.untar.ultimusic.data.LibraryRepository] tampoco debe depender de
 * [com.untar.ultimusic.data.playlist.PlaylistRepository] directamente (rompería la reactividad
 * instantánea: los `.txt` no reemiten solos), el conjunto de filenames-en-alguna-lista se recibe
 * como un "bind" externo (ver [bindPlaylistFilenames]), mismo patrón que
 * [com.untar.ultimusic.ui.collection.CollectionDetailViewModel.bindPlaylistSongs]. Quien conecta
 * este bind de verdad al `PlaylistsViewModel` compartido de la actividad es
 * [com.untar.ultimusic.ui.MainActivity.onCreate] — no un fragmento concreto: este ViewModel se
 * observa hoy desde Canciones, un álbum/artista, una lista, el buscador y el iPod (para editar las
 * etiquetas de una canción), además de la propia pestaña Etiquetas, así que el bind tiene que estar
 * hecho ANTES de que exista cualquiera de ellos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository.get(app)

    /** Ver la clase. Se fija una sola vez, antes de que [tags]/[songsOfTag] se observen. */
    private val playlistFilenamesSource = MutableStateFlow<(() -> StateFlow<Set<String>>)?>(null)
    fun bindPlaylistFilenames(source: () -> StateFlow<Set<String>>) {
        playlistFilenamesSource.value = source
    }

    private val filenamesInAnyPlaylist: Flow<Set<String>> =
        playlistFilenamesSource.flatMapLatest { it?.invoke() ?: flowOf(emptySet()) }

    private val _sort = MutableStateFlow(SortPreferences.get(LibraryTab.TAGS))
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    /** Etiquetas que NUNCA tienen membresía real (ver [SystemTagKey]): no se pueden "quitar" de una
     *  canción (no hay fila que borrar) ni "añadir" (no hay fila que insertar, se derivan solas). */
    private val computedKeys = setOf(
        SystemTagKey.RECENTLY_ADDED.name, SystemTagKey.NOT_IN_PLAYLIST.name, SystemTagKey.NO_CUSTOM_TAGS.name
    )

    /**
     * True si [tag] se ve en la pestaña Etiquetas aunque tenga 0 canciones. Solo dos casos:
     * - Una personalizada del usuario (`systemKey == null && !isAutoAssigned`): si se ocultara vacía,
     *   una etiqueta recién creada desaparecería de la lista antes de poder añadirle la primera
     *   canción, justo lo contrario de lo que se busca al crearla.
     * - Favoritos: la única predefinida que se deja ver vacía a propósito, como recordatorio
     *   permanente de que existe aunque el usuario todavía no haya marcado ninguna.
     *
     * El resto -las 3 calculadas ([computedKeys]), Vídeo sincronizado, Remix / Cover y las de idioma
     * ([TagSummary.isAutoAssigned])- se ocultan en cuanto se quedan sin ninguna canción: no aportan
     * nada mientras estén vacías y el usuario no puede "prepararlas" de antemano como sí puede con una
     * personalizada (confirmado con el usuario: solo Favoritos debe verse con 0 elementos).
     */
    private fun TagSummary.showsWhenEmpty(): Boolean =
        (systemKey == null && !isAutoAssigned) || systemKey == SystemTagKey.FAVORITES.name

    /** Ver [showsWhenEmpty]: SIN filtrar por 0 canciones. Se deja tal cual, sin recortar, porque
     *  [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment] la usa para resolver por id
     *  la etiqueta que está viendo AHORA MISMO (título, botón "+"/X) -si se filtrara aquí, una
     *  predefinida se esfumaría de golpe de su propia ficha en cuanto se quedara sin canciones,
     *  justo mientras el usuario la está mirando-. La pestaña Etiquetas (TagsFragment) NO pinta esta
     *  lista a secas, pinta [visibleTags]. */
    val tags: StateFlow<List<TagSummary>> =
        combine(repository.tagSummaries(filenamesInAnyPlaylist), _sort) { list, option ->
            list.sortedByOption(option)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [tags] sin las predefinidas/automáticas vacías (ver [showsWhenEmpty]): esto es lo que pinta de
     *  verdad la pestaña Etiquetas (TagsFragment), tanto la lista como las letras de la barra de
     *  scroll. */
    val visibleTags: StateFlow<List<TagSummary>> =
        tags.map { list -> list.filter { it.songCount > 0 || it.showsWhenEmpty() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Etiquetas que se le pueden AÑADIR a una canción (ver `TagPickerDialogFragment`): todas menos
     *  las 3 calculadas y las de idioma (`isAutoAssigned`, ver el javadoc de [isEditable]: se
     *  gestionan solas desde el editor de metadatos, no a mano desde aquí). */
    val assignableTags: Flow<List<TagSummary>> =
        repository.tagSummaries(filenamesInAnyPlaylist)
            .map { list -> list.filterNot { it.systemKey in computedKeys || it.isAutoAssigned } }

    fun setSort(option: SortOption) {
        _sort.value = option
        SortPreferences.save(LibraryTab.TAGS, option)
    }

    /** Canciones de UNA etiqueta, por [id] (ver la decisión de diseño de usar el id como clave de
     *  una etiqueta en la ficha de detalle, en vez del nombre). */
    fun songsOfTag(id: Long): Flow<List<Song>> = repository.songsOfTag(id, filenamesInAnyPlaylist)

    /** Etiquetas de UNA canción (ver `SongTagsDialogFragment`), incluidas las calculadas que cumpla
     *  ahora mismo. */
    fun tagsOfSong(songId: Long): Flow<List<TagSummary>> = repository.tagsOfSong(songId, filenamesInAnyPlaylist)

    /**
     * True si [tag] admite añadir/quitar canciones a mano: una etiqueta personalizada (`systemKey ==
     * null`) o una predefinida de membresía real (Favoritos, Vídeo sincronizado, Remix / Cover). False
     * para las 3 calculadas ([computedKeys]), que no tienen fila en `song_tag` que insertar/borrar, y
     * para una de idioma (`tag.isAutoAssigned`, ver [com.untar.ultimusic.data.db.entities.TagEntity.isAutoAssigned]):
     * ESA sí tiene fila real en `song_tag`, pero la mantiene sola
     * [com.untar.ultimusic.data.LibraryRepository.syncLanguageTag] desde el editor de metadatos, no se
     * toca a mano desde el selector de etiquetas.
     *
     * La usa [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment] para decidir si
     * enseña el botón "+" de su ficha (ver [AddSongsToTagDialogFragment]) y la X de quitar de cada
     * fila. `tag.systemKey !in computedKeys` cubre los dos casos de las predefinidas de golpe: `null`
     * nunca está en un `Set<String>`, así que una personalizada ya sale `true` sin hacer falta un `||`
     * aparte; `!tag.isAutoAssigned` es el `||` que sí hace falta para las de idioma.
     */
    fun isEditable(tag: TagSummary): Boolean = tag.systemKey !in computedKeys && !tag.isAutoAssigned

    /**
     * Canciones que TODAVÍA no tienen la etiqueta [id], filtradas por [query] (buscador de
     * [AddSongsToTagDialogFragment]): con la caja de búsqueda vacía salen todas las que le falten.
     * Mismo filtrado sin tildes/mayúsculas que el resto de buscadores de la app (ver [TextSearch]).
     */
    fun songsNotInTag(id: Long, query: Flow<String>): Flow<List<Song>> =
        combine(repository.songs, songsOfTag(id), query) { all, tagged, rawQuery ->
            val taggedIds = tagged.mapTo(mutableSetOf()) { it.id }
            val normalized = TextSearch.normalize(rawQuery)
            all.filter { it.id !in taggedIds && TextSearch.contains(it.title, normalized) }
        }

    /**
     * Etiquetas de TODAS las canciones a la vez, indexadas por id (ver
     * `LibraryRepository.songTagsById`): la usa la pestaña Canciones para la tercera línea de cada
     * fila (ver `SongsAdapter`).
     *
     * Filtradas con [isEditable]: las que el usuario NO puede tocar a mano -las 3 calculadas
     * ([computedKeys]) y las de idioma ([TagSummary.isAutoAssigned])- no se pintan aquí, aunque la
     * canción sí las tenga. Favoritos, Vídeo sincronizado y Remix / Cover SÍ se pintan: tienen
     * membresía real y el usuario también puede tocarlas a mano, igual que [isEditable] ya decide para
     * el botón "+"/X de la ficha de una etiqueta (ver [CollectionDetailDialogFragment][com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment]).
     */
    val songTagsById: Flow<Map<Long, List<TagSummary>>> =
        repository.songTagsById(filenamesInAnyPlaylist)
            .map { byId -> byId.mapValues { (_, tags) -> tags.filter(::isEditable) } }

    /** Ver `LibraryRepository.addSongToTag`/`removeSongFromTag`: solo tiene efecto para etiquetas sin
     *  calcular, la UI ya no deja llegar aquí para las 3 calculadas. */
    fun addSongToTag(songId: Long, tagId: Long) {
        viewModelScope.launch { repository.addSongToTag(songId, tagId) }
    }

    fun removeSongFromTag(songId: Long, tagId: Long) {
        viewModelScope.launch { repository.removeSongFromTag(songId, tagId) }
    }

    /** Ver `LibraryRepository.applyTagsToSongs`: edición múltiple de etiquetas (varias canciones
     *  seleccionadas a la vez, ver `SongTagsDialogFragment` en modo múltiple). */
    fun applyTagsToSongs(songIds: List<Long>, addTagIds: Set<Long>, removeTagIds: Set<Long>) {
        viewModelScope.launch { repository.applyTagsToSongs(songIds, addTagIds, removeTagIds) }
    }

    /** Ver `LibraryRepository.createTag`/`updateTag`/`deleteTag`: CRUD de la etiqueta EN SÍ, para el
     *  editor de etiquetas personalizadas (botón "+" de la pestaña Etiquetas y menú de 3 puntos de
     *  cada fila, ver TagsFragment/TagsAdapter). */
    fun createTag(name: String, colorArgb: Int) {
        viewModelScope.launch { repository.createTag(name, colorArgb) }
    }

    fun updateTag(id: Long, name: String, colorArgb: Int) {
        viewModelScope.launch { repository.updateTag(id, name, colorArgb) }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch { repository.deleteTag(id) }
    }
}
