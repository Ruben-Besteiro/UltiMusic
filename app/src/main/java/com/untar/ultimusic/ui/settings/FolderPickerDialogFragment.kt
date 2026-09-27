package com.untar.ultimusic.ui.settings

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.SafStorage
import kotlinx.coroutines.launch

/**
 * Explorador de carpetas propio de la app, para elegir una SUBCARPETA dentro de alguna de las
 * carpetas ya concedidas por Storage Access Framework (ver [SafStorage]: `UltiMusic` más cualquier
 * carpeta raíz adicional de "Ajustes > Carpetas de la fonoteca"): no hace falta pasar por el
 * selector del sistema, porque no se pide ningún permiso nuevo, solo se navega dentro de árboles que
 * la app ya puede leer.
 *
 * Sirve a la lista gris de ajustes (elegir una subcarpeta que ocultar, ver [SettingsDialogFragment]).
 * Para AÑADIR una raíz nueva de biblioteca (que sí necesita un permiso nuevo) se usa directamente
 * `ActivityResultContracts.OpenDocumentTree()` desde `SettingsDialogFragment`, no este diálogo.
 *
 * Con una única carpeta concedida, empieza directamente dentro de ella (como antes de que hubiera
 * más de una raíz posible). Con varias, empieza en un selector plano de raíces -para no asumir que la
 * fonoteca del usuario vive dentro de `UltiMusic` cuando en realidad puede estar en Download/Music/
 * cualquier otra carpeta añadida a mano-, y "subir" desde la raíz de cualquiera de ellas vuelve a ese
 * selector en vez de cerrar el diálogo de golpe. Tocar una fila navega hacia dentro; el botón "Elegir
 * esta carpeta" confirma la que se esté viendo en ese momento (no hace falta llegar a una carpeta sin
 * subcarpetas, ni tiene sentido mientras se está en el selector de raíces). El resultado se devuelve
 * con la API de resultados entre fragmentos ([setFragmentResult]/`setFragmentResultListener`), igual
 * que [VideoPickerDialogFragment][com.untar.ultimusic.ui.player.VideoPickerDialogFragment] hace con
 * el iPod, y es el docPath de la carpeta elegida (mismo formato que
 * [com.untar.ultimusic.data.db.entities.GreylistFolderEntity.path]).
 */
class FolderPickerDialogFragment : DialogFragment() {

    // Mismo ViewModel que el resto de la app: es de donde sale el acento con el que se tiñe
    // btnChooseFolder (ver onViewCreated).
    private val playerViewModel: PlayerViewModel by activityViewModels()

    /** Todas las carpetas concedidas ahora mismo (docPath -> Uri de árbol), `UltiMusic` primero y el
     *  resto en el mismo orden en que las devuelve [SafStorage.grantedRoots]. */
    private val roots: List<Pair<String, Uri>> by lazy {
        val ultiMusicPath = SafStorage.ultiMusicDocPath(requireContext())
        SafStorage.grantedRoots().sortedBy { (path, _) -> if (path == ultiMusicPath) "" else path.lowercase() }
    }
    private val requestKey: String by lazy { requireArguments().getString(ARG_REQUEST_KEY) ?: RESULT_KEY }

    /** Índice en [roots] de la raíz que se está viendo, o null mientras se ve el selector de raíces
     *  (solo posible con más de una raíz concedida, ver [onViewCreated]). */
    private var currentRootIndex: Int? = null
    private lateinit var currentDocPath: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var chooseButton: MaterialButton
    private lateinit var adapter: FolderPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
        // Con una sola raíz concedida no hace falta el selector: se entra directamente en ella, como
        // pasaba cuando `UltiMusic` era la única carpeta posible.
        if (roots.size == 1) {
            currentRootIndex = 0
            currentDocPath = roots[0].first
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_folder_picker, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pickerRoot = view.findViewById<View>(R.id.folderPickerRoot)
        toolbar = view.findViewById(R.id.folderPickerToolbar)
        recycler = view.findViewById(R.id.folderPickerRecycler)
        emptyView = view.findViewById(R.id.folderPickerEmpty)
        chooseButton = view.findViewById(R.id.btnChooseFolder)

        ViewCompat.setOnApplyWindowInsetsListener(pickerRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        adapter = FolderPickerAdapter { folder ->
            val rootIndex = currentRootIndex
            if (rootIndex == null) {
                // Estábamos en el selector de raíces: la fila tocada ES una raíz completa, se entra
                // en ella (no es una subcarpeta de nada todavía).
                currentRootIndex = folder.docPath.let { path -> roots.indexOfFirst { it.first == path } }
                currentDocPath = folder.docPath
            } else {
                navigateTo(folder.docPath)
            }
            showFolder()
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        toolbar.setNavigationOnClickListener { navigateUpOrDismiss() }
        chooseButton.setOnClickListener {
            setFragmentResult(requestKey, bundleOf(RESULT_PATH to currentDocPath))
            dismiss()
        }

        // Sin esto el botón se queda con el colorPrimary del tema (el amarillo fijo de siempre) en
        // vez de seguir el acento de la canción que suena, como el resto de la app.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.accentColor.collect { accent ->
                    chooseButton.backgroundTintList = ColorStateList.valueOf(accent)
                }
            }
        }

        // El "atrás" del sistema sube un nivel en vez de cerrar de golpe, igual que el buscador de
        // vídeo del iPod retrocede dentro de la web antes de cerrarse (ver VideoPickerDialogFragment).
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUpOrDismiss()
            }
        )

        showFolder()
    }

    private fun navigateTo(docPath: String) {
        currentDocPath = docPath
        showFolder()
    }

    /** ¿Se está viendo la raíz de [currentRootIndex] ahora mismo (ni el selector de raíces, ni una
     *  subcarpeta suya)? */
    private fun atRootTop(): Boolean {
        val index = currentRootIndex ?: return false
        return currentDocPath == roots[index].first
    }

    private fun navigateUpOrDismiss() {
        when {
            currentRootIndex == null -> dismiss() // ya en el selector de raíces: no hay más arriba
            atRootTop() && roots.size > 1 -> currentRootIndex = null // vuelve al selector de raíces
            atRootTop() -> dismiss() // única raíz concedida: comportamiento de siempre
            else -> currentDocPath = currentDocPath.substringBeforeLast('/', roots[currentRootIndex!!].first)
        }
        showFolder()
    }

    private fun showFolder() {
        val rootIndex = currentRootIndex
        if (rootIndex == null) {
            // Selector de raíces: cada una se pinta como si fuera una carpeta de primer nivel.
            toolbar.title = getString(R.string.folder_picker_choose_root_title)
            chooseButton.isVisible = false
            val entries = roots.map { (docPath, _) ->
                SafStorage.SafEntry(
                    docPath = docPath,
                    name = docPath.takeIf { it.isNotEmpty() } ?: getString(R.string.folder_picker_full_storage),
                    isDirectory = true,
                    lastModified = 0L
                )
            }
            adapter.submit(entries)
            emptyView.isVisible = entries.isEmpty()
            return
        }

        val (rootDocPath, treeUri) = roots[rootIndex]
        toolbar.title = if (currentDocPath == rootDocPath) {
            rootDocPath.takeIf { it.isNotEmpty() } ?: getString(R.string.folder_picker_full_storage)
        } else {
            currentDocPath.removePrefix("$rootDocPath/")
        }
        chooseButton.isVisible = true

        val subfolders = SafStorage.listChildren(requireContext(), treeUri, currentDocPath)
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
        adapter.submit(subfolders)
        emptyView.isVisible = subfolders.isEmpty()
    }

    companion object {
        const val TAG = "folder_picker"

        /** Clave por defecto con la que la lista gris escucha el resultado (ver `setFragmentResultListener`). */
        const val RESULT_KEY = "folder_picker_result"
        const val RESULT_PATH = "path"

        private const val ARG_REQUEST_KEY = "request_key"

        /**
         * @param requestKey clave de [setFragmentResult] con la que escuchar el resultado; por
         * defecto [RESULT_KEY].
         */
        fun newInstance(requestKey: String = RESULT_KEY) = FolderPickerDialogFragment().apply {
            arguments = bundleOf(ARG_REQUEST_KEY to requestKey)
        }
    }
}
