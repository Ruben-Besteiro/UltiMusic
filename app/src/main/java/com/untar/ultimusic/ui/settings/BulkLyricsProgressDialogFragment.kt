package com.untar.ultimusic.ui.settings

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.untar.ultimusic.R
import com.untar.ultimusic.data.BulkLyricsAdder
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Diálogo de progreso de "Añadir todas las letras" (ver [SettingsDialogFragment] y
 * [BulkLyricsAdder]), con el que se lanza y se muestra.
 *
 * No tiene ViewModel propio: el trabajo de verdad y su estado viven en [BulkLyricsAdder], un
 * `object` con corrutina propia que sigue corriendo aunque este diálogo se cierre (botón "Segundo
 * plano", o el usuario sale de Ajustes con el "atrás" mientras el pase sigue). Este fragmento solo
 * pinta ese estado y reacciona a sus dos botones.
 *
 * Como [com.untar.ultimusic.ui.editor.GeniusTokenDialogFragment], monta su vista en
 * [onCreateDialog] (no hay [onCreateView]/vista propia), así que todo lo que normalmente iría en
 * `onViewCreated` va aquí.
 */
class BulkLyricsProgressDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false

        val content = layoutInflater.inflate(R.layout.dialog_bulk_lyrics_progress, null)
        val songTitle = content.findViewById<TextView>(R.id.bulkLyricsSongTitle)
        val elapsed = content.findViewById<TextView>(R.id.bulkLyricsElapsed)
        val progressBar = content.findViewById<ProgressBar>(R.id.bulkLyricsProgressBar)
        val counter = content.findViewById<TextView>(R.id.bulkLyricsCounter)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_bulk_lyrics_progress_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> BulkLyricsAdder.cancel(requireContext()) }
            .setPositiveButton(R.string.settings_bulk_lyrics_background) { _, _ ->
                BulkLyricsAdder.enterBackgroundMode(requireContext())
            }
            .create()

        // Igual que GeniusTokenDialogFragment: los botones del AlertDialog no existen como View
        // hasta que se muestra, así que teñirlos (amarillo -> color dinámico, ver AccentTint) tiene
        // que esperar a setOnShowListener. El collect de más abajo se encarga de los cambios de
        // acento MIENTRAS el diálogo está abierto; esto es solo el primer teñido.
        dialog.setOnShowListener {
            AccentTint.buttons(dialog, playerViewModel.accentColor.value)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BulkLyricsAdder.state.collect { state ->
                        render(state, songTitle, progressBar, counter)
                    }
                }
                // Todo lo amarillo de la app usa el color dinámico (ver AccentTint); aquí es la
                // barra de progreso y los dos botones del propio AlertDialog.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        progressBar.progressTintList = ColorStateList.valueOf(accent)
                        AccentTint.buttons(dialog, accent)
                    }
                }
                // El tiempo transcurrido avanza solo, cada segundo, independientemente de por qué
                // canción va el pase: se calcula contra BulkLyricsAdder.State.Running.startedAtMs, que
                // es fijo durante todo el pase (ver su cabecera).
                launch {
                    while (isActive) {
                        (BulkLyricsAdder.state.value as? BulkLyricsAdder.State.Running)?.let { running ->
                            elapsed.text = TimeFormat.hhmmss(System.currentTimeMillis() - running.startedAtMs)
                        }
                        delay(1_000)
                    }
                }
            }
        }

        return dialog
    }

    /**
     * [BulkLyricsAdder.State.Finished] y [BulkLyricsAdder.State.Cancelled] cierran el diálogo solos
     * (el pase ha terminado o se ha cancelado, no queda nada que enseñar) y devuelven
     * [BulkLyricsAdder] a [BulkLyricsAdder.State.Idle] con [BulkLyricsAdder.reset] para que la
     * próxima vez que se pulse "Añadir todas las letras" se ofrezca la confirmación de siempre.
     *
     * [BulkLyricsAdder.State.Idle] no debería llegar aquí nunca mientras el diálogo está en pantalla
     * (solo se muestra tras llamar a [BulkLyricsAdder.start], que dispara [BulkLyricsAdder.State.Running]
     * antes de devolver el control), pero se cierra por si acaso en vez de quedarse pintando nada.
     */
    private fun render(
        state: BulkLyricsAdder.State,
        songTitle: TextView,
        progressBar: ProgressBar,
        counter: TextView
    ) {
        when (state) {
            is BulkLyricsAdder.State.Running -> {
                songTitle.text = state.songTitle
                counter.text = getString(R.string.settings_bulk_lyrics_counter, state.index, state.total)
                progressBar.isIndeterminate = state.total == 0
                if (state.total > 0) {
                    progressBar.max = state.total
                    progressBar.progress = state.index
                }
            }

            is BulkLyricsAdder.State.Finished -> {
                BulkLyricsAdder.reset()
                val message = if (state.total == 0) {
                    getString(R.string.settings_bulk_lyrics_empty)
                } else {
                    getString(R.string.settings_bulk_lyrics_finished, state.added, state.total)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                dismissIfShowing()
            }

            BulkLyricsAdder.State.Cancelled -> {
                BulkLyricsAdder.reset()
                dismissIfShowing()
            }

            BulkLyricsAdder.State.Idle -> dismissIfShowing()
        }
    }

    private fun dismissIfShowing() {
        if (isAdded) dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "bulk_lyrics_progress"
    }
}
