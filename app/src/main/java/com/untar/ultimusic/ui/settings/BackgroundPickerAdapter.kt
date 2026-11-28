package com.untar.ultimusic.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.untar.ultimusic.R
import com.untar.ultimusic.data.BackgroundPreferences
import com.untar.ultimusic.util.DynamicColor

/**
 * Selector horizontal de imagen de fondo de Ajustes: las de serie (ver
 * [BackgroundPreferences.builtInNames], las mismas en los dos temas), detrás las que haya subido el
 * usuario y, al final, el rectángulo de "Subir del dispositivo", que llama a [onUpload].
 * Tocar una imagen la elige ([BackgroundPreferences.select]); el cambio lo aplica MainActivity.
 */
class BackgroundPickerAdapter(
    private val onUpload: () -> Unit,
) : RecyclerView.Adapter<BackgroundPickerAdapter.Holder>() {

    /** Las de serie (`null` = la predeterminada) y luego las subidas (ver [BackgroundPreferences]). */
    private val builtIns: List<String?> = BackgroundPreferences.builtInNames
    private var uploads: List<String> = emptyList()
    private var selected: String? = null
    private var accent = DynamicColor.DEFAULT

    fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        notifyItemChanged(positionOf(selected))
    }

    fun setUploads(names: List<String>) {
        if (names == uploads) return
        uploads = names
        notifyDataSetChanged()
    }

    fun setSelected(name: String?) {
        if (name == selected) return
        val old = positionOf(selected)
        selected = name
        notifyItemChanged(old)
        notifyItemChanged(positionOf(name))
    }

    private fun positionOf(name: String?): Int =
        builtIns.indexOf(name).takeIf { it >= 0 } ?: (builtIns.size + uploads.indexOf(name))

    private fun nameAt(position: Int): String? =
        if (position < builtIns.size) builtIns[position] else uploads[position - builtIns.size]

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: View = view.findViewById(R.id.backgroundOptionFrame)
        val image: ImageView = view.findViewById(R.id.backgroundOptionImage)
        val upload: View = view.findViewById(R.id.backgroundOptionUpload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_background_option, parent, false))

    /** Las de serie + las subidas + el rectángulo de subir. */
    override fun getItemCount(): Int = builtIns.size + uploads.size + 1

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val isUpload = position == itemCount - 1
        holder.upload.visibility = if (isUpload) View.VISIBLE else View.GONE
        if (isUpload) {
            holder.image.setImageDrawable(null)
            holder.itemView.contentDescription = context.getString(R.string.settings_background_upload)
            holder.itemView.setOnClickListener { onUpload() }
            holder.frame.setBackgroundColor(ContextCompat.getColor(context, R.color.um_divider))
            return
        }
        val name = nameAt(position)
        // Con Coil y tamaño de miniatura: decodificar el fondo entero solo para esto sería un
        // desperdicio de memoria.
        val file = BackgroundPreferences.fileFor(name)
        if (file == null) {
            holder.image.load(BackgroundPreferences.builtInImage(name)) { size(256, 512) }
        } else {
            holder.image.load(file) { size(256, 512) }
        }
        holder.itemView.contentDescription = context.getString(R.string.settings_background_option_desc, position + 1)
        holder.itemView.setOnClickListener { BackgroundPreferences.select(name) }
        holder.frame.setBackgroundColor(
            if (name == selected) accent else ContextCompat.getColor(context, R.color.um_divider)
        )
    }
}
