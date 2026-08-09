package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.untar.ultimusic.R
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.SuggestionKind
import com.untar.ultimusic.util.CoverLoader

/**
 * Lista de candidatos del diálogo de autorrelleno, con scroll infinito (ver
 * [MetadataSuggestionsDialogFragment.onViewCreated]): cuando [loadingMore] está activo se añade
 * una fila final con un spinner, para que el usuario vea que hay más página en camino sin que la
 * lista ya cargada desaparezca (a diferencia del [android.widget.ProgressBar] de pantalla completa,
 * que es solo para la búsqueda inicial).
 *
 * No usa `ListAdapter`/`DiffUtil`: cada página nueva se limita a añadirse al final de la lista
 * anterior, así que no hace falta comparar listas — un `notifyDataSetChanged` basta.
 */
class MetadataSuggestionsAdapter(
    private val onPicked: (MetadataSuggestion) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<MetadataSuggestion> = emptyList()
    private var loadingMore: Boolean = false
    private var accent: Int = 0

    fun submit(suggestions: List<MetadataSuggestion>, loadingMore: Boolean) {
        items = suggestions
        this.loadingMore = loadingMore
        notifyDataSetChanged()
    }

    /** El acento puede cambiar (la canción que suena cambia) mientras el pie de carga ya está en
     * pantalla: sin esto se quedaría con el color con el que se pintó la primera vez. */
    fun setAccent(color: Int) {
        accent = color
        if (loadingMore) notifyItemChanged(items.size)
    }

    override fun getItemViewType(position: Int): Int =
        if (loadingMore && position == items.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ITEM

    override fun getItemCount(): Int = items.size + if (loadingMore) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_FOOTER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_metadata_suggestion_footer, parent, false)
            FooterViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_metadata_suggestion, parent, false)
            ViewHolder(view, onPicked)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> holder.bind(items[position])
            is FooterViewHolder -> holder.bind(accent)
        }
    }

    private companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_FOOTER = 1
    }

    class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val spinner: ProgressBar = itemView.findViewById(R.id.suggestionLoadMoreSpinner)

        fun bind(accent: Int) {
            spinner.indeterminateTintList = ColorStateList.valueOf(accent)
        }
    }

    class ViewHolder(
        itemView: View,
        private val onPicked: (MetadataSuggestion) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val cover: ImageView = itemView.findViewById(R.id.suggestionCover)
        private val title: TextView = itemView.findViewById(R.id.suggestionTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.suggestionSubtitle)
        private val badge: TextView = itemView.findViewById(R.id.suggestionBadge)

        fun bind(item: MetadataSuggestion) {
            val context = itemView.context

            title.text = item.title
            subtitle.text = buildSubtitle(item)

            val badgeText = describeType(item)
            badge.text = badgeText
            badge.isVisible = badgeText != null

            cover.load(item.coverUrl, CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }

            itemView.setOnClickListener { onPicked(item) }
        }

        /** "Artista | Álbum | Año" para una canción (mismo separador que usan las listas de
         * canciones/álbumes, ver `song_subtitle_format`); "Artista | Año" para un álbum, donde
         * [MetadataSuggestion.title] YA es el álbum y repetirlo sería redundante. */
        private fun buildSubtitle(item: MetadataSuggestion): String {
            val year = item.year?.toString()
            val parts = if (item.kind == SuggestionKind.SONG) {
                listOfNotNull(item.artist.takeIf { it.isNotBlank() }, item.albumTitle, year)
            } else {
                listOfNotNull(item.artist.takeIf { it.isNotBlank() }, year)
            }
            return parts.joinToString(itemView.context.getString(R.string.subtitle_separator))
        }

        /** Traduce `primaryType`/`secondaryTypes` (en inglés, tal como los da MusicBrainz) a algo
         * legible, p. ej. "Single" o "Álbum (en directo)". Null si MusicBrainz no clasificó la
         * publicación (pasa con datos antiguos o mal etiquetados por la comunidad). */
        private fun describeType(item: MetadataSuggestion): String? {
            val context = itemView.context
            val primaryLabel = when (item.primaryType) {
                "Album" -> context.getString(R.string.suggestion_type_album)
                "Single" -> context.getString(R.string.suggestion_type_single)
                "EP" -> context.getString(R.string.suggestion_type_ep)
                null -> null
                else -> context.getString(R.string.suggestion_type_other)
            } ?: return null

            val secondaryLabels = item.secondaryTypes.mapNotNull {
                when (it) {
                    "Live" -> context.getString(R.string.suggestion_type_live)
                    "Compilation" -> context.getString(R.string.suggestion_type_compilation)
                    "Soundtrack" -> context.getString(R.string.suggestion_type_soundtrack)
                    "Remix" -> context.getString(R.string.suggestion_type_remix)
                    else -> null // el resto no se traduce: mejor omitirlo que enseñar el inglés suelto
                }
            }
            return if (secondaryLabels.isEmpty()) primaryLabel else "$primaryLabel (${secondaryLabels.joinToString(", ")})"
        }
    }
}
