package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.GenreSummary

/**
 * Lista de géneros: solo el nombre y cuántas canciones lo llevan (ver item_genre.xml), sin imagen
 * —un género no tiene carátula propia— y sin menú de 3 puntos —no se puede renombrar ni borrar,
 * es solo lo que traen las canciones—. Al pinchar uno se abre el iPod en modo navegación con sus
 * canciones, igual que al pinchar una lista (ver [GenresFragment]).
 */
class GenresAdapter(
    private val onGenreClick: (GenreSummary) -> Unit
) : RecyclerView.Adapter<GenresAdapter.GenreViewHolder>() {

    private var genres: List<GenreSummary> = emptyList()

    fun submit(list: List<GenreSummary>) {
        genres = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = genres.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return GenreViewHolder(inflater.inflate(R.layout.item_genre, parent, false))
    }

    override fun onBindViewHolder(holder: GenreViewHolder, position: Int) {
        holder.bind(genres[position], onGenreClick)
    }

    class GenreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.genreName)
        private val subtitle: TextView = itemView.findViewById(R.id.genreSubtitle)

        fun bind(genre: GenreSummary, onGenreClick: (GenreSummary) -> Unit) {
            val context = itemView.context
            name.text = genre.name
            subtitle.text = context.resources.getQuantityString(
                R.plurals.song_count, genre.songCount, genre.songCount
            )
            itemView.setOnClickListener { onGenreClick(genre) }
        }
    }
}
