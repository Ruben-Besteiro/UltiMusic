package com.untar.ultimusic.ui.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.appbar.MaterialToolbar
import com.untar.ultimusic.R
import com.untar.ultimusic.util.YouTubeUrl

/**
 * Buscador de videoclips. Abre la web de YouTube DENTRO de la app (en un [WebView]) con la búsqueda
 * "título + artista" ya escrita, para que el usuario elija él mismo cuál es el vídeo bueno. En
 * cuanto pincha uno, se captura su identificador, se cierra esta ventana y se devuelve al iPod, que
 * lo guarda en la canción y lo reproduce.
 *
 * ¿Por qué la web y no la API de YouTube? Porque `search.list` cuesta 100 unidades de las 10.000
 * diarias que Google da por proyecto (unas 100 búsquedas al día **entre todos** los que instalen la
 * app), y porque los datos sacados de su API hay que borrarlos o refrescarlos cada 30 días, así que
 * el enlace no se podría guardar para siempre. Un enlace que elige el usuario no tiene ninguna de
 * las dos limitaciones: es dato suyo. Además acierta más que quedarse con el primer resultado, que
 * muchas veces es un lyric video o una versión en directo.
 *
 * El resultado se devuelve con la API de resultados entre fragmentos
 * ([setFragmentResult]/`setFragmentResultListener`), que es la forma recomendada de que dos
 * fragmentos se comuniquen sin que uno guarde una referencia al otro (una referencia directa se
 * quedaría colgando si el sistema recrea la ventana al girar la pantalla).
 */
class VideoPickerDialogFragment : DialogFragment() {

    private var webView: WebView? = null

    /** Evita devolver dos veces el resultado si el WebView notifica la misma URL más de una vez. */
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_video_picker, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.pickerRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.pickerToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        val web = view.findViewById<WebView>(R.id.pickerWebView)
        webView = web

        // YouTube es una web hecha entera con JavaScript: sin esto (y sin el almacenamiento del DOM,
        // que es donde guarda su estado) la página no llega ni a pintarse.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        web.webViewClient = object : WebViewClient() {
            /**
             * Salta en las navegaciones normales (cuando la página cambia de dirección de verdad).
             * Devolver true significa "ya me encargo yo": así impedimos que el WebView llegue a
             * cargar la página del vídeo, porque en cuanto lo hiciera empezaría a sonar aquí dentro.
             */
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(url)

            /**
             * La web móvil de YouTube es una SPA (single-page application): al tocar un vídeo NO
             * navega de verdad, solo reescribe la barra de direcciones con `history.pushState` y
             * cambia el contenido por JavaScript. Eso NO dispara `shouldOverrideUrlLoading`, así que
             * sin este segundo callback —que sí salta con los cambios de historial— los toques del
             * usuario pasarían desapercibidos y nunca detectaríamos qué vídeo ha elegido.
             */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                handleUrl(url)
            }
        }

        // El botón "atrás" retrocede dentro de la web (por ejemplo, de una búsqueda afinada a la
        // anterior) y solo cierra la ventana cuando ya no queda historial. Sin esto, un solo toque
        // cerraría el buscador entero y habría que volver a escribir la búsqueda.
        //
        // El despachador es el del DIÁLOGO, no el de la actividad: un DialogFragment se dibuja en su
        // propia ventana y es esa la que recibe el "atrás", así que registrarlo en la actividad no
        // serviría de nada. El `as?` es por prudencia: si algún día el diálogo dejara de ser un
        // ComponentDialog, el "atrás" volvería a su comportamiento normal (cerrar) en vez de fallar.
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) web.goBack() else dismiss()
                }
            }
        )

        web.loadUrl(YouTubeUrl.searchUrl(requireArguments().getString(ARG_QUERY).orEmpty()))
    }

    /**
     * Si [url] es la de un vídeo, devuelve su identificador al iPod y cierra. Devuelve true cuando
     * ha reconocido un vídeo, para que [WebViewClient.shouldOverrideUrlLoading] cancele la carga.
     */
    private fun handleUrl(url: String): Boolean {
        if (resultSent) return true
        val videoId = YouTubeUrl.videoId(url) ?: return false
        resultSent = true
        // Se corta la carga a medias: sin esto, el vídeo seguiría cargándose (y sonando) en el
        // WebView durante el instante que tarda la ventana en cerrarse.
        webView?.stopLoading()
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_VIDEO_ID to videoId))
        dismiss()
        return true
    }

    override fun onDestroyView() {
        // Un WebView sigue vivo (y ejecutando JavaScript, y sonando) aunque se quite de la pantalla:
        // hay que pararlo y destruirlo a mano o se queda de fondo consumiendo batería y datos.
        webView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        /** Clave con la que el iPod escucha el resultado (ver `setFragmentResultListener`). */
        const val RESULT_KEY = "video_picker_result"
        const val RESULT_VIDEO_ID = "videoId"

        private const val ARG_QUERY = "query"

        /** [query] es lo que se escribe en el buscador de YouTube: normalmente "Título Artista". */
        fun newInstance(query: String) = VideoPickerDialogFragment().apply {
            arguments = bundleOf(ARG_QUERY to query)
        }
    }
}
