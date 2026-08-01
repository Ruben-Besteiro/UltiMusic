package com.untar.ultimusic.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.PersonSummary
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader

/**
 * Lista de personas: una imagen circular con el nombre y, debajo, cuántos álbumes y canciones
 * tiene. El MISMO adaptador sirve para la pestaña de Artistas y para la de Productores, porque los
 * dos se tratan igual y se pintan igual; lo único que cambia es de qué flujo salen los datos.
 */
class PeopleAdapter(
    private val onPersonClick: (PersonSummary) -> Unit
) : RecyclerView.Adapter<PeopleAdapter.PersonViewHolder>() {

    private var people: List<PersonSummary> = emptyList()

    fun submit(list: List<PersonSummary>) {
        people = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = people.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PersonViewHolder(inflater.inflate(R.layout.item_person, parent, false))
    }

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) {
        holder.bind(people[position], onPersonClick)
    }

    class PersonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ShapeableImageView = itemView.findViewById(R.id.personImage)
        private val name: TextView = itemView.findViewById(R.id.personName)
        private val subtitle: TextView = itemView.findViewById(R.id.personSubtitle)

        fun bind(person: PersonSummary, onPersonClick: (PersonSummary) -> Unit) {
            val context = itemView.context
            name.text = person.name

            // "3 álbumes | 27 canciones", con el plural correcto en cada mitad. Los plurales van en
            // <plurals> de strings.xml en vez de en un if: es lo que Android espera y lo que permite
            // traducir a idiomas cuyas reglas de plural no son las del español.
            val albumsText = context.resources.getQuantityString(
                R.plurals.album_count, person.albumCount, person.albumCount
            )
            val songsText = context.resources.getQuantityString(
                R.plurals.song_count, person.songCount, person.songCount
            )
            subtitle.text = context.getString(R.string.song_subtitle_format, albumsText, songsText)

            image.load(CoverArt.cover(context, person.cover), CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onPersonClick(person) }
        }
    }
}
