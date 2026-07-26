package com.untarlamanteca.ultimusic.ui.playlists

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.untarlamanteca.ultimusic.R

/**
 * Diálogo de "Añadir a playlist" para una canción. Muestra todas las playlists con **casillas**:
 * marcar añade la canción a esa playlist y desmarcar la quita. Abajo, un botón "Nueva playlist…"
 * crea una y mete la canción en ella.
 *
 * La lista de playlists y qué casillas van marcadas se calculan ANTES de abrir el diálogo (en
 * [com.untarlamanteca.ultimusic.ui.songs.SongsFragment], que hace la lectura de disco en una
 * corrutina) y llegan aquí por argumentos. Así este diálogo se construye de forma síncrona y
 * sobrevive a un giro de pantalla sin volver a tocar disco. Las modificaciones se delegan en
 * [PlaylistsViewModel], que refresca el estado compartido (y con él el resaltado de Canciones).
 */
class AddToPlaylistDialogFragment : DialogFragment() {

    private val viewModel: PlaylistsViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val filename = args.getString(ARG_FILENAME)!!
        val names = args.getStringArray(ARG_NAMES)?.toList() ?: emptyList()
        // Estado marcado/desmarcado; el propio AlertDialog lo va actualizando al tocar cada fila.
        val checked = args.getBooleanArray(ARG_CHECKED) ?: BooleanArray(names.size)

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_to_playlist_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                val name = names[which]
                if (isChecked) viewModel.addSong(name, filename)
                else viewModel.removeSong(name, filename)
            }
            .setNeutralButton(R.string.playlist_new_option) { _, _ -> showCreateAndAdd(filename) }
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
    }

    /** Pide un nombre, crea la playlist y añade la canción de una vez. */
    private fun showCreateAndAdd(filename: String) {
        // Al pulsar "Nueva playlist…" el AlertDialog de este diálogo se cierra, y con él se DESANCLA
        // este DialogFragment. Por eso capturamos aquí (todavía anclados) el ViewModel de la actividad
        // y un contexto de la actividad: el segundo diálogo debe seguir funcionando aunque el fragmento
        // ya no esté attached cuando el usuario escriba el nombre y pulse Aceptar.
        val vm = viewModel
        val context = requireActivity()
        val input = EditText(context).apply {
            hint = getString(R.string.playlist_name_hint)
            setSingleLine()
        }
        val padding = (resources.displayMetrics.density * 20).toInt()
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.playlist_create)
            .setView(input, padding, padding / 2, padding, 0)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.playlist_add_button) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    vm.create(name)
                    vm.addSong(name, filename)
                }
            }
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            input.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
        }
        dialog.show()
    }

    companion object {
        private const val ARG_FILENAME = "filename"
        private const val ARG_NAMES = "names"
        private const val ARG_CHECKED = "checked"
        const val TAG = "addToPlaylist"

        fun newInstance(
            filename: String,
            names: List<String>,
            checked: BooleanArray
        ) = AddToPlaylistDialogFragment().apply {
            arguments = bundleOf(
                ARG_FILENAME to filename,
                ARG_NAMES to names.toTypedArray(),
                ARG_CHECKED to checked
            )
        }
    }
}
