package com.untar.ultimusic.ui.editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.GeniusTokenStore
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.ValueRuler
import com.untar.ultimusic.ui.player.VideoPickerDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.LanguageDetector
import com.untar.ultimusic.util.NetworkImage
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.launch

/**
 * Editor de metadatos de una canción, a pantalla completa.
 *
 * Es un [DialogFragment] (no una Activity) por el mismo motivo que la ventana del iPod: así se abre
 * y se cierra sin salir de la pantalla principal y comparte el ciclo de vida del fragmento que lo
 * lanza. Con [STYLE_NO_FRAME] y `setLayout(MATCH_PARENT, MATCH_PARENT)` deja de parecer un diálogo
 * flotante y ocupa toda la pantalla.
 *
 * Todos los campos están siempre visibles, agrupados bajo cabeceras ("Detalles", "Posición en el
 * álbum", "Grabación original"). Lo que se escribe aquí se guarda en Room, de modo que persiste
 * aunque se cierre la aplicación y el escaneo del disco no lo pisa.
 *
 * Se abre siempre igual, ocupando toda la pantalla, sea cual sea el sitio desde el que se lanza
 * (Canciones, una ficha de álbum/artista/productor, el buscador o el iPod).
 */
class MetadataEditorDialogFragment : DialogFragment() {

    private val viewModel: MetadataEditorViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Imagen elegida en esta sesión de edición; no se copia a disco hasta que se guarda. */
    private var pickedImage: Uri? = null

    /** El formulario solo se rellena con la primera emisión: si no, escribir sería imposible. */
    private var formLoaded = false

    /** Se activa mientras [fillForm] escribe los campos, para que ese volcado inicial no se
     * confunda con un cambio del usuario y marque el formulario como sucio. */
    private var isFillingForm = false

    /** True en cuanto el usuario cambia algo (texto o portada). Controla si al intentar salir
     * (ver [attemptClose]) hace falta pedir confirmación. */
    private var isDirty = false

    private var accentColor: Int = 0

    /** Se activa mientras se sincronizan entre sí cada regla de desplazamiento y su caja de texto
     * (ver [setupOffsetControls]: vale tanto para vídeo como para letra), para que ese repintado
     * no se confunda con un cambio del usuario y entre en bucle (mismo patrón que
     * `IPodDialogFragment.openVideoSettings` usaba antes de mudarse aquí). */
    private var updatingOffsetUi = false

    private lateinit var inputTitle: EditText
    private lateinit var inputAlbums: MaterialAutoCompleteTextView
    private lateinit var inputArtists: MaterialAutoCompleteTextView
    private lateinit var inputProducers: MaterialAutoCompleteTextView
    private lateinit var inputYear: EditText
    private lateinit var inputGenres: EditText
    private lateinit var inputLyrics: EditText
    private lateinit var btnClearLyrics: ImageButton
    private lateinit var lyricsOffsetRuler: ValueRuler
    private lateinit var lyricsOffsetValue: EditText
    private lateinit var inputLanguage: EditText
    private lateinit var inputComment: EditText
    private lateinit var inputVideoUrl: EditText
    private lateinit var btnClearVideoUrl: ImageButton
    private lateinit var offsetRuler: ValueRuler
    private lateinit var offsetValue: EditText
    private lateinit var inputTrackNumber: EditText
    private lateinit var inputDiscNumber: EditText
    private lateinit var inputOgTitle: EditText
    private lateinit var inputOgArtist: EditText
    private lateinit var inputOgAlbum: EditText
    private lateinit var inputOgYear: EditText
    private lateinit var cover: ImageView
    private lateinit var btnPickCover: ImageButton
    private lateinit var fabAutofill: FloatingActionButton

    /**
     * Selector de fotos del sistema. Es el moderno ([ActivityResultContracts.PickVisualMedia]): no
     * exige ningún permiso porque el usuario elige la foto en una pantalla del propio sistema y solo
     * nos devuelve esa.
     */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedImage = uri
            cover.load(uri)
            markDirty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
        viewModel.setSongId(requireArguments().getLong(ARG_SONG_ID))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_metadata_editor, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        // Sin esto, el sistema seguiría dejando la vista por debajo de la barra de estado aunque el
        // tema la pinte transparente: la portada no llegaría hasta arriba del todo.
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.editorRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.editorToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // El top va SOLO a la toolbar (con "wrap_content" crece sola para no tapar la
            // hora/batería); la portada sigue llegando hasta y = 0 por detrás.
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        bindViews(view)
        setupToolbar(view)
        setupBackHandling()

        // Dos reglas idénticas en funcionamiento (ver [setupOffsetControls]): la del vídeo
        // ([offsetRuler]) y la de la letra ([lyricsOffsetRuler], justo debajo del campo de letra).
        setupOffsetControls(offsetRuler, offsetValue)
        setupOffsetControls(lyricsOffsetRuler, lyricsOffsetValue)

        val textFields = mutableListOf<TextInputLayout>()
        collectTextInputLayouts(view, textFields)
        val editTexts = mutableListOf<EditText>()
        collectEditTexts(view, editTexts)
        editTexts.forEach { field ->
            field.addTextChangedListener { if (!isFillingForm) markDirty() }
        }

        val openPicker = {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        btnPickCover.setOnClickListener { openPicker() }
        fabAutofill.setOnClickListener { openMetadataSuggestions() }

        // El campo no se escribe a mano: se pulsa y abre el mismo buscador de YouTube que usa el
        // iPod (ver [VideoPickerDialogFragment]); al elegir un vídeo se rellena con su enlace.
        inputVideoUrl.setOnClickListener { openVideoPicker() }
        childFragmentManager.setFragmentResultListener(
            VideoPickerDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val videoId = bundle.getString(VideoPickerDialogFragment.RESULT_VIDEO_ID)
            if (videoId != null) {
                inputVideoUrl.setText(YouTubeUrl.watchUrl(videoId))
            }
        }
        childFragmentManager.setFragmentResultListener(
            MetadataSuggestionsDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            applySuggestion(bundle)
            // Si por el camino Genius rechazó el token, el candidato elegido YA se ha aplicado (con
            // lo que dio iTunes) y solo después se avisa: la elección del usuario no se pierde por
            // un problema que no es suyo. GeniusApi ya ha borrado el token para entonces.
            if (bundle.getBoolean(MetadataSuggestionsDialogFragment.RESULT_GENIUS_TOKEN_INVALID)) {
                GeniusApiErrorDialogFragment.newInstance()
                    .show(childFragmentManager, GeniusApiErrorDialogFragment.TAG)
            }
        }

        // Fin del diálogo de configuración de Genius, por cualquiera de sus dos salidas: se abre el
        // autorrelleno que el usuario pidió al tocar la varita, para que no tenga que volver a
        // tocarla. Si lo configuró, con Genius; si lo rechazó, solo con iTunes (ver
        // GeniusTokenDialogFragment.decline).
        childFragmentManager.setFragmentResultListener(
            GeniusTokenDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val configured = bundle.getBoolean(GeniusTokenDialogFragment.RESULT_CONFIGURED)
            val declined = bundle.getBoolean(GeniusTokenDialogFragment.RESULT_DECLINED)
            if (configured || declined) openMetadataSuggestions()
        }

        // "Error con la API de Genius" aceptado: se encadena el diálogo largo con las instrucciones
        // para sacar un API Client nuevo.
        childFragmentManager.setFragmentResultListener(
            GeniusApiErrorDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(GeniusApiErrorDialogFragment.RESULT_ACKNOWLEDGED)) {
                GeniusTokenDialogFragment.newInstance()
                    .show(childFragmentManager, GeniusTokenDialogFragment.TAG)
            }
        }

        // Papelera para vaciar letra y URL del vídeo: al no ser campos que se escriban a mano
        // (se pulsan y abren un buscador), tocarlos otra vez no sirve para borrarlos. Va en un
        // ImageButton aparte -no el icono final de TextInputLayout- para poder clavarla en la
        // esquina de ARRIBA en vez de centrada en todo el alto de la caja (ver el FrameLayout que
        // envuelve a cada campo en el XML). Solo se ve mientras el campo tiene algo que borrar.
        listOf(btnClearLyrics to inputLyrics, btnClearVideoUrl to inputVideoUrl).forEach { (button, field) ->
            button.isVisible = field.text?.isNotEmpty() == true
            button.setOnClickListener { field.setText("") }
            field.addTextChangedListener { button.isVisible = it?.isNotEmpty() == true }
        }

        // Igual que el campo de vídeo: no se escribe a mano, se pulsa y abre el buscador de
        // lrclib.net (ver [LyricsSuggestionsDialogFragment]); al elegir una letra se rellena con
        // ella. Es la ÚNICA forma de poner letra a una canción.
        inputLyrics.setOnClickListener { openLyricsPicker() }
        childFragmentManager.setFragmentResultListener(
            LyricsSuggestionsDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val lyrics = bundle.getString(LyricsSuggestionsDialogFragment.RESULT_LYRICS)
            if (lyrics != null) {
                inputLyrics.setText(lyrics)
            }
        }

        // El campo "Idioma" se autorrellena deduciéndolo de la letra cada vez que esta cambia,
        // tanto al elegirla en el buscador como al vaciarla con la papelera, pero el usuario puede
        // sobrescribirlo a mano después. Durante el volcado inicial (isFillingForm) NO se recalcula:
        // se respeta el idioma que ya traía guardado la canción.
        inputLyrics.doAfterTextChanged { text ->
            if (!isFillingForm) updateLanguageFromLyrics(text?.toString())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.song.collect { song -> if (song != null) fillForm(song) }
                }
                launch {
                    viewModel.trackAndDisc.collect { pair ->
                        if (pair != null && inputTrackNumber.text.isEmpty()) {
                            // No es un cambio del usuario: es el número de pista/disco que ya traía
                            // el archivo, así que no debe ensuciar el formulario.
                            isFillingForm = true
                            inputTrackNumber.setText(pair.first?.toString().orEmpty())
                            inputDiscNumber.setText(pair.second?.toString().orEmpty())
                            isFillingForm = false
                        }
                    }
                }
                launch {
                    viewModel.artistNames.collect { names -> setSuggestions(inputArtists, names) }
                }
                launch {
                    viewModel.albumTitles.collect { titles -> setSuggestions(inputAlbums, titles) }
                }
                launch {
                    viewModel.producerNames.collect { names -> setSuggestions(inputProducers, names) }
                }
                launch {
                    viewModel.saved.collect { result ->
                        if (result != null) {
                            Toast.makeText(
                                requireContext(), R.string.editor_saved, Toast.LENGTH_SHORT
                            ).show()
                            // Ya no se cierra el editor: el usuario sigue editando, pero ya no hay
                            // cambios sin guardar (así salir ahora mismo no pediría confirmación).
                            isDirty = false
                            // Ya se importó a disco: si no se vuelve a null, un guardado posterior
                            // sin tocar la portada la reimportaría de nuevo por nada.
                            pickedImage = null
                            // Si esta es la canción que suena, el reproductor la sustituye entera
                            // (letra, título, carátula...) al instante en vez de esperar a la
                            // siguiente reproducción.
                            playerViewModel.refreshSong(result.song)
                            viewModel.consumeSaved()
                        }
                    }
                }
                // El amarillo dinámico del botón de portada, del contorno de los campos al
                // enfocarlos y del cursor de texto sigue el color de lo que suena: por defecto
                // Material usa colorPrimary (amarillo fijo) para las tres cosas.
                launch {
                    val defaultStroke = ContextCompat.getColor(requireContext(), R.color.um_divider)
                    playerViewModel.accentColor.collect { accent ->
                        accentColor = accent
                        offsetRuler.accentColor = accent
                        lyricsOffsetRuler.accentColor = accent
                        AccentTint.fill(view, R.id.btnPickCover, accent)
                        AccentTint.contentOnAccent(btnPickCover, accent)
                        fabAutofill.backgroundTintList = ColorStateList.valueOf(accent)
                        AccentTint.contentOnAccent(fabAutofill, accent)
                        val strokeColors = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                            intArrayOf(accent, defaultStroke)
                        )
                        textFields.forEach { field ->
                            field.setBoxStrokeColorStateList(strokeColors)
                            field.setHintTextColor(ColorStateList.valueOf(accent))
                        }
                        // La rayita del cursor (setCursorColor, vía TextInputLayout) y la gota para
                        // arrastrarlo (setTextSelectHandle*, API de View) son dos dibujos aparte, y
                        // la gota solo tiene API pública para recolorearse desde Android 10 (API
                        // 29). Por debajo de esa versión NO tocamos ninguna de las dos, para que no
                        // quede una en el acento y la otra en el blanco fijo del tema (themes.xml):
                        // las dos se quedan blancas.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cursorTint = ColorStateList.valueOf(accent)
                            textFields.forEach { it.setCursorColor(cursorTint) }
                            editTexts.forEach { field ->
                                // Instancia nueva por cada llamada: si una selección muestra la
                                // gota izquierda y la derecha a la vez y comparten Drawable, cada
                                // una pisaría los bounds de la otra al posicionarse.
                                field.setTextSelectHandle(handleDrawable(accent))
                                field.setTextSelectHandleLeft(handleDrawable(accent))
                                field.setTextSelectHandleRight(handleDrawable(accent))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Recorre el árbol de vistas recogiendo todos los [TextInputLayout] (visibles o no: los de las
     * secciones plegables siguen inflados aunque estén ocultos). */
    private fun collectTextInputLayouts(view: View, into: MutableList<TextInputLayout>) {
        if (view is TextInputLayout) into.add(view)
        if (view is ViewGroup) view.children.forEach { collectTextInputLayouts(it, into) }
    }

    /** Igual que [collectTextInputLayouts] pero para los campos de texto en sí (incluye los
     * [MaterialAutoCompleteTextView], que también son EditText). */
    private fun collectEditTexts(view: View, into: MutableList<EditText>) {
        if (view is EditText) into.add(view)
        if (view is ViewGroup) view.children.forEach { collectEditTexts(it, into) }
    }

    /** "Gota" que se arrastra para mover el cursor o seleccionar texto, del color [color]. Sustituye
     * al dibujo nativo del sistema (que no se puede recolorear) por nuestra propia forma de gota
     * (ver drawable/handle_teardrop), retiñéndola con el acento. */
    private fun handleDrawable(color: Int): Drawable {
        val drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.handle_teardrop)!!
            .mutate()
        DrawableCompat.setTint(drawable, color)
        return drawable
    }

    private fun bindViews(view: View) {
        cover = view.findViewById(R.id.editorCover)
        btnPickCover = view.findViewById(R.id.btnPickCover)
        fabAutofill = view.findViewById(R.id.fabAutofill)
        inputTitle = view.findViewById(R.id.inputTitle)
        inputAlbums = view.findViewById(R.id.inputAlbums)
        inputArtists = view.findViewById(R.id.inputArtists)
        inputProducers = view.findViewById(R.id.inputProducers)
        inputYear = view.findViewById(R.id.inputYear)
        inputGenres = view.findViewById(R.id.inputGenres)
        inputLyrics = view.findViewById(R.id.inputLyrics)
        btnClearLyrics = view.findViewById(R.id.btnClearLyrics)
        lyricsOffsetRuler = view.findViewById(R.id.lyricsOffsetRuler)
        lyricsOffsetValue = view.findViewById(R.id.lyricsOffsetValue)
        inputLanguage = view.findViewById(R.id.inputLanguage)
        inputComment = view.findViewById(R.id.inputComment)
        inputVideoUrl = view.findViewById(R.id.inputVideoUrl)
        btnClearVideoUrl = view.findViewById(R.id.btnClearVideoUrl)
        offsetRuler = view.findViewById(R.id.offsetRuler)
        offsetValue = view.findViewById(R.id.offsetValue)
        inputTrackNumber = view.findViewById(R.id.inputTrackNumber)
        inputDiscNumber = view.findViewById(R.id.inputDiscNumber)
        inputOgTitle = view.findViewById(R.id.inputOgTitle)
        inputOgArtist = view.findViewById(R.id.inputOgArtist)
        inputOgAlbum = view.findViewById(R.id.inputOgAlbum)
        inputOgYear = view.findViewById(R.id.inputOgYear)
    }

    /**
     * Engancha [ruler] y [value] entre sí y con [markDirty], para que se comporten como una sola
     * regla de desplazamiento: la usan tanto [offsetRuler]/[offsetValue] (vídeo) como
     * [lyricsOffsetRuler]/[lyricsOffsetValue] (letra), que solo difieren en a qué campo de la
     * canción acaban escribiendo (ver [save]).
     *
     * La regla trabaja en "muescas" de [OFFSET_STEP_MS]: cada unidad suya son 100 ms de
     * desplazamiento real, no 1 ms. Con 1 ms por unidad, la separación fija entre marcas de
     * [ValueRuler] (ver su cabecera) obligaría a arrastrar metros para cambios de unos pocos
     * cientos de milisegundos, que es el caso normal de una desincronización.
     */
    private fun setupOffsetControls(ruler: ValueRuler, value: EditText) {
        ruler.minValue = -OFFSET_MAX_MS / OFFSET_STEP_MS
        ruler.maxValue = OFFSET_MAX_MS / OFFSET_STEP_MS
        ruler.onValueChanged = { notch ->
            if (!updatingOffsetUi) {
                showOffset(ruler, value, notch * OFFSET_STEP_MS)
                markDirty()
            }
        }
        // La caja admite cualquier milisegundo exacto escrito a mano; se refleja en la regla
        // cuadrando a la muesca de 100 ms más cercana (ver [showOffset] para el camino contrario).
        value.doAfterTextChanged { text ->
            if (updatingOffsetUi || isFillingForm) return@doAfterTextChanged
            val typed = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            updatingOffsetUi = true
            val notch = Math.round(typed / OFFSET_STEP_MS.toFloat())
            if (ruler.currentValue != notch) ruler.setValue(notch, notify = false)
            updatingOffsetUi = false
        }
        // Al pulsar "hecho" la caja se cuadra dentro del rango (por si quedó a medio escribir un
        // "-" o se pasó del tope) y se cierra el teclado, igual que SettingsDialogFragment.
        value.setOnEditorActionListener { _, _, _ ->
            showOffset(ruler, value, clampedOffsetOrZero(value))
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(value.windowToken, 0)
            value.clearFocus()
            true
        }
        value.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) showOffset(ruler, value, clampedOffsetOrZero(value))
        }
    }

    /**
     * Refleja [ms] en [ruler] y en [value] a la vez, cuadrando la regla a su muesca de
     * [OFFSET_STEP_MS] más cercana. [updatingOffsetUi] evita que este repintado se confunda
     * con un cambio del usuario (ver [setupOffsetControls]).
     */
    private fun showOffset(ruler: ValueRuler, value: EditText, ms: Int) {
        updatingOffsetUi = true
        val notch = Math.round(ms / OFFSET_STEP_MS.toFloat())
        if (ruler.currentValue != notch) ruler.setValue(notch, notify = false)
        if (value.text.toString() != ms.toString()) {
            value.setText(ms.toString())
            value.setSelection(value.text.length)
        }
        updatingOffsetUi = false
    }

    /** Lo escrito en [value], dentro del rango permitido; 0 si está vacío o a medio escribir. */
    private fun clampedOffsetOrZero(value: EditText): Int =
        value.text.toString().toIntOrNull()
            ?.coerceIn(-OFFSET_MAX_MS, OFFSET_MAX_MS) ?: 0

    private fun setupToolbar(view: View) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.editorToolbar)
        toolbar.setNavigationOnClickListener { attemptClose() }
        toolbar.inflateMenu(R.menu.menu_metadata_editor)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { save(); true }
                else -> false
            }
        }
    }

    /**
     * El botón "atrás" del sistema/gesto tiene que pasar por la misma confirmación que la flecha
     * de la toolbar (ver [attemptClose]). El despachador es el del DIÁLOGO, no el de la actividad
     * (mismo motivo que en [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]): un
     * DialogFragment se dibuja en su propia ventana y es esa la que recibe el "atrás".
     */
    private fun setupBackHandling() {
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = attemptClose()
            }
        )
    }

    /** Punto único de salida del editor: si hay cambios sin guardar pide confirmación antes de
     * cerrar (ver [showUnsavedChangesDialog]); si no, cierra directamente. */
    private fun attemptClose() {
        if (isDirty) showUnsavedChangesDialog() else closeNow()
    }

    /** Los botones siguen el color dinámico de la canción que suena, como manda el proyecto para
     * todo lo amarillo (ver [AccentTint.buttons]). */
    private fun showUnsavedChangesDialog() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.editor_unsaved_changes_title)
            .setMessage(R.string.editor_unsaved_changes_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.editor_leave) { _, _ -> closeNow() }
            .show()
        AccentTint.buttons(dialog, accentColor)
    }

    /** Cierra de verdad, sin pedir confirmación (ya se ha hecho o no hacía falta, ver
     * [attemptClose]). La animación de salida la pone el tema
     * ([R.style.Theme_UltiMusic_FullScreenDialog]). */
    private fun closeNow() {
        dismiss()
    }

    /** El usuario ha cambiado algo desde que se abrió el editor: si intenta salir ahora, hará
     * falta pedir confirmación (ver [attemptClose]). */
    private fun markDirty() {
        isDirty = true
    }

    /** Vuelca la canción en los campos. Solo la primera vez (ver [formLoaded]). */
    private fun fillForm(song: Song) {
        if (formLoaded) return
        formLoaded = true
        isFillingForm = true

        // Sin error(...): si Coil no encuentra carátula, deja el ImageView sin imagen y se ve el
        // mismo fondo cover_placeholder que en las listas de canciones/álbumes (item_song.xml,
        // item_album_card.xml...), en vez de estirar ese drawable como si fuera la foto en sí.
        if (pickedImage == null) {
            cover.load(CoverArt.cover(requireContext(), song), CoverLoader.get(requireContext()))
        }

        inputTitle.setText(song.title)
        // El segundo parámetro (`filter = false`) evita que rellenar el campo abra el desplegable.
        inputAlbums.setText(song.albums.joinToString(SEPARATOR) { it.title }, false)
        inputArtists.setText(song.artists.joinToString(SEPARATOR) { it.name }, false)
        inputProducers.setText(song.producers.joinToString(SEPARATOR) { it.name }, false)
        inputYear.setText(song.year?.toString().orEmpty())
        inputGenres.setText(song.genres.joinToString(SEPARATOR))
        inputLyrics.setText(song.lyrics.orEmpty())
        inputLanguage.setText(song.language.orEmpty())
        inputComment.setText(song.comment.orEmpty())
        inputVideoUrl.setText(song.videoUrl.orEmpty())
        showOffset(offsetRuler, offsetValue, song.videoOffsetMs.toInt())
        showOffset(lyricsOffsetRuler, lyricsOffsetValue, song.lyricsOffsetMs.toInt())
        inputOgTitle.setText(song.ogTitle.orEmpty())
        inputOgArtist.setText(song.ogArtist.orEmpty())
        inputOgAlbum.setText(song.ogAlbum.orEmpty())
        inputOgYear.setText(song.ogYear?.toString().orEmpty())

        isFillingForm = false
    }

    /**
     * Deduce el idioma a partir de [lyrics] con [LanguageDetector] y lo vuelca en [inputLanguage].
     * Letra vacía limpia el campo sin más (no hay de dónde deducir nada); si ML Kit no consigue
     * decidirse, también lo deja vacío en vez de dejar el idioma de una letra anterior.
     *
     * La detección es asíncrona: para cuando responde, la letra pudo haber cambiado otra vez (o el
     * editor pudo haberse cerrado), así que el resultado solo se aplica si [inputLyrics] sigue
     * teniendo exactamente el mismo texto que se mandó a analizar.
     */
    private fun updateLanguageFromLyrics(lyrics: String?) {
        val text = lyrics.orEmpty().trim()
        if (text.isEmpty()) {
            inputLanguage.setText("")
            return
        }
        LanguageDetector.detect(text) { language ->
            if (isAdded && inputLyrics.text.toString().trim() == text) {
                inputLanguage.setText(language.orEmpty())
            }
        }
    }

    /**
     * Abre el buscador de YouTube con "Título Artista" ya escrito, igual que hace el iPod (ver
     * [com.untar.ultimusic.ui.player.IPodNanoDialogFragment.searchQuery]). Se lee de los campos del
     * formulario, no de la canción cargada, para que la búsqueda tenga en cuenta lo que se esté
     * escribiendo aunque todavía no se haya guardado.
     */
    private fun openVideoPicker() {
        val title = inputTitle.text.toString().trim()
        val artist = inputArtists.splitValues().firstOrNull()
        val query = listOfNotNull(title.takeIf { it.isNotEmpty() }, artist).joinToString(" ")
        VideoPickerDialogFragment.newInstance(query).show(childFragmentManager, TAG_VIDEO_PICKER)
    }

    /** Busca sugerencias con lo que haya AHORA en los campos de título y artista (no la canción
     * cargada): así tiene en cuenta lo que se esté escribiendo aunque no se haya guardado todavía,
     * igual que [openVideoPicker].
     *
     * La primera vez pasa por [GeniusTokenDialogFragment]: el autorrelleno de canción usa Genius
     * para los productores, el remix y el videoclip, y ese token lo pone cada usuario (ver
     * [com.untar.ultimusic.data.remote.GeniusTokenStore] sobre por qué). La comprobación va después
     * de la de los campos vacíos a propósito: no tiene sentido mandar a nadie a registrar un cliente
     * de API para una búsqueda que ni siquiera se podría lanzar. */
    private fun openMetadataSuggestions() {
        val title = inputTitle.text.toString().trim()
        val artist = inputArtists.splitValues().firstOrNull().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.autofill_missing_query_song, Toast.LENGTH_SHORT)
                .show()
            return
        }
        // Solo se ofrece si no hay token utilizable Y el usuario no lo ha rechazado ya: quien dijo
        // que no (o no puede completarlo, p. ej. estando baneado de Genius) usa el autorrelleno con
        // iTunes y no se le vuelve a insistir. Ver GeniusTokenStore.shouldOfferSetup.
        if (GeniusTokenStore.shouldOfferSetup) {
            GeniusTokenDialogFragment.newInstance()
                .show(childFragmentManager, GeniusTokenDialogFragment.TAG)
            return
        }
        MetadataSuggestionsDialogFragment.newInstance(SuggestionKind.SONG, title, artist)
            .show(childFragmentManager, TAG_SUGGESTIONS)
    }

    /** Busca letras en lrclib.net con lo que haya AHORA en título/artista, igual que
     * [openMetadataSuggestions]. */
    private fun openLyricsPicker() {
        val title = inputTitle.text.toString().trim()
        val artist = inputArtists.splitValues().firstOrNull().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.autofill_missing_query_song, Toast.LENGTH_SHORT)
                .show()
            return
        }
        // La duración se saca de la canción cargada (no del formulario: no es un campo editable) y
        // sirve para marcar los candidatos que sean de la misma grabación.
        LyricsSuggestionsDialogFragment.newInstance(title, artist, viewModel.song.value?.duration ?: 0L)
            .show(childFragmentManager, TAG_LYRICS_SUGGESTIONS)
    }

    /**
     * Vuelca en el formulario la sugerencia elegida en [MetadataSuggestionsDialogFragment]. El
     * grueso viene de iTunes; productores, canción original y videoclip los aporta Genius cuando
     * está configurado (ver [com.untar.ultimusic.data.remote.GeniusApi]), y llegan vacíos si no.
     *
     * Artistas y productores se tratan igual, como manda el proyecto: los dos se SUSTITUYEN por lo
     * que traiga la sugerencia, no se acumulan. Acumular parece más prudente, pero acaba dejando el
     * mismo artista escrito de dos formas ("ピノキオピー" junto a "PinocchioP") — y aquí eso no es un
     * campo de texto feo, es un perfil de más en la pestaña de Artistas, que además hay que ir a
     * borrar a mano. Sustituir deja el campo tal cual lo dice la fuente, y como el editor no guarda
     * nada hasta que se pulsa Guardar, cualquier colaborador que falte se puede volver a escribir
     * antes de eso.
     */
    private fun applySuggestion(bundle: Bundle) {
        isFillingForm = true

        val title = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_TITLE).orEmpty()
        if (title.isNotEmpty()) inputTitle.setText(title)

        val album = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_ALBUM).orEmpty()
        if (album.isNotEmpty()) inputAlbums.setText(album, false)

        val artists = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_ARTIST).orEmpty()
        replaceValues(inputArtists, artists)

        val producers = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_PRODUCERS).orEmpty()
        replaceValues(inputProducers, producers)

        val genres = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_GENRES).orEmpty()
        if (genres.isNotEmpty()) inputGenres.setText(genres)

        val year = bundle.getInt(MetadataSuggestionsDialogFragment.RESULT_YEAR, MetadataSuggestionsDialogFragment.NO_VALUE)
        if (year > 0) inputYear.setText(year.toString())

        val trackNumber = bundle.getInt(MetadataSuggestionsDialogFragment.RESULT_TRACK_NUMBER, MetadataSuggestionsDialogFragment.NO_VALUE)
        if (trackNumber > 0) inputTrackNumber.setText(trackNumber.toString())

        val discNumber = bundle.getInt(MetadataSuggestionsDialogFragment.RESULT_DISC_NUMBER, MetadataSuggestionsDialogFragment.NO_VALUE)
        if (discNumber > 0) inputDiscNumber.setText(discNumber.toString())

        // Los og* solo llegan con algo si Genius sabe que esta canción es un remix; si no, se
        // quedan como estaban (normalmente vacíos, que es justo lo que significan).
        val ogTitle = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_OG_TITLE).orEmpty()
        if (ogTitle.isNotEmpty()) inputOgTitle.setText(ogTitle)

        val ogArtist = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_OG_ARTIST).orEmpty()
        if (ogArtist.isNotEmpty()) inputOgArtist.setText(ogArtist)

        val ogAlbum = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_OG_ALBUM).orEmpty()
        if (ogAlbum.isNotEmpty()) inputOgAlbum.setText(ogAlbum)

        val ogYear = bundle.getInt(MetadataSuggestionsDialogFragment.RESULT_OG_YEAR, MetadataSuggestionsDialogFragment.NO_VALUE)
        if (ogYear > 0) inputOgYear.setText(ogYear.toString())

        // El videoclip solo se pone si el campo está VACÍO, a diferencia del resto: si ya hay uno,
        // lo eligió el usuario a mano en el buscador de YouTube y sabe mejor que Genius cuál quiere.
        val videoUrl = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_VIDEO_URL).orEmpty()
        if (videoUrl.isNotEmpty() && inputVideoUrl.text.isNullOrBlank()) inputVideoUrl.setText(videoUrl)

        isFillingForm = false
        markDirty()

        // La portada se descarga aparte y de forma asíncrona: si tarda o falla, el resto de los
        // campos ya se han rellenado igualmente (ver NetworkImage.download, best-effort). La URL
        // llega ya en su resolución final y con la fuente decidida, así que aquí no hay nada que
        // ajustar (ver MetadataSuggestionsDialogFragment.coverUrlFor).
        val coverUrl = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_COVER_URL).orEmpty()
        if (coverUrl.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                NetworkImage.download(requireContext(), coverUrl)?.let { uri ->
                    pickedImage = uri
                    cover.load(uri)
                }
            }
        }
    }

    /** Sustituye el contenido de un campo multivalor (artistas, productores) por el que traiga la
     * sugerencia, normalizando de paso el separador al del editor. Un valor vacío no borra nada: la
     * fuente no sabía de ese campo, que no es lo mismo que decir que está vacío. Es el mismo
     * criterio para los dos porque, como dice el proyecto, artistas y productores se tratan igual. */
    private fun replaceValues(field: MaterialAutoCompleteTextView, incoming: String) {
        val values = incoming.split(SEPARATOR_CHAR).map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return
        field.setText(values.joinToString(SEPARATOR), false)
    }

    private fun save() {
        val title = inputTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), R.string.editor_title_required, Toast.LENGTH_SHORT)
                .show()
            return
        }

        viewModel.save(
            EditorForm(
                title = title,
                albums = inputAlbums.splitValues(),
                artists = inputArtists.splitValues(),
                producers = inputProducers.splitValues(),
                year = inputYear.intOrNull(),
                genres = inputGenres.splitValues(),
                lyrics = inputLyrics.textOrNull(),
                language = inputLanguage.textOrNull(),
                comment = inputComment.textOrNull(),
                videoUrl = inputVideoUrl.textOrNull(),
                videoOffsetMs = clampedOffsetOrZero(offsetValue).toLong(),
                lyricsOffsetMs = clampedOffsetOrZero(lyricsOffsetValue).toLong(),
                trackNumber = inputTrackNumber.intOrNull(),
                discNumber = inputDiscNumber.intOrNull(),
                ogTitle = inputOgTitle.textOrNull(),
                ogArtist = inputOgArtist.textOrNull(),
                ogAlbum = inputOgAlbum.textOrNull(),
                ogYear = inputOgYear.intOrNull()
            ),
            pickedImage
        )
    }

    /**
     * Carga las sugerencias del desplegable y hace que elegir una sustituya SOLO el valor que se
     * está escribiendo (el que va detrás de la última coma), no la línea entera. El widget, por su
     * cuenta, reemplazaría todo el texto: por eso lo reescribimos nosotros después.
     */
    private fun setSuggestions(field: MaterialAutoCompleteTextView, names: List<String>) {
        field.setSimpleItems(names.toTypedArray())
        field.setOnItemClickListener { parent, _, position, _ ->
            val chosen = parent.getItemAtPosition(position).toString()
            val prefix = (field.tag as? String).orEmpty().substringBeforeLast(SEPARATOR_CHAR, "")
            val merged = if (prefix.isBlank()) chosen else "${prefix.trim()}$SEPARATOR$chosen"
            field.setText(merged, false)
            field.setSelection(merged.length)
        }
        // El widget pisa el texto ANTES de avisar al listener, así que en cada cambio guardamos en
        // el `tag` de la vista el texto PREVIO: es el que tiene los valores ya escritos.
        field.addPreviousTextTracker { previous -> field.tag = previous }
    }

    companion object {
        private const val ARG_SONG_ID = "songId"
        private const val TAG_VIDEO_PICKER = "video_picker"
        private const val TAG_SUGGESTIONS = "metadata_suggestions"
        private const val TAG_LYRICS_SUGGESTIONS = "lyrics_suggestions"
        private const val SEPARATOR = ", "
        private const val SEPARATOR_CHAR = ','

        /**
         * Tope del desplazamiento (vídeo/audio o letra/audio), en cada sentido. Como el del
         * amplificador de volumen sin límite, no está para proteger al usuario sino porque
         * [ValueRuler] necesita un máximo (ver su cabecera): 30 segundos son muchísimo más que
         * cualquier desincronización real, así que arrastrando no se llega nunca por accidente.
         */
        private const val OFFSET_MAX_MS = 30_000

        /** Cuántos milisegundos avanza cada regla de desplazamiento por cada muesca (ver
         * [showOffset]). */
        private const val OFFSET_STEP_MS = 100

        fun newInstance(songId: Long): MetadataEditorDialogFragment =
            MetadataEditorDialogFragment().apply {
                arguments = Bundle().apply { putLong(ARG_SONG_ID, songId) }
            }
    }
}

// --- Ayudas de lectura de los campos ---

/** Texto recortado, o null si el usuario lo dejó vacío (así la columna queda a NULL en la BD). */
private fun EditText.textOrNull(): String? = text.toString().trim().takeIf { it.isNotEmpty() }

private fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

/** Parte un campo multivalor por comas, quitando espacios y trozos vacíos. */
private fun EditText.splitValues(): List<String> =
    text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Avisa con el texto que había JUSTO ANTES de cada cambio (`beforeTextChanged`). Un [TextWatcher]
 * es la forma estándar de escuchar a un EditText; aquí solo interesa ese primer callback.
 */
private fun EditText.addPreviousTextTracker(onBeforeChange: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            onBeforeChange(s?.toString().orEmpty())
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = Unit
    })
}
