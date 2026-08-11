package com.untar.ultimusic.ui.editor

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.GeniusApi
import com.untar.ultimusic.data.remote.GeniusTokenStore
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * El diálogo de "Acción requerida" que sale al tocar la varita del editor de CANCIÓN cuando todavía
 * no hay token de Genius guardado (ver [GeniusTokenStore] sobre por qué lo pone cada usuario).
 *
 * ## Por qué solo en el editor de canción
 *
 * El autorrelleno de ÁLBUM no llama a Genius en ningún momento: [MetadataSuggestionsViewModel.enrich]
 * se corta en seco si el tipo no es `SuggestionKind.SONG`, así que allí todo sale de iTunes, que no
 * pide clave ninguna. Poner esta puerta también en el editor de álbum sería pedirle al usuario que
 * configure algo que esa pantalla no va a usar.
 *
 * ## Por qué Custom Tab y no un WebView
 *
 * Google **bloquea** sus inicios de sesión OAuth desde WebViews embebidos (error
 * `disallowed_useragent`), y quien se registró en Genius con Google —que son muchos— se quedaría
 * atascado sin salida. Un [CustomTabsIntent] abre el Chrome de verdad, con la sesión que el usuario
 * ya tenga, así que ese login funciona. A cambio no se puede inyectar JavaScript para prerellenar el
 * formulario de Genius, de ahí los botones de copiar: pegar dos campos cuesta un toque, y no depende
 * de una maquetación de terceros que puede cambiar cualquier día y romperse en silencio.
 *
 * ## El portapapeles
 *
 * Al volver del navegador se mira si lo copiado tiene pinta de token y, si la tiene, se valida
 * contra Genius sin que el usuario tenga que tocar nada más — es el "se comprobará automáticamente"
 * del paso 3. Solo se lee **después** de haber mandado al usuario al navegador ([openedBrowser]):
 * desde Android 12 el sistema avisa de cada lectura del portapapeles, y hacerlo sin motivo, nada más
 * abrir el diálogo, sería tan gratuito como sospechoso.
 */
class GeniusTokenDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    private lateinit var status: TextView

    /** Si ya hemos mandado al usuario al navegador. Ver la sección del portapapeles en la cabecera.
     *  Se guarda en el estado porque el sistema puede recrear la Activity de detrás mientras el
     *  Custom Tab está abierto (girar la pantalla, falta de memoria): perdiéndolo, al volver no se
     *  comprobaría el portapapeles solo, que es lo que promete el paso 3. */
    private var openedBrowser = false

    /** Para no lanzar dos validaciones a la vez si el usuario vuelve y toca "Continuar" enseguida. */
    private var validating = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        openedBrowser = savedInstanceState?.getBoolean(STATE_OPENED_BROWSER) == true
        val content = layoutInflater.inflate(R.layout.dialog_genius_token, null)
        status = content.findViewById(R.id.geniusSetupStatus)

        val link = content.findViewById<TextView>(R.id.geniusSetupLink)
        link.setOnClickListener { openGenius() }
        // El enlace es amarillo en el XML y la regla del proyecto es que todo lo amarillo use el
        // color dinámico (ver AccentTint). Se tiñe aquí y no en onViewCreated porque un
        // DialogFragment que monta su vista en onCreateDialog no pasa por onCreateView, así que
        // onViewCreated no llega a ejecutarse nunca.
        link.setTextColor(playerViewModel.accentColor.value)
        content.findViewById<View>(R.id.geniusSetupCopyName).setOnClickListener {
            copy(getString(R.string.genius_setup_value_name))
        }
        content.findViewById<View>(R.id.geniusSetupCopyUrl).setOnClickListener {
            copy(getString(R.string.genius_setup_url))
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.genius_setup_title)
            .setView(content)
            // "Salir" es el rechazo (ver [decline]), así que sí lleva acción, a diferencia del null
            // de antes. El diálogo se cierra solo al pulsarlo, que es lo que toca aquí.
            .setNegativeButton(R.string.genius_setup_exit) { _, _ -> decline() }
            .setPositiveButton(R.string.genius_setup_continue, null)
            .create()

        dialog.setOnShowListener {
            AccentTint.buttons(dialog, playerViewModel.accentColor.value)
            // El listener se pone AQUÍ, y no en setPositiveButton, porque el de la fábrica cierra el
            // diálogo pase lo que pase: si el token no vale, hay que quedarse abierto y decirlo.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                checkClipboard(silentIfNotAToken = false)
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPENED_BROWSER, openedBrowser)
    }

    override fun onResume() {
        super.onResume()
        // Volvemos del navegador: si el usuario ha copiado el token, lo damos por bueno solo. Si lo
        // que hay en el portapapeles es otra cosa, no se dice nada — todavía puede estar a medias.
        if (openedBrowser) checkClipboard(silentIfNotAToken = true)
    }

    /**
     * El usuario rechaza configurar Genius. Se apunta en disco para **no volver a insistir** y se
     * avisa a quien abrió esto para que siga adelante igualmente: el autorrelleno funciona sin Genius
     * —lo hace todo iTunes—, solo que sin productores, sin datos del remix, sin videoclip y con la
     * carátula del disco en vez de la de la canción.
     *
     * Poder decir que no importa de verdad: el usuario puede estar baneado de Genius, en cuyo caso el
     * proceso no lo puede completar por mucho que quiera, y sin esta salida se quedaría sin
     * autorrelleno para siempre por algo ajeno a la aplicación.
     */
    private fun decline() {
        GeniusTokenStore.decline()
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_DECLINED to true))
    }

    private fun openGenius() {
        openedBrowser = true
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        runCatching { intent.launchUrl(requireContext(), Uri.parse(getString(R.string.genius_setup_login_url))) }
            .onFailure {
                // Sin ningún navegador que soporte Custom Tabs (raro, pero posible en una ROM
                // pelada) no hay nada que hacer salvo decirlo: el proceso es en la web de Genius.
                Toast.makeText(requireContext(), R.string.genius_setup_no_browser, Toast.LENGTH_LONG).show()
            }
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("UltiMusic", text))
        // Desde Android 13 el sistema ya enseña su propia confirmación al copiar; duplicarla con un
        // Toast propio se vería dos veces.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.genius_setup_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Mira si el portapapeles trae algo con forma de token y, si es así, lo valida contra Genius.
     *
     * [silentIfNotAToken] distingue las dos formas de llegar aquí: al volver del navegador
     * (automático, y callar si no hay nada es lo correcto porque el usuario puede ir por el paso 2)
     * y al pulsar "Continuar" (deliberado, así que hay que explicar por qué no se sigue).
     */
    private fun checkClipboard(silentIfNotAToken: Boolean) {
        if (validating) return

        val clipboard = requireContext().getSystemService<ClipboardManager>()
        val pasted = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            ?.trim()

        if (pasted == null || !GeniusTokenStore.looksLikeToken(pasted)) {
            if (!silentIfNotAToken) showStatus(R.string.genius_setup_no_token, isError = true)
            return
        }

        val candidate = pasted
        validating = true
        showStatus(R.string.genius_setup_checking, isError = false)

        lifecycleScope.launch {
            val valid = GeniusApi.validateToken(candidate)
            validating = false
            if (valid) {
                GeniusTokenStore.save(candidate)
                setFragmentResult(RESULT_KEY, bundleOf(RESULT_CONFIGURED to true))
                Toast.makeText(requireContext(), R.string.genius_setup_ok, Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                showStatus(R.string.genius_setup_invalid, isError = true)
            }
        }
    }

    private fun showStatus(messageRes: Int, isError: Boolean) {
        status.isVisible = true
        status.setText(messageRes)
        status.setTextColor(
            if (isError) requireContext().getColor(R.color.um_error)
            else playerViewModel.accentColor.value
        )
    }

    companion object {
        const val TAG = "genius_token_setup"

        private const val STATE_OPENED_BROWSER = "opened_browser"

        /**
         * Aviso al editor de cómo ha acabado esto, para que abra el autorrelleno que el usuario había
         * pedido en primer lugar sin obligarle a volver a tocar la varita. Sale por los dos caminos
         * —[RESULT_CONFIGURED] si puso el token, [RESULT_DECLINED] si lo rechazó— porque en ambos hay
         * que seguir adelante; lo único que cambia es si Genius participa o lo hace todo iTunes.
         */
        const val RESULT_KEY = "genius_token_result"
        const val RESULT_CONFIGURED = "configured"
        const val RESULT_DECLINED = "declined"

        fun newInstance() = GeniusTokenDialogFragment()
    }
}
