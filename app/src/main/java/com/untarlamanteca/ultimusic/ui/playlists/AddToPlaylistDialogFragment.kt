package com.untarlamanteca.ultimusic.ui.playlists

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.ui.PlayerViewModel

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
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val filename = args.getString(ARG_FILENAME)!!
        val names = args.getStringArray(ARG_NAMES)?.toList() ?: emptyList()
        // Estado marcado/desmarcado; el propio AlertDialog lo va actualizando al tocar cada fila.
        val checked = args.getBooleanArray(ARG_CHECKED) ?: BooleanArray(names.size)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_to_playlist_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                val name = names[which]
                if (isChecked) viewModel.addSong(name, filename)
                else viewModel.removeSong(name, filename)
            }
            .setNeutralButton(R.string.playlist_new_option) { _, _ -> showCreateAndAdd(filename) }
            .setPositiveButton(R.string.dialog_ok, null)
            .create()

        // El amarillo dinámico de las casillas y de los botones sigue el color de lo que suena,
        // igual que en el editor de metadatos (ver MetadataEditorDialogFragment). Se lee una sola
        // vez al abrir el diálogo (`.value`, no una colecta en vivo): es una interacción corta y
        // no hace falta recolorear a media que se marca una casilla.
        dialog.setOnShowListener {
            val accent = playerViewModel.accentColor.value
            dialog.listView?.let { tintChecklist(it, accent) }
            val tint = ColorStateList.valueOf(accent)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(tint)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(tint)
        }
        return dialog
    }

    /**
     * Tiñe con [accent] las casillas de este diálogo de selección múltiple.
     *
     * Es un [ListView] (lo crea el propio `AlertDialog`, no se puede cambiar por un `RecyclerView`),
     * y un ListView **recicla** sus filas al hacer scroll: si solo tiñéramos las visibles al abrir el
     * diálogo, una playlist que quedase fuera de pantalla aparecería sin teñir al bajar. Por eso se
     * engancha un `OnHierarchyChangeListener`, que repite el tintado cada vez que una fila (nueva o
     * reciclada) entra en la jerarquía de vistas visible.
     */
    private fun tintChecklist(listView: ListView, accent: Int) {
        fun tint(view: View) {
            val checkedTextView = view as? CheckedTextView ?: return
            val drawable = checkedTextView.checkMarkDrawable?.mutate() ?: return
            DrawableCompat.setTint(drawable, accent)
            checkedTextView.checkMarkDrawable = drawable
        }
        for (i in 0 until listView.childCount) tint(listView.getChildAt(i))
        listView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) = tint(child)
            override fun onChildViewRemoved(parent: View, child: View) = Unit
        })
    }

    /** Pide un nombre, crea la playlist y añade la canción de una vez. */
    private fun showCreateAndAdd(filename: String) {
        // Al pulsar "Nueva playlist…" el AlertDialog de este diálogo se cierra, y con él se DESANCLA
        // este DialogFragment. Por eso capturamos aquí (todavía anclados) el ViewModel de la actividad
        // y un contexto de la actividad: el segundo diálogo debe seguir funcionando aunque el fragmento
        // ya no esté attached cuando el usuario escriba el nombre y pulse Aceptar.
        val vm = viewModel
        val accent = playerViewModel.accentColor.value
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

            // El botón "Añadir" se deshabilita mientras el campo está vacío; con un tinte plano se
            // vería igual de amarillo estando gris de verdad, así que necesita sus dos estados.
            val muted = ContextCompat.getColor(context, R.color.um_on_surface_muted)
            ok.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(muted, accent)
                )
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ColorStateList.valueOf(accent))
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
