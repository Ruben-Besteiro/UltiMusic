package com.untar.ultimusic.ui.preview

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
import com.untar.ultimusic.data.remote.DeezerApi
import com.untar.ultimusic.util.CoverLoader

/**
 * Lista de resultados del buscador de fragmentos (ver [PreviewSearchDialogFragment]), con scroll
 * infinito: mientras [loadingMore] está activo se añade una fila final con un spinner, reutilizando
 * el pie de [com.untar.ultimusic.ui.editor.MetadataSuggestionsAdapter].
 *
 * Tocar una fila reproduce (o pausa) su fragmento. La fila de la canción que está cargada se marca
 * con el acento dinámico en el título y en el botón de reproducir/pausar; cada cambio de
 * [PreviewPlayback] solo repinta esas dos filas (con `payload`, para que la portada no parpadee).
 */
class PreviewTrackAdapter(
    private val onTapped: (DeezerApi.PreviewTrack) -> Unit,
    private val onStoresTapped: (DeezerApi.PreviewTrack) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<DeezerApi.PreviewTrack> = emptyList()
    private var loadingMore = false
    private var accent = 0
    private var playback = PreviewPlayback.IDLE

    fun submit(tracks: List<DeezerApi.PreviewTrack>, loadingMore: Boolean) {
        items = tracks
        this.loadingMore = loadingMore
        notifyDataSetChanged()
    }

    /** El acento cambia con la canción que suena en la biblioteca, así que puede variar con el
     * diálogo abierto. */
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    fun setPlayback(new: PreviewPlayback) {
        val old = playback
        playback = new
        for (id in setOf(old.trackId, new.trackId)) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index, PAYLOAD_PLAYBACK)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (loadingMore && position == items.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ITEM

    override fun getItemCount(): Int = items.size + if (loadingMore) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOOTER) {
            FooterViewHolder(inflater.inflate(R.layout.item_metadata_suggestion_footer, parent, false))
        } else {
            ViewHolder(inflater.inflate(R.layout.item_preview_track, parent, false), onTapped, onStoresTapped)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> holder.bind(items[position], playback, accent)
            is FooterViewHolder -> holder.bind(accent)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && holder is ViewHolder) {
            holder.bindPlayback(items[position], playback, accent)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_FOOTER = 1
        const val PAYLOAD_PLAYBACK = "playback"
    }

    class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val spinner: ProgressBar = itemView.findViewById(R.id.suggestionLoadMoreSpinner)

        fun bind(accent: Int) {
            spinner.indeterminateTintList = ColorStateList.valueOf(accent)
        }
    }

    class ViewHolder(
        itemView: View,
        private val onTapped: (DeezerApi.PreviewTrack) -> Unit,
        private val onStoresTapped: (DeezerApi.PreviewTrack) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val cover: ImageView = itemView.findViewById(R.id.previewCover)
        private val stores: View = itemView.findViewById(R.id.previewStores)
        private val title: TextView = itemView.findViewById(R.id.previewTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.previewSubtitle)
        private val action: ImageView = itemView.findViewById(R.id.previewAction)
        private val buffering: ProgressBar = itemView.findViewById(R.id.previewBuffering)

        fun bind(track: DeezerApi.PreviewTrack, playback: PreviewPlayback, accent: Int) {
            val context = itemView.context
            title.text = track.title
            subtitle.text = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.albumTitle)
                .joinToString(context.getString(R.string.subtitle_separator))
            cover.load(track.coverUrl, CoverLoader.get(context)) {
                error(R.drawable.cover_placeholder)
            }
            itemView.setOnClickListener { onTapped(track) }
            stores.setOnClickListener { onStoresTapped(track) }
            bindPlayback(track, playback, accent)
        }

        /** Lo único que cambia cuando solo cambia el reproductor: título, icono y spinner. */
        fun bindPlayback(track: DeezerApi.PreviewTrack, playback: PreviewPlayback, accent: Int) {
            val context = itemView.context
            val active = playback.trackId == track.id
            val status = if (active) playback.status else PreviewPlayback.Status.IDLE
            val loading = status == PreviewPlayback.Status.LOADING
            val playing = status == PreviewPlayback.Status.PLAYING

            // El icono de play del proyecto lleva un tinte amarillo fijo en su XML (ic_play.xml):
            // se sustituye aquí por el acento dinámico, o por el gris apagado si la fila no es la
            // activa (regla del proyecto: nada amarillo fijo).
            val tint = if (active) accent else context.getColor(R.color.um_on_surface_muted)
            title.setTextColor(if (active) accent else context.getColor(R.color.um_on_background))

            buffering.isVisible = loading
            buffering.indeterminateTintList = ColorStateList.valueOf(accent)
            action.isVisible = !loading
            action.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            action.imageTintList = ColorStateList.valueOf(tint)
            action.contentDescription = context.getString(
                if (playing) R.string.preview_pause_desc else R.string.preview_play_desc
            )
        }
    }
}
