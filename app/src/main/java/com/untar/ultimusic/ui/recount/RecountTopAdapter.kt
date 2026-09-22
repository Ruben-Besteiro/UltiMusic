package com.untar.ultimusic.ui.recount

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.RecountEntry
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.TimeFormat

/**
 * Los tres tops de UltiMusic Recount (canciones, artistas y géneros) usan ESTE mismo adaptador: las
 * tres listas son lo mismo -puesto, nombre, cuántas veces sonó y cuánto tiempo-, y lo único que
 * cambia es si el elemento tiene carátula.
 *
 * La imagen se oculta sola cuando [RecountEntry.cover] es null, que pasa en dos sitios: en el top de
 * géneros (un género no tiene imagen ni ficha, ver [com.untar.ultimusic.model.GenreSummary]) y en la
 * fila de canciones borradas.
 *
 * Esa fila de canciones borradas es la única que no representa nada real: agrupa las escuchas cuyo id
 * ya no corresponde a ninguna canción, compite por su puesto en el top como una más, y su nombre no
 * viene del repositorio sino de aquí -es texto de interfaz, no un dato-.
 */
class RecountTopAdapter : RecyclerView.Adapter<RecountTopAdapter.EntryViewHolder>() {

    private var entries: List<RecountEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<RecountEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder =
        EntryViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_recount_top, parent, false)
        )

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(position + 1, entries[position])
    }

    class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rank: TextView = itemView.findViewById(R.id.recountRank)
        private val cover: ShapeableImageView = itemView.findViewById(R.id.recountCover)
        private val label: TextView = itemView.findViewById(R.id.recountLabel)
        private val stats: TextView = itemView.findViewById(R.id.recountStats)

        fun bind(position: Int, entry: RecountEntry) {
            val context = itemView.context
            rank.text = position.toString()
            label.text = if (entry.isDeletedBucket) {
                context.getString(R.string.recount_deleted_songs)
            } else {
                entry.label
            }
            stats.text = context.getString(
                R.string.recount_entry_stats,
                context.resources.getQuantityString(
                    R.plurals.recount_play_count, entry.plays, entry.plays
                ),
                // hhmmss y no mmss: aquí son tiempos ACUMULADOS de todo un año, donde pasar de la
                // hora es lo normal y "184:22" no se entendería de un vistazo.
                TimeFormat.hhmmss(entry.playedMs),
                // Qué parte del año se llevó esta fila, en reproducciones y no en tiempo (ver
                // RecountEntry.share). Se redondea al entero: décimas de porcentaje no dicen nada
                // en una lista de diez elementos.
                Math.round(entry.share * 100f)
            )

            val ref = entry.cover
            if (ref == null) {
                cover.visibility = View.GONE
            } else {
                cover.visibility = View.VISIBLE
                cover.load(CoverArt.cover(context, ref), CoverLoader.get(context)) {
                    error(R.drawable.cover_placeholder)
                }
            }
        }
    }
}
