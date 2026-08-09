package com.untar.ultimusic.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.LyricsSuggestion
import com.untar.ultimusic.util.TimeFormat

/**
 * Lista de candidatos del buscador de letras de lrclib.net. Igual que
 * [MetadataSuggestionsAdapter], no usa `ListAdapter`/`DiffUtil`: la lista es corta y se pinta una
 * única vez por búsqueda, así que no compensa la maquinaria de comparar listas.
 */
class LyricsSuggestionsAdapter(
    private val onPicked: (LyricsSuggestion) -> Unit
) : RecyclerView.Adapter<LyricsSuggestionsAdapter.ViewHolder>() {

    private var items: List<LyricsSuggestion> = emptyList()

    fun submit(suggestions: List<LyricsSuggestion>) {
        items = suggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyrics_suggestion, parent, false)
        return ViewHolder(view, onPicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        itemView: View,
        private val onPicked: (LyricsSuggestion) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.lyricsSuggestionTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.lyricsSuggestionSubtitle)
        private val badge: TextView = itemView.findViewById(R.id.lyricsSuggestionBadge)

        fun bind(item: LyricsSuggestion) {
            title.text = item.trackName
            subtitle.text = buildSubtitle(item)

            val isSynced = item.syncedLyrics != null
            badge.isVisible = isSynced
            if (isSynced) badge.setText(R.string.lyrics_suggestions_badge_synced)

            itemView.setOnClickListener { onPicked(item) }
        }

        /** "Artista | Álbum | m:ss", mismo separador que el resto de listas y filas de sugerencia
         * (ver [MetadataSuggestionsAdapter.buildSubtitle]). El álbum y la duración se omiten si
         * lrclib.net no los trae. */
        private fun buildSubtitle(item: LyricsSuggestion): String {
            val duration = item.durationMs?.let { TimeFormat.mmss(it) }
            val parts = listOfNotNull(
                item.artistName.takeIf { it.isNotBlank() },
                item.albumName,
                duration
            )
            return parts.joinToString(itemView.context.getString(R.string.subtitle_separator))
        }
    }
}
