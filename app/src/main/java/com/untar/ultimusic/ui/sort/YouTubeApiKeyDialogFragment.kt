package com.untar.ultimusic.ui.sort

import android.app.Dialog
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
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
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.remote.YouTubeApiKeyStore
import com.untar.ultimusic.data.remote.YouTubeStatsApi
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * El diálogo de "Acción requerida" que sale al elegir "Popularidad" en [SortDialogFragment] cuando
 * todavía no hay clave de la YouTube Data API guardada (ver [YouTubeApiKeyStore] sobre por qué la
 * pone cada usuario). Es el gemelo exacto de
 * [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment] —Custom Tab en vez de WebView por el
 * mismo motivo (login de Google bloqueado en WebViews embebidos), portapapeles comprobado solo
 * DESPUÉS de mandar al usuario al navegador, mismo patrón de botones—, así que ver allí el porqué de
 * cada pieza; aquí solo se documenta lo que cambia.
 */
class YouTubeApiKeyDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    private lateinit var status: TextView

    /** Ver la sección del portapapeles en el javadoc de GeniusTokenDialogFragment: mismo motivo para
     *  guardarlo en el estado. */
    private var openedBrowser = false

    private var validating = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        openedBrowser = savedInstanceState?.getBoolean(STATE_OPENED_BROWSER) == true
        val content = layoutInflater.inflate(R.layout.dialog_youtube_setup, null)
        status = content.findViewById(R.id.youtubeSetupStatus)

        val link = content.findViewById<TextView>(R.id.youtubeSetupLink)
        link.setOnClickListener { openConsole() }
        // El enlace es amarillo en el XML y la regla del proyecto es que todo lo amarillo use el
        // color dinámico (ver AccentTint). Se tiñe aquí y no en onViewCreated porque un
        // DialogFragment que monta su vista en onCreateDialog no pasa por onCreateView.
        link.setTextColor(playerViewModel.accentColor.value)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.youtube_setup_title)
            .setNegativeButton(R.string.youtube_setup_exit) { _, _ -> decline() }
            .setPositiveButton(R.string.youtube_setup_continue, null)
            .setView(content)
            .create()

        dialog.setOnShowListener {
            AccentTint.buttons(dialog, playerViewModel.accentColor.value)
            // Igual que en GeniusTokenDialogFragment: el listener va aquí, no en setPositiveButton,
            // porque el de la fábrica cierra el diálogo pase lo que pase.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                checkClipboard(silentIfNotAKey = false)
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
        if (openedBrowser) checkClipboard(silentIfNotAKey = true)
    }

    /** El usuario rechaza configurar YouTube: se apunta para no volver a insistir (ver
     *  [YouTubeApiKeyStore.decline]) y se avisa a quien abrió esto para que siga adelante sin ese
     *  criterio de orden. */
    private fun decline() {
        YouTubeApiKeyStore.decline()
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_DECLINED to true))
    }

    private fun openConsole() {
        openedBrowser = true
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        runCatching { intent.launchUrl(requireContext(), Uri.parse(getString(R.string.youtube_setup_login_url))) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.genius_setup_no_browser, Toast.LENGTH_LONG).show()
            }
    }

    /** Mira si el portapapeles trae algo con forma de clave y, si es así, la valida contra YouTube.
     *  Mismo contrato que [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment.checkClipboard]. */
    private fun checkClipboard(silentIfNotAKey: Boolean) {
        if (validating) return

        val clipboard = requireContext().getSystemService<ClipboardManager>()
        val pasted = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            ?.trim()

        if (pasted == null || !YouTubeApiKeyStore.looksLikeKey(pasted)) {
            if (!silentIfNotAKey) showStatus(R.string.youtube_setup_no_key, isError = true)
            return
        }

        val candidate = pasted
        validating = true
        showStatus(R.string.youtube_setup_checking, isError = false)

        lifecycleScope.launch {
            val valid = YouTubeStatsApi.validateKey(candidate)
            validating = false
            if (valid) {
                YouTubeApiKeyStore.save(candidate)
                // force = true: no hace falta esperar al refresco diario. Esto vale tanto para la
                // primera clave como para una reconfiguración (ver el botón "Reconfigurar" de
                // SettingsDialogFragment): si el usuario había metido vídeos sin clave, o con una que
                // dejó de servir, quiere ver sus visitas/suscriptores YA al arreglarlo, no mañana.
                LibraryRepository.get(requireContext()).refreshYouTubeStatsIfDue(force = true)
                setFragmentResult(RESULT_KEY, bundleOf(RESULT_CONFIGURED to true))
                Toast.makeText(requireContext(), R.string.youtube_setup_ok, Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                showStatus(R.string.youtube_setup_invalid, isError = true)
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
        const val TAG = "youtube_api_key_setup"

        private const val STATE_OPENED_BROWSER = "opened_browser"

        /** Mismo doble aviso que [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment.RESULT_KEY]:
         *  sale por las dos salidas porque en ambas hay que seguir adelante con el diálogo de ordenar,
         *  solo que con o sin el criterio de Popularidad disponible. */
        const val RESULT_KEY = "youtube_api_key_result"
        const val RESULT_CONFIGURED = "configured"
        const val RESULT_DECLINED = "declined"

        fun newInstance() = YouTubeApiKeyDialogFragment()
    }
}
