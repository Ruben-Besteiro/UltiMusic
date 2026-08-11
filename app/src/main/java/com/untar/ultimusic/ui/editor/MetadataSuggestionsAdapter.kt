package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.untar.ultimusic.R
import com.untar.ultimusic.model.MetadataSuggestion
import com.untar.ultimusic.model.ReleaseType
import com.untar.ultimusic.util.CoverLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lista de candidatos del diálogo de autorrelleno, con scroll infinito (ver
 * [MetadataSuggestionsDialogFragment.onViewCreated]): cuando [loadingMore] está activo se añade
 * una fila final con un spinner, para que el usuario vea que hay más página en camino sin que la
 * lista ya cargada desaparezca (a diferencia del [android.widget.ProgressBar] de pantalla completa,
 * que es solo para la búsqueda inicial).
 *
 * No usa `ListAdapter`/`DiffUtil`: cada página nueva se limita a añadirse al final de la lista
 * anterior, así que no hace falta comparar listas — un `notifyDataSetChanged` basta.
 *
 * [scope] lanza la sustitución de carátula de cada fila (ver [ViewHolder.bind]); vive tan poco como
 * la vista de la lista (se le pasa `viewLifecycleOwner.lifecycleScope`), así que una fila que se
 * recicla o se cierra el diálogo a medias no deja ninguna descarga huérfana. [coverOverride] es
 * [MetadataSuggestionsViewModel.coverArtOverride].
 *
 * [usesGenius] dice si esta lista va a preguntarle a Genius por la carátula de cada fila. Es cierto
 * solo en sugerencias de CANCIÓN **y** con token de Genius utilizable: en una de álbum Genius no
 * pinta nada, y sin token [coverOverride] devolvería null siempre, así que en los dos casos lanzar la
 * corrutina de [ViewHolder.bind] sería una llamada de más sin nada que ganar.
 */
class MetadataSuggestionsAdapter(
    private val usesGenius: Boolean,
    private val scope: CoroutineScope,
    private val coverOverride: suspend (MetadataSuggestion) -> String?,
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
            is ViewHolder -> holder.bind(items[position], usesGenius, scope, coverOverride)
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

        /** El item que está pintando esta vista AHORA MISMO, para que la sustitución asíncrona de
         * [bind] (ver más abajo) no le ponga la carátula de una fila a otra: el `RecyclerView` puede
         * reciclar esta vista para un item distinto antes de que Genius conteste. */
        private var boundItem: MetadataSuggestion? = null

        fun bind(
            item: MetadataSuggestion,
            usesGenius: Boolean,
            scope: CoroutineScope,
            coverOverride: suspend (MetadataSuggestion) -> String?
        ) {
            val context = itemView.context
            boundItem = item

            title.text = item.title
            subtitle.text = buildSubtitle(item)
            badge.text = context.getString(describeType(item.releaseType))
            itemView.setOnClickListener { onPicked(item) }

            // Sin Genius de por medio, la miniatura de iTunes es la única candidata: se pinta ya.
            if (!usesGenius) {
                cover.load(item.coverUrl, CoverLoader.get(context)) {
                    error(R.drawable.cover_placeholder)
                }
                return
            }

            // Con Genius disponible, la de iTunes es solo el respaldo (ver coverOverride): no se
            // pinta todavía, para no enseñar un momento la carátula del álbum y sustituirla después
            // por la propia de la canción — es justo lo que causaba el bug original. La fila se
            // queda con el placeholder de fondo del XML hasta que se sepa cuál de las dos gana.
            scope.launch {
                val override = coverOverride(item)
                if (boundItem !== item) return@launch // reciclada para otra fila mientras se esperaba
                cover.load(override ?: item.coverUrl, CoverLoader.get(context)) {
                    error(R.drawable.cover_placeholder)
                }
            }
        }

        /** "Artista | Álbum | Año" para una canción (mismo separador que usan las listas de
         * canciones/álbumes, ver `song_subtitle_format`). En una sugerencia de álbum queda
         * "Artista | Año" solo, sin necesidad de distinguir el caso: ahí
         * [MetadataSuggestion.albumTitle] es null porque [MetadataSuggestion.title] YA es el álbum
         * y repetirlo sería redundante. */
        private fun buildSubtitle(item: MetadataSuggestion): String =
            listOfNotNull(item.artist.takeIf { it.isNotBlank() }, item.albumTitle, item.year?.toString())
                .joinToString(itemView.context.getString(R.string.subtitle_separator))

        /** Etiqueta de la fila. A diferencia de MusicBrainz, iTunes siempre deja clasificar la
         * publicación en uno de los tres tipos (ver [ReleaseType]), así que la etiqueta nunca se
         * queda vacía y no hay tipos secundarios ("en directo", "recopilatorio"…) que añadir. */
        private fun describeType(type: ReleaseType): Int = when (type) {
            ReleaseType.ALBUM -> R.string.suggestion_type_album
            ReleaseType.SINGLE -> R.string.suggestion_type_single
            ReleaseType.EP -> R.string.suggestion_type_ep
        }
    }
}
