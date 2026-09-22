package com.untar.ultimusic.ui.settings

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.untar.ultimusic.R
import com.untar.ultimusic.data.BulkLyricsAdder
import com.untar.ultimusic.data.remote.GeniusTokenStore
import com.untar.ultimusic.data.remote.YouTubeApiKeyStore
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.model.LibraryRoot
import com.untar.ultimusic.ui.BOOST_LIMIT_PERCENT
import com.untar.ultimusic.ui.BOOST_MAX_PERCENT
import com.untar.ultimusic.ui.BOOST_MIN_PERCENT
import com.untar.ultimusic.ui.EqPreset
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.recount.RecountReminder
import com.untar.ultimusic.ui.recount.RecountDialogFragment
import com.untar.ultimusic.ui.common.ValueRuler
import com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment
import com.untar.ultimusic.ui.sort.YouTubeApiKeyDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.AppLocale
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.Headphones
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.launch

/**
 * Pantalla de ajustes, la del engranaje de la barra superior. Está dividida en tres grupos, uno
 * detrás de otro: **ajustes auditivos** (el **amplificador de volumen** y el **ecualizador**),
 * **ajustes visuales** (la casilla de **etiquetas en la pestaña Canciones**, las **carpetas de la
 * fonoteca** y la **lista gris** de subcarpetas) y **servicios externos** (el token de **Genius**,
 * la clave de la **YouTube Data API** y la de la **Cloud Translation API**, ver el bloque de más
 * abajo).
 *
 * Es un [DialogFragment] a pantalla completa, como el buscador o el editor de metadatos: se abre
 * encima de la principal sin cambiar de Activity, así que la música no se corta y el botón "atrás"
 * devuelve a la pestaña en la que se estaba.
 *
 * Aquí **no** vive el estado: la amplificación, el ecualizador y el límite los guarda
 * [PlayerViewModel], que es quien tiene el reproductor y aplica de verdad los efectos. Esta clase
 * solo pinta ese estado y le manda los cambios. Por eso usa `activityViewModels()`: es el mismo
 * ViewModel que el mini-reproductor, no una copia.
 *
 * ### Los dos mandos del amplificador
 *
 * - Con el límite puesto (lo normal) se ve un **slider** del 100 % al 150 %.
 * - Al desactivar el límite, el slider se cambia por un [ValueRuler], una cinta métrica que se
 *   arrastra. El motivo es que un slider **dibuja su recorrido**, así que necesita un máximo
 *   cercano; con el rango que se abre al quitar el límite, un píxel valdría decenas de unidades y el
 *   slider sería inservible.
 *
 * En los dos casos, el número exacto sale a la derecha en una caja de texto editable, por si se
 * quiere clavar un valor sin pelearse con el dedo.
 *
 * ### El ecualizador
 *
 * Un interruptor maestro, un desplegable de presets, un slider vertical por cada banda que
 * reporte el dispositivo (ver [PlayerViewModel.eqBands]: su número y rango no son fijos de la
 * aplicación) y una ganancia maestra. Tocar cualquier control a mano cambia el desplegable a
 * "Usuario"; guardar esa configuración con un nombre la añade al final del desplegable, después de
 * los predefinidos.
 *
 * Hay dos capas de ocultación, una dentro de la otra:
 *
 * - El interruptor maestro oculta **todo** lo demás de la sección ([eqOptionsContainer]: presets,
 *   bandas, ganancia previa, reinicio y la casilla "Opciones adicionales" con su contenido). Con
 *   el ecualizador apagado no tiene sentido enseñar controles que no van a sonar.
 * - Dentro de eso, la casilla "Opciones adicionales" oculta o enseña cuatro efectos que no son
 *   bandas del ecualizador: refuerzo de graves, sonido envolvente, reverberación y compresor de
 *   rango dinámico (los dos últimos, novedad frente al ecualizador clásico, se explican en el
 *   bloque "Ecualizador" de [PlayerViewModel]). Van agrupados y ocultos por defecto porque se
 *   tocan poco, y cada uno lleva su propio botón "(?)" —el mismo icono que la ayuda de la
 *   actividad principal— que abre un diálogo explicando qué hace y por qué no basta con los
 *   sliders verticales.
 *
 * ### Servicios externos
 *
 * Genius y la YouTube Data API piden un token/clave que pone cada usuario (ver [GeniusTokenStore] y
 * [YouTubeApiKeyStore]) mediante un diálogo de "Acción requerida" —[GeniusTokenDialogFragment],
 * [YouTubeApiKeyDialogFragment]— que solo sale la primera vez que hace falta: al tocar la varita del
 * editor de canción o al elegir "Popularidad" en el diálogo de ordenar. Si ahí se le da a "Salir",
 * esa entrada no vuelve a ofrecerse sola (`setupDeclined` queda fijo en disco a propósito, para no
 * insistir). Esta sección es la única puerta para volver a abrir esos diálogos después de eso —haya
 * token/clave ya puestos (para cambiarlos) o no (para configurarlos por fin)—, así que aquí se
 * llaman directamente, sin pasar por `shouldOfferSetup`: eso es solo para las ofertas automáticas de
 * los otros sitios, no para una acción que el usuario pide a propósito.
 *
 * La traducción de letras (botón "あ" del iPod) NO está aquí: usa Lingva Translate (ver
 * [com.untar.ultimusic.util.LyricsTranslator]), que no pide clave ni cuenta, así que no tiene nada
 * que configurar.
 */
class SettingsDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var audioSectionTitle: TextView
    private lateinit var visualSectionTitle: TextView
    private lateinit var languageTitle: TextView
    private lateinit var languageSpainButton: View
    private lateinit var languageEnglishButton: View
    private lateinit var languageSpainBorder: View
    private lateinit var languageEnglishBorder: View
    private lateinit var boostTitle: TextView
    private lateinit var slider: Slider
    private lateinit var ruler: ValueRuler
    private lateinit var valueInput: EditText
    private lateinit var noLimitCheck: MaterialCheckBox
    private lateinit var libraryRootsTitle: TextView
    private lateinit var libraryRootAdapter: LibraryRootAdapter
    private lateinit var greylistTitle: TextView
    private lateinit var greylistAdapter: GreylistAdapter
    private lateinit var artistGroupingTitle: TextView
    private lateinit var artistGroupingValue: EditText
    private lateinit var addAllLyricsTitle: TextView
    private lateinit var btnAddAllLyrics: TextView

    private lateinit var eqTitle: TextView
    private lateinit var eqUnavailable: View
    private lateinit var eqSwitch: MaterialSwitch

    /** Gris de fábrica de [eqSwitch], capturado antes de pisar su `trackTintList` la primera vez
     *  (ver [com.untar.ultimusic.util.DynamicColor.switchTrackTint]). */
    private var eqSwitchDefaultTrackTint: ColorStateList? = null
    private lateinit var eqOptionsContainer: View
    private lateinit var eqPresetsSpinner: Spinner
    private lateinit var btnSavePreset: View
    private lateinit var btnDeletePreset: View
    private lateinit var preGainSlider: Slider
    private lateinit var btnResetEq: View
    private lateinit var additionalOptionsCheck: MaterialCheckBox
    private lateinit var additionalOptionsContainer: View
    private lateinit var bassBoostSlider: Slider
    private lateinit var bassBoostUnsupported: View
    private lateinit var virtualizerSlider: Slider
    private lateinit var virtualizerUnsupported: View
    private lateinit var reverbSpinner: Spinner
    private lateinit var reverbUnsupported: View
    private lateinit var dynamicsSlider: Slider
    private lateinit var dynamicsUnsupported: View

    private lateinit var servicesSectionTitle: TextView
    private lateinit var geniusTitle: TextView
    private lateinit var geniusStatus: TextView
    private lateinit var btnConfigureGenius: TextView
    private lateinit var youtubeTitle: TextView
    private lateinit var recountSectionTitle: TextView
    private lateinit var youtubeStatus: TextView
    private lateinit var btnConfigureYoutube: TextView

    /** Un slider por banda del ecualizador, en el mismo orden que [PlayerViewModel.eqBands]. */
    private var eqBandSliders: List<Slider> = emptyList()

    /**
     * Nombres que pinta [eqPresetsSpinner], en orden: los predefinidos, "Usuario" (una entrada fija
     * que indica que hay cambios sin guardar; elegirla a mano no hace nada, ver
     * [PlayerViewModel.applyPreset]) y al final los guardados por el usuario.
     */
    private lateinit var presetsAdapter: ArrayAdapter<String>

    /** Evita que [eqPresetsSpinner] dispare `applyPreset` al repintarse desde el propio ViewModel. */
    private var updatingPresetSpinner = false

    /**
     * Bandera de "lo estoy cambiando yo".
     *
     * Los tres controles (slider, regla y caja de texto) muestran el mismo número, así que al mover
     * uno hay que actualizar los otros dos. Sin esta bandera se entraría en bucle: el slider avisa,
     * eso escribe en la caja, la caja avisa de que ha cambiado, eso mueve el slider... Mientras está
     * en true, las reacciones de los otros controles se ignoran.
     */
    private var updatingUi = false

    /** Misma bandera que [updatingUi] pero para los controles del ecualizador, independiente de ella. */
    private var updatingEqUi = false

    /** Misma bandera que [updatingUi] pero para [artistGroupingValue], independiente de ella. */
    private var updatingArtistGroupingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_settings, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.settingsRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.settingsToolbar)
        audioSectionTitle = view.findViewById(R.id.audioSectionTitle)
        visualSectionTitle = view.findViewById(R.id.visualSectionTitle)
        languageTitle = view.findViewById(R.id.languageTitle)
        languageSpainButton = view.findViewById(R.id.languageSpainButton)
        languageEnglishButton = view.findViewById(R.id.languageEnglishButton)
        languageSpainBorder = view.findViewById(R.id.languageSpainSelectedBorder)
        languageEnglishBorder = view.findViewById(R.id.languageEnglishSelectedBorder)
        boostTitle = view.findViewById(R.id.boostTitle)
        slider = view.findViewById(R.id.boostSlider)
        ruler = view.findViewById(R.id.boostRuler)
        valueInput = view.findViewById(R.id.boostValue)
        noLimitCheck = view.findViewById(R.id.boostNoLimit)
        libraryRootsTitle = view.findViewById(R.id.libraryRootsTitle)
        val libraryRootsRecycler = view.findViewById<RecyclerView>(R.id.libraryRootsRecycler)
        val addLibraryRootButton = view.findViewById<View>(R.id.btnAddLibraryRoot)
        greylistTitle = view.findViewById(R.id.greylistTitle)
        val greylistRecycler = view.findViewById<RecyclerView>(R.id.greylistRecycler)
        val addGreylistFolderButton = view.findViewById<View>(R.id.btnAddGreylistFolder)
        artistGroupingTitle = view.findViewById(R.id.artistGroupingTitle)
        artistGroupingValue = view.findViewById(R.id.artistGroupingValue)
        addAllLyricsTitle = view.findViewById(R.id.addAllLyricsTitle)
        btnAddAllLyrics = view.findViewById(R.id.btnAddAllLyrics)
        eqTitle = view.findViewById(R.id.eqTitle)
        eqUnavailable = view.findViewById(R.id.eqUnavailable)
        eqSwitch = view.findViewById(R.id.eqSwitch)
        eqSwitchDefaultTrackTint = eqSwitch.trackTintList
        eqOptionsContainer = view.findViewById(R.id.eqOptionsContainer)
        eqPresetsSpinner = view.findViewById(R.id.eqPresetsSpinner)
        btnSavePreset = view.findViewById(R.id.btnSavePreset)
        btnDeletePreset = view.findViewById(R.id.btnDeletePreset)
        preGainSlider = view.findViewById(R.id.preGainSlider)
        val eqBandsContainer = view.findViewById<LinearLayout>(R.id.eqBandsContainer)
        btnResetEq = view.findViewById(R.id.btnResetEq)
        additionalOptionsCheck = view.findViewById(R.id.additionalOptionsCheck)
        additionalOptionsContainer = view.findViewById(R.id.additionalOptionsContainer)
        bassBoostSlider = view.findViewById(R.id.bassBoostSlider)
        bassBoostUnsupported = view.findViewById(R.id.bassBoostUnsupported)
        virtualizerSlider = view.findViewById(R.id.virtualizerSlider)
        virtualizerUnsupported = view.findViewById(R.id.virtualizerUnsupported)
        reverbSpinner = view.findViewById(R.id.reverbSpinner)
        reverbUnsupported = view.findViewById(R.id.reverbUnsupported)
        dynamicsSlider = view.findViewById(R.id.dynamicsSlider)
        dynamicsUnsupported = view.findViewById(R.id.dynamicsUnsupported)
        servicesSectionTitle = view.findViewById(R.id.servicesSectionTitle)
        geniusTitle = view.findViewById(R.id.geniusTitle)
        geniusStatus = view.findViewById(R.id.geniusStatus)
        btnConfigureGenius = view.findViewById(R.id.btnConfigureGenius)
        youtubeTitle = view.findViewById(R.id.youtubeTitle)
        youtubeStatus = view.findViewById(R.id.youtubeStatus)
        btnConfigureYoutube = view.findViewById(R.id.btnConfigureYoutube)
        recountSectionTitle = view.findViewById(R.id.recountSectionTitle)
        val openRecountButton = view.findViewById<TextView>(R.id.btnOpenRecount)

        // La barra de estado es transparente en este tema, así que la toolbar se aparta ella sola
        // de la hora y la batería con un padding del tamaño de ese hueco.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
            toolbar.updatePadding(top = bars.top)
            insets
        }

        toolbar.setNavigationOnClickListener { dismiss() }

        ruler.minValue = BOOST_MIN_PERCENT
        // El tope existe por obligación técnica, no de diseño: está tan lejos que arrastrando no se
        // llega nunca por accidente (ver la cabecera de ValueRuler).
        ruler.maxValue = BOOST_MAX_PERCENT

        setupBoostControls()
        setupLanguage()
        setupLibraryRoots(libraryRootsRecycler, addLibraryRootButton)
        setupGreylist(greylistRecycler, addGreylistFolderButton)
        setupArtistGrouping()
        setupBulkLyrics()
        setupEqualizer(eqBandsContainer)
        setupServices()
        setupRecount(openRecountButton)
        observeState()
    }

    /**
     * Las dos banderas del selector de idioma (ver [AppLocale]): tocar una cambia el idioma de toda
     * la aplicación al instante -[AppLocale.select] recrea sola cualquier Activity viva- y mueve el
     * borde marcado a la bandera recién elegida. Tocar la que ya está seleccionada no hace nada.
     */
    private fun setupLanguage() {
        updateLanguageSelection()
        languageSpainButton.setOnClickListener {
            AppLocale.select(spanish = true)
            updateLanguageSelection()
        }
        languageEnglishButton.setOnClickListener {
            AppLocale.select(spanish = false)
            updateLanguageSelection()
        }
    }

    private fun updateLanguageSelection() {
        val spanish = AppLocale.isSpanishSelected()
        languageSpainBorder.isVisible = spanish
        languageEnglishBorder.isVisible = !spanish
    }

    /**
     * Monta la lista de carpetas raíz adicionales, el botón que pide una carpeta NUEVA con el
     * selector del propio sistema (`ActivityResultContracts.OpenDocumentTree`: a diferencia de la
     * lista gris, aquí sí hace falta un permiso nuevo, ver [com.untar.ultimusic.util.SafStorage]) y la
     * papelera de cada fila.
     */
    private fun setupLibraryRoots(recycler: RecyclerView, addFolderButton: View) {
        libraryRootAdapter = LibraryRootAdapter(onDelete = { root -> showRemoveLibraryRootDialog(root) })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = libraryRootAdapter

        addFolderButton.setOnClickListener { addLibraryRootLauncher.launch(null) }
    }

    private val addLibraryRootLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            when (settingsViewModel.tryAddLibraryRoot(uri)) {
                SettingsViewModel.AddLibraryRootResult.SUCCESS -> Unit
                SettingsViewModel.AddLibraryRootResult.ALREADY_COVERED ->
                    toast(R.string.settings_library_root_error_nested)
            }
        }

    /**
     * Confirmación antes de quitar una carpeta raíz: a diferencia de la lista gris, aquí sí hay
     * riesgo real de perder datos (la próxima reconciliación borra de verdad las canciones exclusivas
     * de esa carpeta si no reaparecen con el mismo nombre en otra vigilada, ver
     * [com.untar.ultimusic.data.LibraryRepository.removeLibraryRoot]).
     */
    private fun showRemoveLibraryRootDialog(root: LibraryRoot) {
        val treeUri = Uri.parse(root.path)
        val name = SafStorage.docPathOfTree(treeUri)?.substringAfterLast('/') ?: root.path
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_library_root_delete_desc)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.settings_library_roots_remove_confirm), name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> settingsViewModel.removeLibraryRoot(treeUri) }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /**
     * Monta la lista de subcarpetas, el botón que abre el explorador de carpetas y la papelera de
     * cada fila. El resultado del explorador ([FolderPickerDialogFragment]) se escucha en el
     * `childFragmentManager`, porque se abre encima de esta pantalla con `show(childFragmentManager, ...)`.
     */
    private fun setupGreylist(recycler: RecyclerView, addFolderButton: View) {
        greylistAdapter = GreylistAdapter(
            onToggle = { folder, excluded -> settingsViewModel.setGreylistFolderExcluded(folder.path, excluded) },
            onDelete = { folder -> showRemoveGreylistFolderDialog(folder) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = greylistAdapter

        addFolderButton.setOnClickListener {
            FolderPickerDialogFragment.newInstance().show(childFragmentManager, FolderPickerDialogFragment.TAG)
        }

        childFragmentManager.setFragmentResultListener(
            FolderPickerDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString(FolderPickerDialogFragment.RESULT_PATH)?.let(settingsViewModel::addGreylistFolder)
        }
    }

    /** Confirmación antes de quitar una subcarpeta, dejando claro que no se borra del dispositivo. */
    private fun showRemoveGreylistFolderDialog(folder: GreylistFolder) {
        val name = folder.path.substringAfterLast('/')
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_greylist_delete_desc)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.settings_greylist_remove_confirm), name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> settingsViewModel.removeGreylistFolder(folder.path) }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /**
     * "Añadir todas las letras", al final de "ajustes visuales" (ver [BulkLyricsAdder]): pide
     * confirmación y, si se acepta, lanza el pase y abre su diálogo de progreso.
     *
     * Si ya hay un pase en marcha —el usuario lo mandó a segundo plano y ha vuelto a Ajustes, o
     * simplemente lo dejó corriendo y reabrió esta pantalla— se reabre directamente el diálogo de
     * progreso, sin volver a pedir confirmación: ya se dio una vez para ESTE pase.
     */
    private fun setupBulkLyrics() {
        btnAddAllLyrics.setOnClickListener {
            if (BulkLyricsAdder.isRunning) showBulkLyricsProgress() else showBulkLyricsConfirm()
        }
    }

    private fun showBulkLyricsConfirm() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_bulk_lyrics_confirm_title)
            .setMessage(R.string.settings_bulk_lyrics_confirm_message)
            .setNegativeButton(R.string.dialog_no, null)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                BulkLyricsAdder.start(requireContext())
                showBulkLyricsProgress()
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    private fun showBulkLyricsProgress() {
        if (childFragmentManager.findFragmentByTag(BulkLyricsProgressDialogFragment.TAG) != null) return
        BulkLyricsProgressDialogFragment().show(childFragmentManager, BulkLyricsProgressDialogFragment.TAG)
    }

    /**
     * Cablea los botones de Genius y YouTube: cada uno abre su diálogo de "Acción requerida" de
     * siempre —[GeniusTokenDialogFragment], [YouTubeApiKeyDialogFragment]— directamente, sin mirar
     * `shouldOfferSetup` (eso es solo para la oferta automática de los otros sitios). Es la única
     * forma de volver a entrar ahí si el usuario rechazó la configuración en su momento, ver la
     * cabecera de la clase.
     */
    private fun setupServices() {
        refreshServiceStatus()

        btnConfigureGenius.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(GeniusTokenDialogFragment.TAG) == null) {
                GeniusTokenDialogFragment.newInstance()
                    .show(childFragmentManager, GeniusTokenDialogFragment.TAG)
            }
        }
        btnConfigureYoutube.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(YouTubeApiKeyDialogFragment.TAG) == null) {
                YouTubeApiKeyDialogFragment.newInstance()
                    .show(childFragmentManager, YouTubeApiKeyDialogFragment.TAG)
            }
        }
        // Los dos diálogos avisan de cómo han acabado tanto si se configura como si se rechaza (ver
        // su RESULT_KEY); aquí interesan los dos casos por igual, porque los dos pueden cambiar lo
        // que hay que pintar (un token/clave nuevo, o un rechazo que vuelve a dejarlo en "No
        // configurado").
        childFragmentManager.setFragmentResultListener(
            GeniusTokenDialogFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> refreshServiceStatus() }
        childFragmentManager.setFragmentResultListener(
            YouTubeApiKeyDialogFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> refreshServiceStatus() }
    }

    /** Repinta el estado ("Configurado"/"No configurado") y el texto del botón de cada servicio.
     *  Se llama al abrir la pantalla y después de cada vuelta de sus diálogos. */
    private fun refreshServiceStatus() {
        geniusStatus.setText(
            if (GeniusTokenStore.hasUserToken) R.string.settings_service_status_configured
            else R.string.settings_service_status_not_configured
        )
        btnConfigureGenius.setText(
            if (GeniusTokenStore.hasUserToken) R.string.settings_genius_reconfigure
            else R.string.settings_genius_configure
        )
        youtubeStatus.setText(
            if (YouTubeApiKeyStore.hasUserKey) R.string.settings_service_status_configured
            else R.string.settings_service_status_not_configured
        )
        btnConfigureYoutube.setText(
            if (YouTubeApiKeyStore.hasUserKey) R.string.settings_youtube_reconfigure
            else R.string.settings_youtube_configure
        )
    }

    /**
     * Infla un [R.layout.item_eq_band] por cada banda que reporte [PlayerViewModel.eqBands] (su
     * número y rango dependen del dispositivo, por eso no están fijos en el XML) y cablea todos los
     * mandos del ecualizador. Si el dispositivo no tiene el efecto, la sección entera se deshabilita
     * en vez de dejar sliders que no hacen nada.
     */
    private fun setupEqualizer(bandsContainer: LinearLayout) {
        val bands = playerViewModel.eqBands
        eqUnavailable.isVisible = bands.isEmpty()
        eqSwitch.isEnabled = bands.isNotEmpty()

        val inflater = LayoutInflater.from(requireContext())
        eqBandSliders = bands.map { band ->
            val row = inflater.inflate(R.layout.item_eq_band, bandsContainer, false)
            val bandSlider = row.findViewById<Slider>(R.id.eqBandSlider)
            row.findViewById<TextView>(R.id.eqBandFreq).text = formatFreq(band.centerFreqHz)
            bandSlider.valueFrom = band.minLevelMb.toFloat()
            bandSlider.valueTo = band.maxLevelMb.toFloat()
            bandsContainer.addView(row)
            bandSlider
        }
        eqBandSliders.forEachIndexed { index, bandSlider ->
            bandSlider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser || updatingEqUi) return@addOnChangeListener
                playerViewModel.setEqBandLevel(index, value.toInt())
            }
        }

        eqSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingEqUi) return@setOnCheckedChangeListener
            playerViewModel.setEqEnabled(checked)
        }

        setupEqPresets()

        preGainSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingEqUi) return@addOnChangeListener
            playerViewModel.setEqPreGain(value.toInt())
        }

        bassBoostUnsupported.isVisible = !playerViewModel.bassBoostSupported
        bassBoostSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingEqUi) return@addOnChangeListener
            playerViewModel.setBassBoostStrength(value.toInt())
        }

        virtualizerUnsupported.isVisible = !playerViewModel.virtualizerSupported
        virtualizerSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingEqUi) return@addOnChangeListener
            playerViewModel.setVirtualizerStrength(value.toInt())
        }

        btnResetEq.isEnabled = bands.isNotEmpty()
        btnResetEq.setOnClickListener { playerViewModel.resetEqualizer() }

        setupAdditionalOptions()
    }

    /**
     * Refuerzo de graves, sonido envolvente, reverberación y compresor de rango dinámico: los
     * cuatro efectos que no son bandas del ecualizador (ver el bloque "Ecualizador" de
     * [PlayerViewModel]). Van tras la casilla "Opciones adicionales", ocultos hasta que se marca, y
     * cada uno lleva un botón de ayuda que explica qué hace y por qué no basta con los sliders
     * verticales.
     */
    private fun setupAdditionalOptions() {
        additionalOptionsContainer.isVisible = additionalOptionsCheck.isChecked
        additionalOptionsCheck.setOnCheckedChangeListener { _, checked ->
            additionalOptionsContainer.isVisible = checked
        }

        requireView().findViewById<View>(R.id.btnHelpBassBoost).setOnClickListener {
            showHelpDialog(R.string.settings_eq_bass_boost_help_title, R.string.settings_eq_bass_boost_help_body)
        }
        requireView().findViewById<View>(R.id.btnHelpVirtualizer).setOnClickListener {
            showHelpDialog(R.string.settings_eq_virtualizer_help_title, R.string.settings_eq_virtualizer_help_body)
        }
        requireView().findViewById<View>(R.id.btnHelpReverb).setOnClickListener {
            showHelpDialog(R.string.settings_eq_reverb_help_title, R.string.settings_eq_reverb_help_body)
        }
        requireView().findViewById<View>(R.id.btnHelpDynamics).setOnClickListener {
            showHelpDialog(R.string.settings_eq_dynamics_help_title, R.string.settings_eq_dynamics_help_body)
        }

        val reverbAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, playerViewModel.reverbPresetNames
        )
        reverbAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        reverbSpinner.adapter = reverbAdapter
        reverbSpinner.isEnabled = playerViewModel.reverbSupported
        reverbUnsupported.isVisible = !playerViewModel.reverbSupported
        reverbSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingEqUi) return
                playerViewModel.setReverbPreset(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        dynamicsUnsupported.isVisible = !playerViewModel.dynamicsProcessingSupported
        dynamicsSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingEqUi) return@addOnChangeListener
            playerViewModel.setDynamicsStrength(value.toInt())
        }
    }

    /** Diálogo de ayuda de un control del ecualizador: qué hace y por qué está ahí. */
    private fun showHelpDialog(titleRes: Int, bodyRes: Int) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /**
     * Monta el desplegable de presets: los predefinidos, luego "Usuario" (el que se selecciona
     * solo al tocar cualquier control a mano, ver [PlayerViewModel.markEqAsModified]) y al final
     * los guardados por el usuario. Elegir uno aplica sus valores; el propio ViewModel decide si
     * lo que se ha elegido es un preset de verdad o el aviso "Usuario" (que no hace nada).
     */
    private fun setupEqPresets() {
        presetsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item)
        presetsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        eqPresetsSpinner.adapter = presetsAdapter
        eqPresetsSpinner.isEnabled = playerViewModel.eqBands.isNotEmpty()
        btnSavePreset.isEnabled = playerViewModel.eqBands.isNotEmpty()

        refreshPresetSpinnerItems(playerViewModel.userPresets.value)
        selectPresetInSpinner(playerViewModel.currentPresetName.value)

        eqPresetsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingPresetSpinner) return
                presetsAdapter.getItem(position)?.let(playerViewModel::applyPreset)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSavePreset.setOnClickListener { showSavePresetDialog() }
        btnDeletePreset.setOnClickListener { showDeletePresetConfirm() }
    }

    /** Reconstruye la lista de nombres del desplegable: predefinidos, "Usuario", guardados. */
    private fun refreshPresetSpinnerItems(userPresets: List<EqPreset>) {
        val items = mutableListOf<String>()
        items.addAll(playerViewModel.predefinedPresetNames)
        items.add(playerViewModel.userModifiedPresetName)
        items.addAll(userPresets.map { it.name })
        presetsAdapter.clear()
        presetsAdapter.addAll(items)
    }

    /** Pone [name] como seleccionado en el desplegable sin disparar `applyPreset`. */
    private fun selectPresetInSpinner(name: String) {
        val position = presetsAdapter.getPosition(name)
        if (position < 0) return
        updatingPresetSpinner = true
        eqPresetsSpinner.setSelection(position)
        updatingPresetSpinner = false
    }

    /** La papelera de borrar preset solo tiene sentido si lo seleccionado es un preset guardado. */
    private fun updateDeletePresetButtonVisibility(currentName: String, userPresetNames: List<String>) {
        btnDeletePreset.isVisible = currentName in userPresetNames
    }

    /** Pide un nombre y guarda la configuración actual (bandas + refuerzos) como preset nuevo. */
    private fun showSavePresetDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val accent = playerViewModel.accentColor.value
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.settings_eq_save_preset_name)
            val current = playerViewModel.currentPresetName.value
            if (current != playerViewModel.userModifiedPresetName) setText(current)
            // Mismo tinte por estados que en PlaylistsFragment.showNameDialog para la rayita de
            // debajo del campo: sin esto se queda en el amarillo fijo de colorControlActivated.
            backgroundTintList = AccentTint.underline(requireContext(), accent)
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_eq_save_preset)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                val reserved = playerViewModel.predefinedPresetNames + playerViewModel.userModifiedPresetName
                if (name.isEmpty() || name in reserved) {
                    toast(R.string.settings_eq_preset_name_invalid)
                } else {
                    playerViewModel.saveUserPreset(name)
                    toast(R.string.settings_eq_preset_saved)
                }
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /** Confirmación antes de borrar el preset guardado que esté seleccionado ahora mismo. */
    private fun showDeletePresetConfirm() {
        val name = playerViewModel.currentPresetName.value
        if (playerViewModel.userPresets.value.none { it.name == name }) return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_eq_delete_preset)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.settings_eq_delete_preset_confirm), name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                playerViewModel.deleteUserPreset(name)
                toast(R.string.settings_eq_preset_deleted)
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
    }

    /** "230" -> "230 Hz", "1200" -> "1.2 kHz". Con Locale.ROOT para que el punto no cambie con el idioma. */
    private fun formatFreq(hz: Int): String =
        if (hz >= 1000) String.format(java.util.Locale.ROOT, "%.1f kHz", hz / 1000f) else "$hz Hz"

    /**
     * Con el interruptor apagado, [eqOptionsContainer] entero se oculta (presets, bandas, ganancia
     * previa, reinicio y "Opciones adicionales"): es la primera de las dos capas de ocultación de
     * la sección. La segunda es [additionalOptionsContainer], que depende de
     * [additionalOptionsCheck] y no de este interruptor.
     *
     * Los refuerzos (graves, envolvente, reverberación, dinámica) además se deshabilitan si el
     * dispositivo no los soporta, aunque el interruptor esté encendido: eso es independiente de la
     * visibilidad y ya se anuncia con el texto "no disponible" de cada uno.
     */
    private fun applyEqInteractivity(enabled: Boolean) {
        eqOptionsContainer.isVisible = enabled
        bassBoostSlider.isEnabled = enabled && playerViewModel.bassBoostSupported
        virtualizerSlider.isEnabled = enabled && playerViewModel.virtualizerSupported
        reverbSpinner.isEnabled = enabled && playerViewModel.reverbSupported
        dynamicsSlider.isEnabled = enabled && playerViewModel.dynamicsProcessingSupported
    }

    private fun setupBoostControls() {
        // `fromUser` distingue mover el slider con el dedo de moverlo por código: solo el primero es
        // un cambio de verdad del usuario.
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingUi) return@addOnChangeListener
            playerViewModel.setVolumeBoost(value.toInt())
        }

        ruler.onValueChanged = { value ->
            if (!updatingUi) playerViewModel.setVolumeBoost(value)
        }

        valueInput.doAfterTextChanged { text ->
            if (updatingUi) return@doAfterTextChanged
            // Solo se aplica si lo escrito ya es un número válido. Mientras se teclea "120" se pasa
            // por "1" y por "12": corregirlos al vuelo al mínimo pelearía con el usuario, así que
            // los valores fuera de rango se dejan estar y se cuadran al terminar (ver
            // [normalizeInput]).
            val typed = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (typed >= BOOST_MIN_PERCENT) playerViewModel.setVolumeBoost(typed)
        }

        // Al pulsar "hecho" o al perder el foco, lo escrito se cuadra con lo que de verdad se ha
        // aplicado (por si se quedó "5" o "9999" con el límite puesto).
        valueInput.setOnEditorActionListener { _, _, _ ->
            normalizeInput()
            hideKeyboard()
            true
        }
        valueInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) normalizeInput() }

        // setOnClickListener y no setOnCheckedChangeListener: hay que poder devolver la casilla a su
        // sitio cuando se rechaza el cambio, y con el listener de "checked" eso volvería a
        // dispararlo y entraría en bucle.
        noLimitCheck.setOnClickListener { tryDisableLimit(noLimitCheck.isChecked) }
    }

    /**
     * Pide al ViewModel quitar (o reponer) el tope y reacciona a lo que conteste.
     *
     * Reponerlo siempre se puede. Quitarlo depende de por dónde esté saliendo el sonido:
     *
     * - **Nada en la oreja** (altavoz del móvil, altavoz Bluetooth, coche…) — se aplica.
     * - **Auriculares** — se rechaza y se explica.
     * - **Bluetooth sin identificar** — no se rechaza del todo: falta el permiso para preguntarle al
     *   aparato qué es, así que se le pide al usuario y, si lo concede, se vuelve a intentar. Es
     *   mejor que dar un «no» sin explicación cuando lo conectado es un altavoz.
     */
    private fun tryDisableLimit(wanted: Boolean) {
        when (playerViewModel.setLimitDisabled(wanted)) {
            Headphones.Check.NONE -> Unit  // Aplicado; la casilla ya está donde toca.

            Headphones.Check.CONNECTED -> {
                noLimitCheck.isChecked = false
                toast(R.string.settings_boost_no_limit_headphones)
            }

            Headphones.Check.UNKNOWN_BLUETOOTH -> {
                noLimitCheck.isChecked = false
                // Antes de Android 12 no hay ningún permiso que pedir: si aun así no se ha podido
                // identificar el aparato, no hay nada más que hacer que explicarlo.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    toast(R.string.settings_boost_device_unknown)
                }
            }
        }
    }

    /**
     * Petición del permiso de Bluetooth. Se registra como campo porque `registerForActivityResult`
     * exige hacerlo **antes** de que el fragmento llegue a STARTED: si se llamara desde el clic de
     * la casilla, ya sería tarde y reventaría.
     *
     * Si el usuario concede, se reintenta quitar el tope: ahora la comprobación ya sabrá si el
     * aparato es un altavoz (y entonces se aplica) o unos auriculares (y entonces se rechaza con su
     * aviso). Si no concede, se explica por qué se queda el tope.
     */
    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // Si sale bien, la casilla se marca sola: el cambio llega por el flujo
                // `limitDisabled` y lo pinta showMode().
                tryDisableLimit(true)
            } else {
                toast(R.string.settings_boost_bluetooth_denied)
            }
        }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()
    }

    /** Deja en la caja de texto el valor que de verdad está aplicado. */
    private fun normalizeInput() {
        updatingUi = true
        valueInput.setText(playerViewModel.volumeBoost.value.toString())
        updatingUi = false
    }

    /**
     * Caja de texto del umbral de "Otros" (ver [ArtistGroupingPreferences][com.untar.ultimusic.data.ArtistGroupingPreferences]).
     * Mismo patrón que [valueInput]/[normalizeInput] pero sin slider ni regla que sincronizar: se
     * aplica en caliente mientras lo escrito ya es un número válido, y al terminar (tecla "hecho" o
     * perder el foco) se cuadra con lo que de verdad quedó guardado, por si se quedó vacío o a medio
     * escribir.
     */
    private fun setupArtistGrouping() {
        artistGroupingValue.doAfterTextChanged { text ->
            if (updatingArtistGroupingUi) return@doAfterTextChanged
            val typed = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            settingsViewModel.setArtistGroupingThreshold(typed)
        }
        artistGroupingValue.setOnEditorActionListener { _, _, _ ->
            normalizeArtistGroupingInput()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(artistGroupingValue.windowToken, 0)
            artistGroupingValue.clearFocus()
            true
        }
        artistGroupingValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) normalizeArtistGroupingInput()
        }
    }

    private fun showArtistGroupingValue(value: Int) {
        updatingArtistGroupingUi = true
        if (artistGroupingValue.text.toString() != value.toString()) {
            artistGroupingValue.setText(value.toString())
        }
        updatingArtistGroupingUi = false
    }

    private fun normalizeArtistGroupingInput() {
        showArtistGroupingValue(settingsViewModel.artistGroupingThreshold.value)
    }

    /**
     * Sigue el estado del [PlayerViewModel] y repinta los controles.
     *
     * `repeatOnLifecycle(STARTED)` hace que la escucha se pare al irse la pantalla a segundo plano y
     * se reanude al volver, en vez de quedarse recibiendo cambios para una vista que no se ve.
     */
    /**
     * El acceso a UltiMusic Recount, el último de la pantalla. No tiene estado que refrescar (a
     * diferencia de Genius/YouTube, que enseñan si están configurados): es solo una puerta.
     *
     * Se abre sobre el `childFragmentManager` y no sobre el de la actividad porque este diálogo ya
     * está a pantalla completa y el Recount se muestra ENCIMA, dentro de él: el mismo criterio que
     * sigue [FolderPickerDialogFragment] aquí mismo. Al cerrarlo se vuelve a Ajustes, no a la
     * biblioteca.
     */
    private fun setupRecount(openButton: TextView) {
        // Solo existe la última semana de diciembre, la misma en que se ofrece con la notificación y
        // el aviso de arranque (ver RecountReminder.isInReminderWeek). Se decide al abrir Ajustes.
        val recountGroup = requireView().findViewById<View>(R.id.recountGroup)
        recountGroup.visibility = if (RecountReminder.isInReminderWeek()) View.VISIBLE else View.GONE
        openButton.setOnClickListener {
            // Entrar desde aquí también apaga el aviso de este año (ver RecountReminder.markSeen).
            RecountReminder.markSeen(requireContext())
            RecountDialogFragment.show(childFragmentManager)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playerViewModel.volumeBoost.collect { percent -> showValue(percent) }
                }
                launch {
                    settingsViewModel.libraryRoots.collect { roots -> libraryRootAdapter.submit(roots) }
                }
                launch {
                    settingsViewModel.greylistFolders.collect { folders -> greylistAdapter.submit(folders) }
                }
                launch {
                    settingsViewModel.artistGroupingThreshold.collect { value -> showArtistGroupingValue(value) }
                }
                launch {
                    playerViewModel.limitDisabled.collect { disabled ->
                        showMode(disabled)
                        // Se vuelve a pintar el valor porque al cambiar de mando hay que pasárselo
                        // al que acaba de aparecer.
                        showValue(playerViewModel.volumeBoost.value)
                    }
                }
                launch {
                    playerViewModel.eqEnabled.collect { enabled ->
                        updatingEqUi = true
                        eqSwitch.isChecked = enabled
                        updatingEqUi = false
                        applyEqInteractivity(enabled)
                    }
                }
                launch {
                    playerViewModel.eqBandLevels.collect { levels ->
                        updatingEqUi = true
                        levels.forEachIndexed { index, level ->
                            eqBandSliders.getOrNull(index)?.let { s ->
                                val clamped = level.toFloat().coerceIn(s.valueFrom, s.valueTo)
                                if (s.value != clamped) s.value = clamped
                            }
                        }
                        updatingEqUi = false
                    }
                }
                launch {
                    playerViewModel.bassBoostStrength.collect { percent ->
                        updatingEqUi = true
                        bassBoostSlider.value = percent.toFloat()
                        updatingEqUi = false
                    }
                }
                launch {
                    playerViewModel.virtualizerStrength.collect { percent ->
                        updatingEqUi = true
                        virtualizerSlider.value = percent.toFloat()
                        updatingEqUi = false
                    }
                }
                launch {
                    playerViewModel.reverbPreset.collect { preset ->
                        updatingEqUi = true
                        if (reverbSpinner.selectedItemPosition != preset) reverbSpinner.setSelection(preset)
                        updatingEqUi = false
                    }
                }
                launch {
                    playerViewModel.dynamicsStrength.collect { percent ->
                        updatingEqUi = true
                        dynamicsSlider.value = percent.toFloat()
                        updatingEqUi = false
                    }
                }
                launch {
                    playerViewModel.eqPreGain.collect { percent ->
                        updatingEqUi = true
                        preGainSlider.value = percent.toFloat()
                        updatingEqUi = false
                    }
                }
                // El desplegable de presets y la papelera de borrar siguen tanto el preset activo
                // como la lista de guardados: cualquiera de los dos puede cambiar por separado (un
                // preset nuevo guardado, o simplemente tocar una banda a mano y pasar a "Usuario").
                launch {
                    playerViewModel.currentPresetName.collect { name ->
                        selectPresetInSpinner(name)
                        updateDeletePresetButtonVisibility(name, playerViewModel.userPresets.value.map { it.name })
                    }
                }
                launch {
                    playerViewModel.userPresets.collect { presets ->
                        refreshPresetSpinnerItems(presets)
                        selectPresetInSpinner(playerViewModel.currentPresetName.value)
                        updateDeletePresetButtonVisibility(playerViewModel.currentPresetName.value, presets.map { it.name })
                    }
                }
                // Los mandos se tiñen con el color de la canción que suena, como las pestañas y el
                // mini-reproductor (ver `MainActivity.setupDynamicColor`). Los títulos de sección y
                // subsección y los interruptores (ecualizador, cada carpeta de la lista gris)
                // llevaban el amarillo fijo del XML/tema aunque sonara una canción de otro color;
                // ahora siguen el mismo acento que el resto.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        val tint = ColorStateList.valueOf(accent)
                        audioSectionTitle.setTextColor(accent)
                        visualSectionTitle.setTextColor(accent)
                        languageTitle.setTextColor(accent)
                        AccentTint.stroke(languageSpainBorder, R.id.languageSpainSelectedBorder, accent, R.dimen.flag_selected_stroke_width)
                        AccentTint.stroke(languageEnglishBorder, R.id.languageEnglishSelectedBorder, accent, R.dimen.flag_selected_stroke_width)
                        boostTitle.setTextColor(accent)
                        slider.trackActiveTintList = tint
                        slider.thumbTintList = tint
                        ruler.accentColor = accent
                        noLimitCheck.buttonTintList = tint
                        additionalOptionsCheck.buttonTintList = tint
                        libraryRootsTitle.setTextColor(accent)
                        greylistTitle.setTextColor(accent)
                        greylistAdapter.setAccent(accent)
                        addAllLyricsTitle.setTextColor(accent)
                        eqTitle.setTextColor(accent)
                        // Solo se tiñe la pista (parte exterior) y solo en el estado encendido; el
                        // pomo se deja con el tinte por defecto del tema y el estado apagado
                        // mantiene su gris de fábrica en vez del acento.
                        eqSwitch.trackTintList = DynamicColor.switchTrackTint(accent, eqSwitchDefaultTrackTint)
                        eqBandSliders.forEach { s ->
                            s.trackActiveTintList = tint
                            s.thumbTintList = tint
                        }
                        preGainSlider.trackActiveTintList = tint
                        preGainSlider.thumbTintList = tint
                        bassBoostSlider.trackActiveTintList = tint
                        bassBoostSlider.thumbTintList = tint
                        virtualizerSlider.trackActiveTintList = tint
                        virtualizerSlider.thumbTintList = tint
                        dynamicsSlider.trackActiveTintList = tint
                        dynamicsSlider.thumbTintList = tint
                        servicesSectionTitle.setTextColor(accent)
                        geniusTitle.setTextColor(accent)
                        youtubeTitle.setTextColor(accent)
                        recountSectionTitle.setTextColor(accent)
                    }
                }
                // Si se conectan auriculares con el límite quitado, el ViewModel lo repone solo; aquí
                // solo hay que contarlo, porque el cambio de la casilla ya llega por el flujo de arriba.
                launch {
                    playerViewModel.limitForcedByHeadphones.collect {
                        Toast.makeText(
                            requireContext(),
                            R.string.settings_boost_no_limit_headphones,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /** Enseña el mando que toca: slider con límite, regla sin él. */
    private fun showMode(limitDisabled: Boolean) {
        updatingUi = true
        noLimitCheck.isChecked = limitDisabled
        slider.isVisible = !limitDisabled
        ruler.isVisible = limitDisabled
        updatingUi = false
    }

    /** Pone [percent] en los tres controles sin que se avisen entre ellos. */
    private fun showValue(percent: Int) {
        updatingUi = true
        // El slider revienta si se le da un valor fuera de su rango, así que se recorta: con el
        // límite quitado el valor puede pasar de 150 mientras el slider sigue existiendo (oculto).
        slider.value = percent.coerceIn(BOOST_MIN_PERCENT, BOOST_LIMIT_PERCENT).toFloat()
        if (ruler.currentValue != percent) ruler.setValue(percent, notify = false)
        // Solo se reescribe la caja si dice otra cosa: si no, escribir en ella movería el cursor al
        // principio a cada tecla.
        if (valueInput.text.toString() != percent.toString()) {
            valueInput.setText(percent.toString())
            valueInput.setSelection(valueInput.text.length)
        }
        updatingUi = false
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(valueInput.windowToken, 0)
        valueInput.clearFocus()
    }

    companion object {
        const val TAG = "settings"
    }
}
