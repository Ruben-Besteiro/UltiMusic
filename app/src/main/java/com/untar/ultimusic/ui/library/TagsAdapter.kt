package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.TagSummary
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.DynamicColor

/**
 * Lista de etiquetas: el nombre dentro de una "salchicha" de color (ver item_tag.xml/bg_tag_chip.xml,
 * inspirada en Top Drives: borde vivo, relleno del mismo color pero oscuro) y debajo cuántas
 * canciones tiene, igual de estructura que [GenresAdapter] pero sin texto plano. Menú de 3 puntos
 * (editar/eliminar) SOLO en las etiquetas personalizadas del usuario (`systemKey == null`); las 5
 * predefinidas lo ocultan, igual que hoy.
 */
class TagsAdapter(
    private val onTagClick: (TagSummary) -> Unit,
    private val onEditTag: (TagSummary) -> Unit,
    private val onDeleteTag: (TagSummary) -> Unit
) : RecyclerView.Adapter<TagsAdapter.TagViewHolder>() {

    private var tags: List<TagSummary> = emptyList()

    fun submit(list: List<TagSummary>) {
        tags = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tags.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return TagViewHolder(inflater.inflate(R.layout.item_tag, parent, false))
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(tags[position], onTagClick, onEditTag, onDeleteTag)
    }

    class TagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chip: TextView = itemView.findViewById(R.id.tagChip)
        private val subtitle: TextView = itemView.findViewById(R.id.tagSubtitle)
        private val more: View = itemView.findViewById(R.id.btnTagMore)

        fun bind(
            tag: TagSummary,
            onTagClick: (TagSummary) -> Unit,
            onEditTag: (TagSummary) -> Unit,
            onDeleteTag: (TagSummary) -> Unit
        ) {
            val context = itemView.context
            chip.text = tag.name
            // Colores FIJOS por etiqueta, no el acento dinámico de lo que suena: a diferencia del
            // resto de "amarillos" de la app, cada etiqueta tiene su propio color de verdad (elegido
            // en TagEditorDialogFragment, o sembrado en Migrations.kt.seedDefaultTags para las
            // predefinidas), así que no le aplica la regla de color dinámico.
            AccentTint.fill(itemView, R.id.tagChip, DynamicColor.dim(tag.colorArgb))
            AccentTint.stroke(itemView, R.id.tagChip, tag.colorArgb, R.dimen.tag_chip_stroke_width)
            subtitle.text = context.resources.getQuantityString(
                R.plurals.song_count, tag.songCount, tag.songCount
            )
            itemView.setOnClickListener { onTagClick(tag) }

            // Editar/eliminar solo tiene sentido para una etiqueta personalizada: las 5 predefinidas
            // no se pueden renombrar ni borrar (confirmado con el usuario al diseñar esta pantalla).
            val isCustom = tag.systemKey == null
            more.visibility = if (isCustom) View.VISIBLE else View.GONE
            if (isCustom) {
                more.setOnClickListener { anchor ->
                    PopupMenu(anchor.context, anchor).apply {
                        menuInflater.inflate(R.menu.menu_tag_item, menu)
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                R.id.action_edit_tag -> { onEditTag(tag); true }
                                R.id.action_delete_tag -> { onDeleteTag(tag); true }
                                else -> false
                            }
                        }
                        show()
                    }
                }
            } else {
                more.setOnClickListener(null)
            }
        }
    }
}
