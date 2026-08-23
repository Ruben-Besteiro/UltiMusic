package com.untar.ultimusic.ui.library

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.untar.ultimusic.R
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.library.colorpicker.ColorPickerView
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.DynamicColor

/**
 * Crear o editar una etiqueta personalizada: nombre + color (ver [ColorPickerView]), con una
 * vista previa en vivo de la "salchicha" final (mismo trío `AccentTint.fill`/`AccentTint.stroke`/
 * `DynamicColor.dim` que usa [TagsAdapter], para que se vea el resultado real antes de guardar).
 *
 * Un único diálogo sirve para los dos casos ([newCreate]/[newEdit]): en modo edición llega
 * precargado con el nombre y color actuales de la etiqueta.
 *
 * El acento dinámico de reproducción SÍ tiñe la chrome del diálogo (subrayado del nombre, botones
 * OK/Cancelar, subrayado de los campos R/G/B del picker), pero NUNCA el color que el usuario elige
 * para su etiqueta: ver el KDoc de [ColorPickerView] sobre esa excepción, ya documentada en
 * [TagsAdapter] para `tag.colorArgb`.
 */
class TagEditorDialogFragment : DialogFragment() {

    private val tagsViewModel: TagsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editingId = requireArguments().getLong(ARG_TAG_ID, -1L).takeIf { it != -1L }
        val initialName = requireArguments().getString(ARG_TAG_NAME).orEmpty()
        val initialColor = requireArguments().getInt(ARG_TAG_COLOR, DynamicColor.DEFAULT)
        val accent = playerViewModel.accentColor.value

        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_tag_editor, null, false)
        val nameInput = content.findViewById<EditText>(R.id.inputTagName)
        val chipPreview = content.findViewById<TextView>(R.id.tagChipPreview)
        val colorPicker = content.findViewById<ColorPickerView>(R.id.colorPicker)

        nameInput.setText(initialName)
        nameInput.setSelection(nameInput.text.length)
        nameInput.backgroundTintList = AccentTint.underline(requireContext(), accent)
        colorPicker.setAccentTint(accent)
        colorPicker.setColor(initialColor)

        fun refreshChipPreview(color: Int) {
            val name = nameInput.text.toString().trim()
            chipPreview.text = name.ifBlank { getString(R.string.tag_name_hint) }
            AccentTint.fill(content, R.id.tagChipPreview, DynamicColor.dim(color))
            AccentTint.stroke(content, R.id.tagChipPreview, color, R.dimen.tag_chip_stroke_width)
        }
        refreshChipPreview(initialColor)
        colorPicker.onColorChanged = { color -> refreshChipPreview(color) }
        nameInput.doAfterTextChanged { refreshChipPreview(colorPicker.getColor()) }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (editingId != null) R.string.tag_edit else R.string.tag_create)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(if (editingId != null) R.string.dialog_ok else R.string.add_tag_button) { _, _ ->
                val name = nameInput.text.toString().trim()
                val color = colorPicker.getColor()
                if (editingId != null) {
                    tagsViewModel.updateTag(editingId, name, color)
                } else {
                    tagsViewModel.createTag(name, color)
                }
            }
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = nameInput.text.isNotBlank()
            nameInput.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }

            // Mismo patrón que PlaylistsFragment.showNameDialog: el botón deshabilitado necesita sus
            // dos estados, o con un tinte plano se vería igual de coloreado estando gris de verdad.
            val muted = ContextCompat.getColor(requireContext(), R.color.um_on_surface_muted)
            ok.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(muted, accent)
                )
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ColorStateList.valueOf(accent))
        }
        return dialog
    }

    companion object {
        const val TAG = "tagEditor"
        private const val ARG_TAG_ID = "tagId"
        private const val ARG_TAG_NAME = "tagName"
        private const val ARG_TAG_COLOR = "tagColor"

        fun newCreate(): TagEditorDialogFragment = TagEditorDialogFragment().apply {
            arguments = bundleOf()
        }

        fun newEdit(tag: TagSummary): TagEditorDialogFragment = TagEditorDialogFragment().apply {
            arguments = bundleOf(
                ARG_TAG_ID to tag.id,
                ARG_TAG_NAME to tag.name,
                ARG_TAG_COLOR to tag.colorArgb
            )
        }
    }
}
