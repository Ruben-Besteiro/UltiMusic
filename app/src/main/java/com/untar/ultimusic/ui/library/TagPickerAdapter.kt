package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.DynamicColor

/**
 * Lista de etiquetas asignables del buscador de [TagPickerDialogFragment]: solo el nombre dentro de
 * la "salchicha" (ver item_tag_pill.xml), sin recuento ni X — análogo a [TagsAdapter] pero para tocar
 * y AÑADIR, no para navegar a una ficha.
 */
class TagPickerAdapter(
    private val onTagClick: (TagSummary) -> Unit
) : RecyclerView.Adapter<TagPickerAdapter.PillViewHolder>() {

    private var tags: List<TagSummary> = emptyList()

    fun submit(list: List<TagSummary>) {
        tags = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tags.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PillViewHolder(inflater.inflate(R.layout.item_tag_pill, parent, false))
    }

    override fun onBindViewHolder(holder: PillViewHolder, position: Int) {
        holder.bind(tags[position], onTagClick)
    }

    class PillViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chip: TextView = itemView.findViewById(R.id.tagPillChip)

        fun bind(tag: TagSummary, onTagClick: (TagSummary) -> Unit) {
            chip.text = tag.name
            AccentTint.fill(itemView, R.id.tagPillChip, DynamicColor.dim(tag.colorArgb))
            AccentTint.stroke(itemView, R.id.tagPillChip, tag.colorArgb, R.dimen.tag_chip_stroke_width)
            itemView.setOnClickListener { onTagClick(tag) }
        }
    }
}
