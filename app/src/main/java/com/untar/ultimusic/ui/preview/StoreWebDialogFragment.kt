package com.untar.ultimusic.ui.preview

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.StoreDownloader
import kotlinx.coroutines.launch

/**
 * La web de una tienda de descarga (ver [StoreLinksDialogFragment]) DENTRO de la app, en un
 * [WebView], en vez de abrir el navegador. Mismo esqueleto que
 * [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]: diálogo a pantalla completa, "atrás"
 * navega por el historial de la web y solo cierra cuando ya no queda, y el WebView se destruye a
 * mano al cerrar.
 *
 * Todo lo que la página ofrezca descargar lo recoge [StoreDownloader] (un WebView no descarga nada
 * por sí solo) y lo guarda en la carpeta `UltiMusic/`, que la biblioteca ya vigila: la canción
 * aparece sola en Canciones.
 *
 * Es una web de terceros con JavaScript, así que el WebView va cerrado: sin acceso a archivos ni
 * a contenido locales, sin puentes JavaScript hacia la app, y solo `http`/`https` (cualquier otro
 * esquema —`intent:`, `market:`...— se ignora en vez de lanzar aplicaciones externas).
 *
 * Limitaciones de un WebView embebido: el inicio de sesión con Google o Apple lo bloquean sus
 * propios servidores en WebViews (vale usuario y contraseña, tarjeta o PayPal), y Google Pay no
 * funciona.
 */
class StoreWebDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_store_web, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.storeWebRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.storeWebToolbar)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.storeWebProgress)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.title = requireArguments().getString(ARG_NAME)
        toolbar.setNavigationOnClickListener { dismiss() }

        val web = view.findViewById<WebView>(R.id.storeWebView)
        webView = web

        // Las tiendas son webs hechas con JavaScript, y guardan la sesión y el carrito en el
        // almacenamiento del DOM y en cookies (también de terceros: pasarelas de pago).
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            /** true = "ya me encargo yo": se ignora cualquier esquema que no sea web. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress >= FULL_PROGRESS) View.INVISIBLE else View.VISIBLE
            }
        }

        val appContext = requireContext().applicationContext
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            StoreDownloader.download(
                context = appContext,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                cookies = CookieManager.getInstance().getCookie(url)
            )
        }

        // Atrás retrocede dentro de la web y solo cierra cuando ya no queda historial (mismo bloque
        // y mismo motivo que VideoPickerDialogFragment: el despachador es el del DIÁLOGO).
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) web.goBack() else dismiss()
                }
            }
        )

        // Regla del proyecto: todo lo amarillo usa el color dinámico.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.accentColor.collect { accent -> progress.setIndicatorColor(accent) }
            }
        }

        // Solo la primera vez: al girar la pantalla el WebView se recrea y recargaría la página
        // desde cero, perdiendo lo que el usuario hubiera avanzado (carrito, formularios...).
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(requireArguments().getString(ARG_URL).orEmpty())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroyView() {
        // Un WebView sigue vivo (y ejecutando JavaScript) aunque se quite de la pantalla: hay que
        // pararlo y destruirlo a mano o se queda de fondo consumiendo batería y datos.
        webView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "StoreWebDialog"

        private const val ARG_NAME = "name"
        private const val ARG_URL = "url"
        private const val FULL_PROGRESS = 100

        /** [name] es el de la tienda (título de la toolbar); [url] la página con que se abre. */
        fun newInstance(name: String, url: String) = StoreWebDialogFragment().apply {
            arguments = bundleOf(ARG_NAME to name, ARG_URL to url)
        }
    }
}
