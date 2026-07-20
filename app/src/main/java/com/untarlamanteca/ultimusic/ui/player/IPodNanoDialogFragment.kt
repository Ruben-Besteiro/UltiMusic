package com.untarlamanteca.ultimusic.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.ui.PlayerViewModel
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverLoader
import com.untarlamanteca.ultimusic.util.TimeFormat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Ventana a pantalla completa con forma de iPod Nano. Sube deslizándose desde abajo (animación del
 * tema del diálogo) y al pulsar la "X" baja y se destruye. Comparte el [PlayerViewModel] de la
 * actividad, así que refleja siempre la misma reproducción que el mini-reproductor.
 */
class IPodNanoDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_IPodDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_ipod_nano, container, false)

    override fun onStart() {
        super.onStart()
        // Ocupar toda la pantalla (por defecto un diálogo se ajusta a su contenido).
        dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // La actividad es edge-to-edge; añadimos padding para no dibujar bajo las barras de sistema.
        val root = view.findViewById<View>(R.id.ipodRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val cover = view.findViewById<ShapeableImageView>(R.id.cover)
        val queueList = view.findViewById<RecyclerView>(R.id.queueList)
        val tvPosition = view.findViewById<TextView>(R.id.tvPosition)
        val tvDuration = view.findViewById<TextView>(R.id.tvDuration)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
        val progressBar = view.findViewById<SeekBar>(R.id.ipodProgress)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val btnMenu = view.findViewById<ImageButton>(R.id.btnMenu)
        val btnPrev = view.findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = view.findViewById<ImageButton>(R.id.btnNext)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPauseBig)

        val adapter = IPodQueueAdapter { position -> playerViewModel.jumpTo(position) }
        val queueLayoutManager = LinearLayoutManager(requireContext())
        queueList.layoutManager = queueLayoutManager
        queueList.adapter = adapter

        btnClose.setOnClickListener { dismiss() }
        // El botón de las 3 rayas alterna la pantalla entre carátula y cola.
        btnMenu.setOnClickListener {
            val showQueue = cover.isVisible
            cover.isVisible = !showQueue
            queueList.isVisible = showQueue
        }
        btnPrev.setOnClickListener { playerViewModel.previous() }
        btnNext.setOnClickListener { playerViewModel.next() }
        btnPlayPause.setOnClickListener { playerViewModel.togglePlayPause() }

        // Arrastrar/tocar la barra mueve la reproducción; mientras se toca, mostramos el tiempo
        // destino en tvPosition y evitamos que la actualización periódica pise la posición.
        var userSeeking = false
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = playerViewModel.currentSong.value?.duration ?: 0L
                    tvPosition.text = TimeFormat.mmss(dur * prog / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                playerViewModel.seekToFraction(sb.progress / 1000f)
            }
        })

        val loader = CoverLoader.get(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playerViewModel.currentSong.collect { song ->
                        if (song != null) {
                            cover.load(CoverArt.cover(song), loader) {
                                error(R.drawable.cover_placeholder)
                            }
                        } else {
                            cover.setImageResource(R.drawable.cover_placeholder)
                        }
                        tvTitle.text = song?.title ?: getString(R.string.nothing_playing)
                        tvMeta.text = song?.let { metaLine(it) } ?: ""
                    }
                }
                launch {
                    playerViewModel.isPlaying.collect { isPlaying ->
                        btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
                launch {
                    playerViewModel.progress.collect { p ->
                        // durationMs es 0 hasta que ExoPlayer prepara; caemos en song.duration.
                        val total = if (p.durationMs > 0) p.durationMs
                        else playerViewModel.currentSong.value?.duration ?: 0L
                        tvDuration.text = TimeFormat.mmss(total)
                        if (!userSeeking) {
                            tvPosition.text = TimeFormat.mmss(p.positionMs)
                            progressBar.progress =
                                if (p.durationMs > 0) ((p.positionMs * 1000) / p.durationMs).toInt()
                                else 0
                        }
                    }
                }
                launch {
                    // La vista de cola muestra la cola 1 (historial + actual + encoladas) seguida de
                    // la cola 2 (el resto mezclado): así se ve todo lo que viene a continuación.
                    combine(
                        playerViewModel.queue1,
                        playerViewModel.queue2,
                        playerViewModel.currentIndex
                    ) { q1, q2, i -> Triple(q1 + q2, i, q1.size) }
                        .collect { (combined, index, queue1Size) ->
                            adapter.submit(combined, index, queue1Size)
                            // Centrar la fila actual (la altura ya está disponible en post{}).
                            queueList.post {
                                queueLayoutManager.scrollToPositionWithOffset(
                                    index, queueList.height / 2
                                )
                            }
                        }
                }
            }
        }
    }

    /** Construye la línea "Artista · Álbum · Año" omitiendo el año si no existe. */
    private fun metaLine(song: Song): String {
        val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
        val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
        return listOfNotNull(artist, album, song.year?.toString()).joinToString(" · ")
    }
}
