package com.untar.ultimusic.ui.editor

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint

/**
 * Aviso de que el API Client de Genius del usuario ha dejado de funcionar (su token devuelve 401 con
 * `invalid_token`: lo ha borrado, lo ha regenerado, o le han suspendido la cuenta). Para cuando esto
 * sale, [com.untar.ultimusic.data.remote.GeniusApi.detailsFor] ya ha borrado el token guardado.
 *
 * Es un paso previo a [GeniusTokenDialogFragment], no un sustituto: explica **qué ha pasado** en una
 * frase, y al aceptar se abre el diálogo largo con las instrucciones para sacar otro. Separarlos
 * evita que quien ya conoce el proceso tenga que releerlo entero para entender por qué le ha saltado
 * de nuevo.
 */
class GeniusApiErrorDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.genius_error_title)
            .setMessage(R.string.genius_error_message)
            .setPositiveButton(R.string.genius_setup_continue) { _, _ ->
                setFragmentResult(RESULT_KEY, bundleOf(RESULT_ACKNOWLEDGED to true))
            }
            .create()

        // Regla del proyecto: nada de amarillo fijo, todo con el color de la canción que suena.
        dialog.setOnShowListener { AccentTint.buttons(dialog, playerViewModel.accentColor.value) }
        return dialog
    }

    companion object {
        const val TAG = "genius_api_error"

        /** Al aceptar, el editor abre [GeniusTokenDialogFragment]. */
        const val RESULT_KEY = "genius_api_error_result"
        const val RESULT_ACKNOWLEDGED = "acknowledged"

        fun newInstance() = GeniusApiErrorDialogFragment()
    }
}
