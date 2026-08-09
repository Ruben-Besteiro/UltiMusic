package com.untar.ultimusic.ui.editor

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
import android.view.MenuItem
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
import androidx.core.widget.addTextChangedListener
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
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.remote.MusicBrainzApi
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.CoverRef
import com.untar.ultimusic.util.NetworkImage
import kotlinx.coroutines.launch

/**
 * Editor de metadatos de un ÁLBUM, a pantalla completa. Gemelo de [MetadataEditorDialogFragment]
 * (mismo tipo de [DialogFragment] a pantalla completa, mismo icono de guardar que se amarillea con
 * cambios sin guardar, mismo tintado de acento en los campos), pero con los campos que tiene un
 * álbum en vez de una canción suelta: título, artista(s), año, género(s) y portada.
 *
 * Lo abre el botón de 3 puntos de la ficha de álbum (ver
 * [com.untar.ultimusic.ui.library.DetailDialogFragment]). Al guardar, la propia ficha se repinta
 * sola: su cabecera sale de la misma consulta de Room que este editor acaba de escribir.
 */
class AlbumEditorDialogFragment : DialogFragment() {

    private val viewModel: AlbumEditorViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Imagen elegida en esta sesión de edición; no se copia a disco hasta que se guarda. */
    private var pickedImage: Uri? = null

    /** El formulario solo se rellena con la primera emisión: si no, escribir sería imposible. */
    private var formLoaded = false

    /** Se activa mientras [fillForm] escribe los campos, para que ese volcado inicial no se
     * confunda con un cambio del usuario y marque el formulario como sucio. */
    private var isFillingForm = false

    /** True en cuanto el usuario cambia algo. El icono de guardar se pinta de amarillo dinámico
     * mientras esto sea true, para señalar que esa es la opción recomendada. */
    private var isDirty = false

    private lateinit var saveMenuItem: MenuItem
    private var accentColor: Int = 0

    private lateinit var inputTitle: EditText
    private lateinit var inputArtists: MaterialAutoCompleteTextView
    private lateinit var inputYear: EditText
    private lateinit var inputGenres: EditText
    private lateinit var cover: ImageView
    private lateinit var btnPickCover: ImageButton
    private lateinit var fabAutofill: FloatingActionButton

    /** Mismo selector de fotos del sistema que el editor de canciones: ver
     * [MetadataEditorDialogFragment.pickImage]. */
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
        viewModel.setAlbumId(requireArguments().getLong(ARG_ALBUM_ID))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_album_editor, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.editorRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.editorToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        bindViews(view)
        setupToolbar(view)

        val textFields = mutableListOf<TextInputLayout>()
        collectTextInputLayouts(view, textFields)
        val editTexts = mutableListOf<EditText>()
        collectEditTexts(view, editTexts)
        editTexts.forEach { field ->
            field.addTextChangedListener { if (!isFillingForm) markDirty() }
        }

        btnPickCover.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        fabAutofill.setOnClickListener { openMetadataSuggestions() }

        childFragmentManager.setFragmentResultListener(
            MetadataSuggestionsDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle -> applySuggestion(bundle) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.album.collect { pair -> if (pair != null) fillForm(pair.first, pair.second) }
                }
                launch {
                    viewModel.artistNames.collect { names -> setSuggestions(inputArtists, names) }
                }
                launch {
                    viewModel.saved.collect { result ->
                        if (result != null) {
                            Toast.makeText(
                                requireContext(), R.string.editor_saved, Toast.LENGTH_SHORT
                            ).show()
                            isDirty = false
                            updateSaveIcon()
                            pickedImage = null
                            viewModel.consumeSaved()
                        }
                    }
                }
                // Mismo amarillo dinámico que el editor de canciones: ver
                // MetadataEditorDialogFragment para la explicación completa de cada parte.
                launch {
                    val defaultStroke = ContextCompat.getColor(requireContext(), R.color.um_divider)
                    playerViewModel.accentColor.collect { accent ->
                        accentColor = accent
                        updateSaveIcon()
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cursorTint = ColorStateList.valueOf(accent)
                            textFields.forEach { it.setCursorColor(cursorTint) }
                            editTexts.forEach { field ->
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

    private fun collectTextInputLayouts(view: View, into: MutableList<TextInputLayout>) {
        if (view is TextInputLayout) into.add(view)
        if (view is ViewGroup) view.children.forEach { collectTextInputLayouts(it, into) }
    }

    private fun collectEditTexts(view: View, into: MutableList<EditText>) {
        if (view is EditText) into.add(view)
        if (view is ViewGroup) view.children.forEach { collectEditTexts(it, into) }
    }

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
        inputArtists = view.findViewById(R.id.inputArtists)
        inputYear = view.findViewById(R.id.inputYear)
        inputGenres = view.findViewById(R.id.inputGenres)
    }

    private fun setupToolbar(view: View) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.editorToolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_metadata_editor)
        saveMenuItem = toolbar.menu.findItem(R.id.action_save)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { save(); true }
                else -> false
            }
        }
    }

    private fun markDirty() {
        if (isDirty) return
        isDirty = true
        updateSaveIcon()
    }

    private fun updateSaveIcon() {
        if (!::saveMenuItem.isInitialized) return
        val drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_save)?.mutate()
        if (isDirty) {
            drawable?.let { DrawableCompat.setTint(it, accentColor) }
        }
        saveMenuItem.icon = drawable
    }

    /** Vuelca el álbum en los campos. Solo la primera vez (ver [formLoaded]). */
    private fun fillForm(album: AlbumEntity, artistNames: List<String>) {
        if (formLoaded) return
        formLoaded = true
        isFillingForm = true

        // Sin error(...): igual que en el editor de canciones, si no hay carátula se deja el
        // ImageView sin imagen y se ve el mismo fondo cover_placeholder que en las listas.
        if (pickedImage == null) {
            cover.load(
                CoverArt.cover(requireContext(), CoverRef(album.imageName, null, null)),
                CoverLoader.get(requireContext())
            )
        }

        inputTitle.setText(album.title)
        inputArtists.setText(artistNames.joinToString(SEPARATOR), false)
        inputYear.setText(album.year?.toString().orEmpty())
        inputGenres.setText(album.genres.joinToString(SEPARATOR))

        isFillingForm = false
    }

    /** Busca sugerencias con lo que haya AHORA en título y artista(s), no el álbum cargado: así
     * tiene en cuenta lo que se esté escribiendo aunque no se haya guardado todavía. Ver
     * [MetadataEditorDialogFragment.openMetadataSuggestions], mismo criterio. */
    private fun openMetadataSuggestions() {
        val title = inputTitle.text.toString().trim()
        val artist = inputArtists.splitValues().firstOrNull().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.autofill_missing_query_album, Toast.LENGTH_SHORT)
                .show()
            return
        }
        MetadataSuggestionsDialogFragment.newInstance(SuggestionKind.ALBUM, title, artist)
            .show(childFragmentManager, TAG_SUGGESTIONS)
    }

    /**
     * Vuelca la sugerencia elegida. Más simple que
     * [MetadataEditorDialogFragment.applySuggestion]: un álbum no tiene pista, disco ni "álbum" al
     * que pertenecer, así que solo se tocan título, artistas, año, géneros y portada. El criterio
     * de artistas es el mismo: se AÑADE el de la sugerencia si no estaba ya, nunca sustituye lo
     * que el usuario hubiera escrito.
     */
    private fun applySuggestion(bundle: Bundle) {
        isFillingForm = true

        val title = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_TITLE).orEmpty()
        if (title.isNotEmpty()) inputTitle.setText(title)

        val artist = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_ARTIST).orEmpty()
        if (artist.isNotEmpty()) {
            val current = inputArtists.splitValues()
            if (current.none { it.equals(artist, ignoreCase = true) }) {
                val merged = (current + artist).joinToString(SEPARATOR)
                inputArtists.setText(merged, false)
            }
        }

        val genres = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_GENRES).orEmpty()
        if (genres.isNotEmpty()) inputGenres.setText(genres)

        val year = bundle.getInt(MetadataSuggestionsDialogFragment.RESULT_YEAR, MetadataSuggestionsDialogFragment.NO_VALUE)
        if (year > 0) inputYear.setText(year.toString())

        isFillingForm = false
        markDirty()

        val coverUrl = bundle.getString(MetadataSuggestionsDialogFragment.RESULT_COVER_URL).orEmpty()
        if (coverUrl.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                NetworkImage.download(requireContext(), MusicBrainzApi.fullResCoverUrl(coverUrl))?.let { uri ->
                    pickedImage = uri
                    cover.load(uri)
                }
            }
        }
    }

    private fun save() {
        val title = inputTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), R.string.album_editor_title_required, Toast.LENGTH_SHORT)
                .show()
            return
        }

        viewModel.save(
            AlbumEditorForm(
                title = title,
                artists = inputArtists.splitValues(),
                year = inputYear.intOrNull(),
                genres = inputGenres.splitValues()
            ),
            pickedImage
        )
    }

    /**
     * Carga las sugerencias del desplegable y hace que elegir una sustituya SOLO el valor que se
     * está escribiendo (el que va detrás de la última coma), no la línea entera. Idéntico al de
     * [MetadataEditorDialogFragment.setSuggestions]: es el mismo widget con el mismo campo
     * multivalor ("Artista(s)"), y debe comportarse igual en los dos editores.
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
        field.addPreviousTextTracker { previous -> field.tag = previous }
    }

    companion object {
        private const val ARG_ALBUM_ID = "albumId"
        private const val TAG_SUGGESTIONS = "metadata_suggestions"
        private const val SEPARATOR = ", "
        private const val SEPARATOR_CHAR = ','

        fun newInstance(albumId: Long): AlbumEditorDialogFragment =
            AlbumEditorDialogFragment().apply {
                arguments = Bundle().apply { putLong(ARG_ALBUM_ID, albumId) }
            }
    }
}

private fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

private fun EditText.splitValues(): List<String> =
    text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Avisa con el texto que había JUSTO ANTES de cada cambio (`beforeTextChanged`). Ver
 * [MetadataEditorDialogFragment.addPreviousTextTracker]: mismo truco, mismo motivo. */
private fun EditText.addPreviousTextTracker(onBeforeChange: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            onBeforeChange(s?.toString().orEmpty())
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = Unit
    })
}
