package com.untar.ultimusic.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
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
import kotlinx.coroutines.launch
import java.io.File

/**
 * Explorador de carpetas propio de la app (no el selector de documentos del sistema): UltiMusic ya
 * lee el almacenamiento directamente con `File` gracias a `MANAGE_EXTERNAL_STORAGE`, así que no hace
 * falta pasar por Storage Access Framework ni traducir después una URI a una ruta real.
 *
 * Sirve a dos pantallas de ajustes con el mismo explorador, cada una con su propio punto de partida
 * y su propia clave de resultado (ver [newInstance]): la lista gris arranca en `UltiMusic` (para
 * elegir una subcarpeta que ocultar), y las carpetas raíz de la fonoteca arrancan en el
 * almacenamiento externo entero (para poder elegir una carpeta en cualquier parte del dispositivo).
 *
 * Empieza en [root] y deja navegar hacia dentro tocando una fila; el botón "Elegir esta carpeta"
 * confirma la que se esté viendo en ese momento (no hace falta llegar a una carpeta sin
 * subcarpetas). El resultado se devuelve con la API de resultados entre fragmentos
 * ([setFragmentResult]/`setFragmentResultListener`), igual que [VideoPickerDialogFragment
 * ][com.untar.ultimusic.ui.player.VideoPickerDialogFragment] hace con el iPod.
 */
class FolderPickerDialogFragment : DialogFragment() {

    // Mismo ViewModel que el resto de la app: es de donde sale el acento con el que se tiñe
    // btnChooseFolder (ver onViewCreated).
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private val root: File by lazy {
        File(requireArguments().getString(ARG_INITIAL_DIR)!!)
    }
    private val rootTitle: String? by lazy { requireArguments().getString(ARG_ROOT_TITLE) }
    private val requestKey: String by lazy { requireArguments().getString(ARG_REQUEST_KEY) ?: RESULT_KEY }
    private lateinit var currentDir: File

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: FolderPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
        currentDir = root
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
        val chooseButton = view.findViewById<MaterialButton>(R.id.btnChooseFolder)

        ViewCompat.setOnApplyWindowInsetsListener(pickerRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }

        adapter = FolderPickerAdapter { folder -> navigateTo(folder) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        toolbar.setNavigationOnClickListener { navigateUpOrDismiss() }
        chooseButton.setOnClickListener {
            setFragmentResult(requestKey, bundleOf(RESULT_PATH to currentDir.absolutePath))
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

        showFolder(currentDir)
    }

    private fun navigateTo(folder: File) {
        currentDir = folder
        showFolder(folder)
    }

    private fun navigateUpOrDismiss() {
        val parent = currentDir.parentFile
        if (currentDir == root || parent == null) {
            dismiss()
        } else {
            currentDir = parent
            showFolder(currentDir)
        }
    }

    private fun showFolder(folder: File) {
        toolbar.title = if (folder == root) {
            rootTitle ?: getString(R.string.folder_picker_root_title)
        } else {
            folder.absolutePath.removePrefix(root.absolutePath + "/")
        }

        val subfolders = folder.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        adapter.submit(subfolders)
        emptyView.isVisible = subfolders.isEmpty()
    }

    companion object {
        const val TAG = "folder_picker"

        /** Clave por defecto con la que la lista gris escucha el resultado (ver `setFragmentResultListener`). */
        const val RESULT_KEY = "folder_picker_result"
        const val RESULT_PATH = "path"

        private const val ARG_INITIAL_DIR = "initial_dir"
        private const val ARG_ROOT_TITLE = "root_title"
        private const val ARG_REQUEST_KEY = "request_key"

        /**
         * @param initialDir carpeta en la que arranca el explorador (por defecto, `UltiMusic`, para
         * no cambiar el comportamiento de la lista gris).
         * @param rootTitle título de la toolbar mientras se ve [initialDir]; si es null se usa
         * [R.string.folder_picker_root_title] ("UltiMusic").
         * @param requestKey clave de [setFragmentResult] con la que escuchar el resultado; por
         * defecto [RESULT_KEY], para no romper el punto de llamada de la lista gris.
         */
        fun newInstance(
            initialDir: File = File(Environment.getExternalStorageDirectory(), "UltiMusic"),
            rootTitle: String? = null,
            requestKey: String = RESULT_KEY
        ) = FolderPickerDialogFragment().apply {
            arguments = bundleOf(
                ARG_INITIAL_DIR to initialDir.absolutePath,
                ARG_ROOT_TITLE to rootTitle,
                ARG_REQUEST_KEY to requestKey
            )
        }
    }
}
