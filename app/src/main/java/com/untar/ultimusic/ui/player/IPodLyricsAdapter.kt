package com.untar.ultimusic.ui.player

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.util.DynamicColor

/**
 * Letra del iPod: una fila por línea. Sirve para los dos casos a la vez, sin que
 * [IPodDialogFragment] tenga que usar una vista distinta para cada uno:
 *
 * - **Sincronizada** (el texto guardado tenía marcas de tiempo LRC, ver
 *   [com.untar.ultimusic.util.LrcParser]): una fila por línea de verdad. La que toca cantar ahora
 *   ([currentIndex], que decide el iPod mirando [PlayerViewModel.progress]) se resalta con el color
 *   dinámico y en negrita; el resto en gris apagado — mismo contraste "actual vs. resto" que ya usa
 *   la cola de reproducción ([IPodQueueAdapter]).
 * - **Sin sincronizar** (letra escrita a mano, o sin marcas de tiempo) o sin letra: [submit] recibe
 *   una lista de una única línea con el texto entero (o el aviso de "sin letra"), y [synced] en
 *   `false`. Esa única fila se pinta neutra, tal cual se veía antes de que existiera este
 *   adaptador: no tiene sentido resaltar ni centrar nada cuando no hay ninguna marca de tiempo con
 *   la que seguir la canción.
 */
class IPodLyricsAdapter : RecyclerView.Adapter<IPodLyricsAdapter.LineViewHolder>() {

    private var lines: List<String> = emptyList()
    private var synced: Boolean = false
    private var currentIndex: Int = -1
    private var accent: Int = DynamicColor.DEFAULT

    /** Si la letra actual está sincronizada de verdad; [IPodDialogFragment] la usa para decidir si
     *  tiene sentido resaltar/centrar algo en cada tick de progreso. */
    val isSynced: Boolean get() = synced

    fun submit(newLines: List<String>, synced: Boolean) {
        lines = newLines
        this.synced = synced
        currentIndex = -1
        notifyDataSetChanged()
    }

    /** Cambia qué línea está resaltada. Devuelve `true` si de verdad ha cambiado, para que quien
     *  llame sepa si hace falta volver a centrar la vista. */
    fun setCurrentLine(index: Int): Boolean {
        if (index == currentIndex) return false
        val previous = currentIndex
        currentIndex = index
        if (previous in lines.indices) notifyItemChanged(previous)
        if (index in lines.indices) notifyItemChanged(index)
        return true
    }

    fun setAccent(color: Int) {
        accent = color
        if (synced && currentIndex in lines.indices) notifyItemChanged(currentIndex)
    }

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bind(lines[position], synced, position == currentIndex, accent)
    }

    class LineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text = itemView as TextView

        fun bind(line: String, synced: Boolean, isCurrent: Boolean, accent: Int) {
            text.text = line
            when {
                // La línea que toca cantar ahora mismo.
                isCurrent -> {
                    text.setTextColor(accent)
                    text.setTypeface(null, Typeface.BOLD)
                }
                // El resto de líneas de una letra sincronizada, atenuadas (aún no llega / ya pasó).
                synced -> {
                    text.setTextColor(ContextCompat.getColor(itemView.context, R.color.um_on_surface_muted))
                    text.setTypeface(null, Typeface.NORMAL)
                }
                // Letra sin sincronizar (o sin letra): una única fila neutra, como antes.
                else -> {
                    text.setTextColor(ContextCompat.getColor(itemView.context, R.color.um_on_background))
                    text.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }
}
