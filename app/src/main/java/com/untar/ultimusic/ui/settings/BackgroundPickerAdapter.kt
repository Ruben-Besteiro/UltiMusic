package com.untar.ultimusic.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.untar.ultimusic.R
import com.untar.ultimusic.util.DynamicColor

/**
 * Selector horizontal de imagen de fondo de Ajustes. De momento solo hay una imagen (`bg_main`,
 * siempre seleccionada) y, detrás, un rectángulo gris 1:2 que marca el hueco de las que vendrán: no
 * hace nada al tocarlo y no se guarda ninguna elección todavía.
 */
class BackgroundPickerAdapter : RecyclerView.Adapter<BackgroundPickerAdapter.Holder>() {

    /** `null` = el rectángulo gris de reserva. */
    private val items: List<Int?> = listOf(R.drawable.bg_main, null)
    private val selected = 0
    private var accent = DynamicColor.DEFAULT

    fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        notifyItemChanged(selected)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: View = view.findViewById(R.id.backgroundOptionFrame)
        val image: ImageView = view.findViewById(R.id.backgroundOptionImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_background_option, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        @DrawableRes val res = items[position]
        val context = holder.itemView.context
        if (res != null) {
            // Con Coil y tamaño de miniatura: decodificar el fondo entero solo para esto sería un
            // desperdicio de memoria.
            holder.image.load(res) { size(256, 512) }
            holder.itemView.contentDescription = context.getString(R.string.settings_background_option_desc, position + 1)
        } else {
            holder.image.setImageDrawable(null)
            holder.itemView.contentDescription = context.getString(R.string.settings_background_placeholder_desc)
        }
        holder.frame.setBackgroundColor(
            if (position == selected) accent else ContextCompat.getColor(context, R.color.um_divider)
        )
    }
}
