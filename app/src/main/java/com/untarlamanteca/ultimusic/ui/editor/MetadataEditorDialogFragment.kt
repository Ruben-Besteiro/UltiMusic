package com.untarlamanteca.ultimusic.ui.editor

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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.ui.PlayerViewModel
import com.untarlamanteca.ultimusic.ui.common.CollapsibleSection
import com.untarlamanteca.ultimusic.util.AccentTint
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverLoader
import kotlinx.coroutines.launch

/**
 * Editor de metadatos de una canción, a pantalla completa.
 *
 * Es un [DialogFragment] (no una Activity) por el mismo motivo que la ventana del iPod: así se abre
 * y se cierra sin salir de la pantalla principal y comparte el ciclo de vida del fragmento que lo
 * lanza. Con [STYLE_NO_FRAME] y `setLayout(MATCH_PARENT, MATCH_PARENT)` deja de parecer un diálogo
 * flotante y ocupa toda la pantalla.
 *
 * Los cuatro campos principales están siempre visibles; los secundarios se agrupan en secciones
 * plegables ([CollapsibleSection]). Lo que se escribe aquí se guarda en Room, de modo que persiste
 * aunque se cierre la aplicación y el escaneo del disco no lo pisa.
 */
class MetadataEditorDialogFragment : DialogFragment() {

    private val viewModel: MetadataEditorViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Imagen elegida en esta sesión de edición; no se copia a disco hasta que se guarda. */
    private var pickedImage: Uri? = null

    /** El formulario solo se rellena con la primera emisión: si no, escribir sería imposible. */
    private var formLoaded = false

    private lateinit var inputTitle: EditText
    private lateinit var inputAlbums: MaterialAutoCompleteTextView
    private lateinit var inputArtists: MaterialAutoCompleteTextView
    private lateinit var inputProducers: MaterialAutoCompleteTextView
    private lateinit var inputYear: EditText
    private lateinit var inputGenres: EditText
    private lateinit var inputLyrics: EditText
    private lateinit var inputLanguage: EditText
    private lateinit var inputComment: EditText
    private lateinit var inputTrackNumber: EditText
    private lateinit var inputDiscNumber: EditText
    private lateinit var inputOgTitle: EditText
    private lateinit var inputOgArtist: EditText
    private lateinit var inputOgAlbum: EditText
    private lateinit var inputOgYear: EditText
    private lateinit var cover: ImageView
    private lateinit var btnPickCover: ImageButton

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
        setupSections(view)

        val textFields = mutableListOf<TextInputLayout>()
        collectTextInputLayouts(view, textFields)
        val editTexts = mutableListOf<EditText>()
        collectEditTexts(view, editTexts)

        val openPicker = {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        btnPickCover.setOnClickListener { openPicker() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.song.collect { song -> if (song != null) fillForm(song) }
                }
                launch {
                    viewModel.trackAndDisc.collect { pair ->
                        if (pair != null && inputTrackNumber.text.isEmpty()) {
                            inputTrackNumber.setText(pair.first?.toString().orEmpty())
                            inputDiscNumber.setText(pair.second?.toString().orEmpty())
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
                    viewModel.saved.collect { saved ->
                        if (saved) {
                            Toast.makeText(
                                requireContext(), R.string.editor_saved, Toast.LENGTH_SHORT
                            ).show()
                            dismiss()
                        }
                    }
                }
                // El amarillo dinámico del botón de portada, del contorno de los campos al
                // enfocarlos y del cursor de texto sigue el color de lo que suena: por defecto
                // Material usa colorPrimary (amarillo fijo) para las tres cosas.
                launch {
                    val defaultStroke = ContextCompat.getColor(requireContext(), R.color.um_divider)
                    playerViewModel.accentColor.collect { accent ->
                        AccentTint.fill(view, R.id.btnPickCover, accent)
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
        inputTitle = view.findViewById(R.id.inputTitle)
        inputAlbums = view.findViewById(R.id.inputAlbums)
        inputArtists = view.findViewById(R.id.inputArtists)
        inputProducers = view.findViewById(R.id.inputProducers)
        inputYear = view.findViewById(R.id.inputYear)
        inputGenres = view.findViewById(R.id.inputGenres)
        inputLyrics = view.findViewById(R.id.inputLyrics)
        inputLanguage = view.findViewById(R.id.inputLanguage)
        inputComment = view.findViewById(R.id.inputComment)
        inputTrackNumber = view.findViewById(R.id.inputTrackNumber)
        inputDiscNumber = view.findViewById(R.id.inputDiscNumber)
        inputOgTitle = view.findViewById(R.id.inputOgTitle)
        inputOgArtist = view.findViewById(R.id.inputOgArtist)
        inputOgAlbum = view.findViewById(R.id.inputOgAlbum)
        inputOgYear = view.findViewById(R.id.inputOgYear)
    }

    private fun setupToolbar(view: View) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.editorToolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_metadata_editor)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                save()
                true
            } else false
        }
    }

    private fun setupSections(view: View) {
        CollapsibleSection.setup(
            view.findViewById(R.id.sectionDetailsHeader),
            view.findViewById(R.id.sectionDetailsContent)
        )
        CollapsibleSection.setup(
            view.findViewById(R.id.sectionPositionHeader),
            view.findViewById(R.id.sectionPositionContent)
        )
        CollapsibleSection.setup(
            view.findViewById(R.id.sectionOriginalHeader),
            view.findViewById(R.id.sectionOriginalContent)
        )
    }

    /** Vuelca la canción en los campos. Solo la primera vez (ver [formLoaded]). */
    private fun fillForm(song: Song) {
        if (formLoaded) return
        formLoaded = true

        if (pickedImage == null) {
            cover.load(CoverArt.cover(requireContext(), song), CoverLoader.get(requireContext())) {
                error(R.drawable.cover_placeholder)
            }
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
        inputOgTitle.setText(song.ogTitle.orEmpty())
        inputOgArtist.setText(song.ogArtist.orEmpty())
        inputOgAlbum.setText(song.ogAlbum.orEmpty())
        inputOgYear.setText(song.ogYear?.toString().orEmpty())

        inputLanguage.setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.language_auto_fill,
                Toast.LENGTH_SHORT
            ).show()
        }
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
        private const val SEPARATOR = ", "
        private const val SEPARATOR_CHAR = ','

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
