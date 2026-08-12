package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.ApiUnauthorizedException
import com.untar.ultimusic.data.remote.GeniusApi
import com.untar.ultimusic.data.remote.ItunesApi
import com.untar.ultimusic.data.remote.retryInSeconds
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * Diálogo de sugerencias del autorrelleno de metadatos: busca en iTunes (a través de
 * [MetadataSuggestionsViewModel]) y deja al usuario elegir un candidato, que se devuelve a quien
 * abrió el diálogo — [MetadataEditorDialogFragment] o [AlbumEditorDialogFragment] — para que
 * rellene sus campos. Nunca guarda nada por su cuenta: solo entrega datos.
 *
 * Mismo patrón que [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]: un [DialogFragment]
 * a pantalla completa que devuelve su resultado con la API de resultados entre fragmentos
 * (`setFragmentResult`/`setFragmentResultListener`), la forma recomendada de que dos fragmentos se
 * comuniquen sin que uno guarde una referencia directa al otro (esa referencia se quedaría
 * colgando si el sistema recrea la ventana, por ejemplo al girar la pantalla).
 *
 * El resultado viaja en un [Bundle] de solo tipos primitivos (texto y números) en vez de mandar el
 * propio [MetadataSuggestion]: el proyecto no tiene instalado el plugin `kotlin-parcelize`
 * (necesario para poder meter una `data class` en un `Bundle` tal cual), y no compensa añadirlo
 * solo para este resultado.
 */
class MetadataSuggestionsDialogFragment : DialogFragment() {

    private val viewModel: MetadataSuggestionsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Si se buscan canciones o álbumes. Decide qué se busca en iTunes y si se le pregunta a
     * Genius al elegir (ver [MetadataSuggestionsViewModel.enrich]). */
    private val kind: SuggestionKind by lazy {
        SuggestionKind.valueOf(requireArguments().getString(ARG_KIND)!!)
    }

    /** Evita devolver dos veces el resultado si el usuario llega a tocar dos filas en el mismo
     * instante, antes de que el diálogo termine de cerrarse. */
    private var resultSent = false

    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var messageGroup: View
    private lateinit var message: TextView
    private lateinit var retryButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_metadata_suggestions, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.suggestionsRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.suggestionsToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        list = view.findViewById(R.id.suggestionsList)
        loading = view.findViewById(R.id.suggestionsLoading)
        messageGroup = view.findViewById(R.id.suggestionsMessageGroup)
        message = view.findViewById(R.id.suggestionsMessage)
        retryButton = view.findViewById(R.id.suggestionsRetry)

        // Genius solo entra en juego en sugerencias de CANCIÓN y con token utilizable; en cualquier
        // otro caso la carátula es la de iTunes y se sabe ya, sin esperar a nada (ver la cabecera
        // del adaptador y MetadataSuggestionsViewModel.coverArtOverride).
        val adapter = MetadataSuggestionsAdapter(
            usesGenius = kind == SuggestionKind.SONG && viewModel.geniusAvailable,
            scope = viewLifecycleOwner.lifecycleScope,
            coverOverride = { suggestion -> viewModel.coverArtOverride(suggestion) },
            onPicked = { onSuggestionPicked(it) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        retryButton.setOnClickListener { viewModel.retry() }

        // Scroll infinito: al acercarse a las últimas filas (mientras se baja, dy > 0) se pide la
        // página siguiente. loadMore() ya se protege solo contra llamadas repetidas (mira
        // hasMore/loadingMore antes de pedir nada), así que no hace falta ningún guard aquí.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val itemCount = recyclerView.adapter?.itemCount ?: 0
                if (lastVisible >= itemCount - LOAD_MORE_THRESHOLD) viewModel.loadMore()
            }
        })

        viewModel.search(
            kind,
            requireArguments().getString(ARG_PRIMARY_QUERY).orEmpty(),
            requireArguments().getString(ARG_SECONDARY_QUERY).orEmpty()
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> render(state, adapter) }
                }
                // El spinner de carga y el botón de "Reintentar" salen por defecto de
                // colorPrimary (amarillo fijo, ver themes.xml); la regla del proyecto es que
                // todo lo amarillo use el color dinámico, así que se tiñen igual que
                // SongsFragment.loadingSpinner / los botones filled del resto de la app (ver
                // AccentTint.kt).
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        loading.indeterminateTintList = ColorStateList.valueOf(accent)
                        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
                        adapter.setAccent(accent)
                    }
                }
            }
        }
    }

    private fun render(state: SuggestionsUiState, adapter: MetadataSuggestionsAdapter) {
        loading.isVisible = state is SuggestionsUiState.Loading
        list.isVisible = state is SuggestionsUiState.Success
        messageGroup.isVisible = state is SuggestionsUiState.Empty ||
            state is SuggestionsUiState.Error ||
            state is SuggestionsUiState.RateLimited
        // Sin botón de reintentar con un rate limit: reintentar es justo lo que no hay que hacer, y
        // el freno lo denegaría igualmente sin llegar a salir a la red (ver RateLimitGuard).
        retryButton.isVisible = state is SuggestionsUiState.Error

        when (state) {
            is SuggestionsUiState.Success -> adapter.submit(state.suggestions, state.loadingMore)
            is SuggestionsUiState.Empty -> message.setText(R.string.suggestions_empty)
            is SuggestionsUiState.Error -> message.setText(R.string.suggestions_error)
            is SuggestionsUiState.RateLimited ->
                message.text = getString(R.string.suggestions_rate_limited, state.retryInSeconds)
            SuggestionsUiState.Loading -> Unit
        }
    }

    /**
     * Al elegir una fila se le pide a Genius lo que iTunes no sabe de esa canción (ver
     * [MetadataSuggestionsViewModel.enrich]) y SOLO ENTONCES se abre [showOverwritePicker], para que
     * el usuario decida qué campos de todo lo recopilado quiere aplicar. Mientras tanto se tapa la
     * lista con el spinner, porque son dos peticiones y se nota.
     *
     * Si Genius no está configurado o no encuentra la canción, `enrich` devuelve null y lo que se le
     * ofrece al usuario es exactamente lo que daría iTunes por su cuenta: este paso solo puede añadir
     * campos, nunca quitar ni empeorar los que ya había.
     *
     * De Genius solo se avisa de los dos fallos ante los que el usuario puede hacer algo (ver
     * [com.untar.ultimusic.data.remote.GeniusApi.detailsFor]), y **ninguno de los dos interrumpe la
     * entrega**: el candidato que eligió se sigue ofreciendo igual con lo que dio iTunes, porque su
     * elección no tiene la culpa de que Genius falle.
     * - **Rate limit**: un aviso pasajero ([android.widget.Toast]) diciendo cuánto esperar.
     * - **Token inválido**: no cabe en un Toast (hay que crear otro API Client), así que en vez de
     *   avisar aquí se marca [RESULT_GENIUS_TOKEN_INVALID] en el resultado y es el editor quien
     *   encadena los diálogos DESPUÉS de aplicar la sugerencia. Para cuando esto pasa, `GeniusApi` ya
     *   ha borrado el token guardado.
     */
    private fun onSuggestionPicked(suggestion: MetadataSuggestion) {
        if (resultSent) return
        resultSent = true
        loading.isVisible = true
        list.isVisible = false
        messageGroup.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            var tokenInvalid = false
            val details = try {
                viewModel.enrich(kind, suggestion)
            } catch (limited: ApiRateLimitException) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.suggestions_genius_rate_limited, limited.retryInSeconds()),
                    Toast.LENGTH_LONG
                ).show()
                null
            } catch (unauthorized: ApiUnauthorizedException) {
                tokenInvalid = true
                null
            }
            // Se destapa la lista antes de abrir el diálogo de casillas: si el usuario cancela ahí,
            // vuelve a ver la lista de sugerencias tal cual la dejó, en vez de un spinner colgado.
            loading.isVisible = false
            list.isVisible = true
            showOverwritePicker(suggestion, details, tokenInvalid)
        }
    }

    /** Un campo ofrecido en [showOverwritePicker]: su etiqueta, si la sugerencia trajo algo que
     * enseñar y qué hay que vaciar en el bundle de salida si el usuario lo desmarca. */
    private class OverwriteField(val label: String, val present: Boolean, val clear: (Bundle) -> Unit)

    /**
     * Diálogo "Elige qué quieres sobrescribir": una casilla por cada campo que la sugerencia haya
     * traído de verdad (las que no trajeron nada ni se enseñan, no hay nada que ofrecer sobrescribir
     * con ellas), todas marcadas por defecto. Solo al confirmar se manda [RESULT_KEY] con los campos
     * desmarcados vaciados —lo que para [MetadataEditorDialogFragment.applySuggestion]/
     * [AlbumEditorDialogFragment.applySuggestion] significa "no toques esto"—, y solo entonces se
     * cierra este diálogo entero. Al cancelar no se manda nada y se libera [resultSent] para poder
     * elegir otra fila.
     *
     * Los cuatro campos `og*` (canción original de un remix) viajan bajo UNA sola casilla: Genius los
     * da siempre los cuatro juntos o ninguno (ver [GeniusApi.SongDetails.ogTitle]), así que separarlos
     * no tendría sentido.
     */
    private fun showOverwritePicker(
        suggestion: MetadataSuggestion,
        details: GeniusApi.SongDetails?,
        tokenInvalid: Boolean
    ) {
        val title = suggestion.title
        val album = suggestion.albumTitle.orEmpty()
        val artists = artistsFor(suggestion, details)
        val genres = suggestion.genre.orEmpty()
        val year = suggestion.year ?: NO_VALUE
        val trackNumber = suggestion.trackNumber ?: NO_VALUE
        val discNumber = suggestion.discNumber ?: NO_VALUE
        val coverUrl = coverUrlFor(suggestion, details).orEmpty()
        val producers = details?.producers?.joinToString(", ").orEmpty()
        val videoUrl = details?.videoUrl.orEmpty()
        val ogTitle = details?.ogTitle.orEmpty()
        val ogArtist = details?.ogArtist.orEmpty()
        val ogAlbum = details?.ogAlbum.orEmpty()
        val ogYear = details?.ogYear ?: NO_VALUE

        val fullResult = bundleOf(
            RESULT_TITLE to title,
            RESULT_ARTIST to artists,
            RESULT_ALBUM to album,
            RESULT_YEAR to year,
            RESULT_GENRES to genres,
            RESULT_TRACK_NUMBER to trackNumber,
            RESULT_DISC_NUMBER to discNumber,
            RESULT_COVER_URL to coverUrl,
            RESULT_PRODUCERS to producers,
            RESULT_VIDEO_URL to videoUrl,
            RESULT_OG_TITLE to ogTitle,
            RESULT_OG_ARTIST to ogArtist,
            RESULT_OG_ALBUM to ogAlbum,
            RESULT_OG_YEAR to ogYear,
            RESULT_GENIUS_TOKEN_INVALID to tokenInvalid
        )

        // Las etiquetas son las mismas que ya rotulan cada campo en los editores (ver el bloque
        // field_* de strings.xml): álbum es "Álbum(es)" porque es EL MISMO campo multivalor que
        // inputAlbums, no un campo nuevo con nombre propio. El título es distinto según el tipo de
        // ficha, porque así lo es también en cada editor (canción vs. álbum).
        //
        // Pista, disco, productores, vídeo y og* no existen en el editor de ÁLBUM (ver el javadoc de
        // AlbumEditorDialogFragment.applySuggestion), así que no se ofrecen ahí; en la práctica ya
        // llegarían vacíos (enrich() no llama a Genius fuera de SONG), pero omitir la casilla entera
        // es más claro que enseñar una que nunca puede traer nada.
        val titleLabel = if (kind == SuggestionKind.SONG) R.string.field_title else R.string.field_album_title
        val fields = buildList {
            add(OverwriteField(getString(titleLabel), title.isNotEmpty()) {
                it.putString(RESULT_TITLE, "")
            })
            if (kind == SuggestionKind.SONG) {
                add(OverwriteField(getString(R.string.field_albums), album.isNotEmpty()) {
                    it.putString(RESULT_ALBUM, "")
                })
            }
            add(OverwriteField(getString(R.string.field_artists), artists.isNotEmpty()) {
                it.putString(RESULT_ARTIST, "")
            })
            add(OverwriteField(getString(R.string.field_year), year > 0) {
                it.putInt(RESULT_YEAR, NO_VALUE)
            })
            add(OverwriteField(getString(R.string.field_genres), genres.isNotEmpty()) {
                it.putString(RESULT_GENRES, "")
            })
            if (kind == SuggestionKind.SONG) {
                add(OverwriteField(getString(R.string.field_track_number), trackNumber > 0) {
                    it.putInt(RESULT_TRACK_NUMBER, NO_VALUE)
                })
                add(OverwriteField(getString(R.string.field_disc_number), discNumber > 0) {
                    it.putInt(RESULT_DISC_NUMBER, NO_VALUE)
                })
            }
            add(OverwriteField(getString(R.string.overwrite_field_cover), coverUrl.isNotEmpty()) {
                it.putString(RESULT_COVER_URL, "")
            })
            if (kind == SuggestionKind.SONG) {
                add(OverwriteField(getString(R.string.field_producers), producers.isNotEmpty()) {
                    it.putString(RESULT_PRODUCERS, "")
                })
                add(OverwriteField(getString(R.string.field_video_url), videoUrl.isNotEmpty()) {
                    it.putString(RESULT_VIDEO_URL, "")
                })
                add(OverwriteField(getString(R.string.overwrite_field_original_song), ogTitle.isNotEmpty()) {
                    it.putString(RESULT_OG_TITLE, "")
                    it.putString(RESULT_OG_ARTIST, "")
                    it.putString(RESULT_OG_ALBUM, "")
                    it.putInt(RESULT_OG_YEAR, NO_VALUE)
                })
            }
        }.filter { it.present }

        val content = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_suggestion_overwrite, null, false)
        val fieldsContainer = content.findViewById<LinearLayout>(R.id.overwriteFields)
        val toggleAll = content.findViewById<TextView>(R.id.overwriteToggleAll)
        val missingFields = content.findViewById<TextView>(R.id.overwriteMissingFields)

        val checkboxes = fields.map { field ->
            (LayoutInflater.from(requireContext())
                .inflate(R.layout.item_overwrite_checkbox, fieldsContainer, false) as MaterialCheckBox)
                .apply { text = field.label }
                .also { fieldsContainer.addView(it) }
        }

        fun updateToggleAllText() {
            val anyChecked = checkboxes.any { it.isChecked }
            toggleAll.setText(if (anyChecked) R.string.overwrite_uncheck_all else R.string.overwrite_check_all)
        }
        checkboxes.forEach { it.setOnCheckedChangeListener { _, _ -> updateToggleAllText() } }
        updateToggleAllText()
        toggleAll.setOnClickListener {
            // Si no hay ninguna marcada la próxima acción es marcarlas todas; en cualquier otro
            // caso (todas o solo alguna) es desmarcarlas todas, como dice el propio texto del enlace.
            val checkAll = checkboxes.none { it.isChecked }
            checkboxes.forEach { it.isChecked = checkAll }
        }

        // Solo en una sugerencia de CANCIÓN sin nada de Genius (sin token, sin conexión, o Genius no
        // la tiene): ahí es donde de verdad faltan campos que pedir, y la carátula cae de vuelta a la
        // del álbum (ver coverUrlFor). Que Genius SÍ responda pero esta canción en concreto no tenga
        // productor, vídeo o no sea un remix es normal (ver los campos og* en CLAUDE.md) y no un fallo
        // que avisar aquí; por eso el disparador es "no hay `details`", no "falta este campo suelto".
        if (kind == SuggestionKind.SONG && details == null) {
            val missingLabels = listOf(
                getString(R.string.field_producers),
                getString(R.string.field_video_url),
                getString(R.string.overwrite_field_original_song)
            )
            val joined = missingLabels.dropLast(1).joinToString(", ") + " y " + missingLabels.last()
            missingFields.text = getString(R.string.overwrite_missing_fields, joined)
            missingFields.isVisible = true
        } else {
            missingFields.isVisible = false
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.overwrite_picker_title)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> resultSent = false }
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val outgoing = Bundle(fullResult)
                fields.forEachIndexed { index, field ->
                    if (!checkboxes[index].isChecked) field.clear(outgoing)
                }
                setFragmentResult(RESULT_KEY, outgoing)
                dismiss()
            }
            .setOnCancelListener { resultSent = false }
            .create()

        // Mismo amarillo dinámico que el resto del diálogo (spinner, botón de reintentar): ver el
        // `collect` de accentColor en onViewCreated.
        val accent = playerViewModel.accentColor.value
        val tint = ColorStateList.valueOf(accent)
        checkboxes.forEach { it.buttonTintList = tint }
        toggleAll.setTextColor(accent)
        dialog.setOnShowListener { AccentTint.buttons(dialog, accent) }
        dialog.show()
    }

    /**
     * Los artistas acreditados, separados por comas como espera el campo multivalor del editor.
     *
     * Se prefiere SIEMPRE la lista de Genius, aunque el resto de campos vengan de iTunes: Genius
     * los da uno a uno ([GeniusApi.SongDetails.artists]), mientras que iTunes acredita una única
     * cadena con todos pegados ("The Weeknd & ROSALÍA") que no hay forma de partir sin romper a los
     * grupos que llevan "&" en su propio nombre. Como el editor sustituye este campo por lo que
     * llegue aquí, esa diferencia decide si acaban tres artistas bien separados o un artista
     * inventado con el nombre de dos.
     *
     * Sin Genius (sin token, o canción que no tiene) se manda la cadena de iTunes tal cual: es lo
     * único que hay, y el usuario puede separarla a mano en el formulario antes de guardar.
     */
    private fun artistsFor(
        suggestion: MetadataSuggestion,
        details: GeniusApi.SongDetails?
    ): String {
        val fromGenius = details?.artists.orEmpty()
        return if (fromGenius.isNotEmpty()) fromGenius.joinToString(", ") else suggestion.artist
    }

    /**
     * Qué carátula se lleva el editor, ya en la resolución final (lista para descargarla tal cual,
     * sin que quien la reciba tenga que saber de qué fuente vino).
     *
     * La regla es simple y es **la misma que aplica la lista** (ver
     * [MetadataSuggestionsViewModel.coverArtOverride], que es quien pinta las filas): con token de
     * Genius manda la carátula de Genius, que es la de la canción concreta y no la del disco entero;
     * sin token —o si Genius no tiene arte propio para esa canción— manda la de iTunes, que siempre
     * está.
     *
     * Que las dos funciones coincidan no es cosmético: si discreparan, el usuario elegiría una fila
     * viendo una carátula y el editor acabaría rellenado con otra distinta.
     */
    private fun coverUrlFor(
        suggestion: MetadataSuggestion,
        details: GeniusApi.SongDetails?
    ): String? {
        val itunesCover = suggestion.coverUrl?.let { ItunesApi.fullResCoverUrl(it) }
        return details?.songArtUrl ?: itunesCover
    }

    companion object {
        /** Filas antes del final desde las que ya se pide la página siguiente, para que llegue a
         * tiempo (cargarla de golpe) antes de que el usuario alcance el final de verdad y vea un
         * hueco en blanco esperando la respuesta de iTunes. */
        private const val LOAD_MORE_THRESHOLD = 5

        /** Clave con la que el editor escucha el resultado. */
        const val RESULT_KEY = "metadata_suggestions_result"
        const val RESULT_TITLE = "title"
        const val RESULT_ARTIST = "artist"
        const val RESULT_ALBUM = "album"
        const val RESULT_YEAR = "year"

        /** El género del candidato, listo para volcarlo tal cual en el `EditText` de géneros.
         * iTunes solo da uno por publicación, así que aquí nunca hay varios que unir con el ", "
         * de los campos multivalor de los editores — la clave conserva el nombre en plural porque
         * el campo del editor sí admite varios, escritos a mano. */
        const val RESULT_GENRES = "genres"
        const val RESULT_TRACK_NUMBER = "trackNumber"
        const val RESULT_DISC_NUMBER = "discNumber"

        /** URL de la carátula **ya en resolución final**: quien la recibe solo tiene que
         * descargarla. Se resuelve aquí, y no en el editor, porque el tamaño se pide de forma
         * distinta según de qué fuente venga y solo este diálogo sabe cuál ganó (ver
         * [coverUrlFor]). */
        const val RESULT_COVER_URL = "coverUrl"

        /** Los cuatro campos que aporta Genius y que iTunes no conoce (ver [GeniusApi]). Llegan
         * vacíos, o con [NO_VALUE] en el año, cuando no hay token configurado, cuando Genius no
         * tiene la canción, o en una sugerencia de álbum (donde no se le pregunta). */
        const val RESULT_PRODUCERS = "producers"
        const val RESULT_VIDEO_URL = "videoUrl"
        const val RESULT_OG_TITLE = "ogTitle"
        const val RESULT_OG_ARTIST = "ogArtist"
        const val RESULT_OG_ALBUM = "ogAlbum"
        const val RESULT_OG_YEAR = "ogYear"

        /**
         * `true` si al enriquecer este candidato Genius rechazó el token por inválido. No es un dato
         * de la sugerencia: viaja aquí para que el editor pueda aplicar primero lo que sí se ha
         * conseguido y avisar después (ver [onSuggestionPicked] y `MetadataEditorDialogFragment`).
         */
        const val RESULT_GENIUS_TOKEN_INVALID = "geniusTokenInvalid"

        /** Centinela de "sin valor" para año/pista/disco en el [Bundle] del resultado: los reales
         * son siempre positivos, así que no hay ambigüedad posible. */
        const val NO_VALUE = -1

        private const val ARG_KIND = "kind"
        private const val ARG_PRIMARY_QUERY = "primaryQuery"
        private const val ARG_SECONDARY_QUERY = "secondaryQuery"

        /**
         * [primaryQuery]/[secondaryQuery] son título+artista para [SuggestionKind.SONG], o
         * álbum+artista para [SuggestionKind.ALBUM].
         */
        fun newInstance(
            kind: SuggestionKind,
            primaryQuery: String,
            secondaryQuery: String
        ) = MetadataSuggestionsDialogFragment().apply {
            arguments = bundleOf(
                ARG_KIND to kind.name,
                ARG_PRIMARY_QUERY to primaryQuery,
                ARG_SECONDARY_QUERY to secondaryQuery
            )
        }
    }
}
