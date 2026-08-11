package com.untar.ultimusic.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * Calibra [com.untar.ultimusic.model.Song.videoOffsetMs] por oído en vez de a ciegas: primero
 * reproduce el audio local SOLO (con el reproductor real de la app, ver [PlayerViewModel] — así
 * suena con el ecualizador y el amplificador del usuario, igual que sonará de verdad; efecto
 * secundario asumido: sustituye lo que estuviera sonando y la cola, como si se tocara la canción
 * en la biblioteca) y espera a que el usuario lo pause en un instante reconocible y mapeable al
 * vídeo (un golpe de batería, un corte brusco...). Con ese instante capturado, hace lo mismo con
 * el vídeo SOLO (mudo, como manda [com.untar.ultimusic.ui.player.VideoScreenController]: el vídeo
 * nunca suena en esta app) y, al pausarlo en el mismo momento, resta las dos posiciones para
 * obtener el desplazamiento exacto.
 *
 * Funciona porque vídeo y audio son la misma grabación a la misma velocidad —solo cambia cuánta
 * "cabecera" tiene cada uno recortada—, así que un desplazamiento constante entre ambos queda
 * determinado del todo con un único instante común, sin ensayo-error.
 *
 * Mismo patrón que [LyricsSuggestionsDialogFragment] (que a su vez sigue el de
 * [com.untar.ultimusic.ui.player.VideoPickerDialogFragment]): un [DialogFragment] a pantalla
 * completa que devuelve su resultado con `setFragmentResult`/`setFragmentResultListener`. Nunca
 * guarda nada por su cuenta: [MetadataEditorDialogFragment] es quien vuelca el resultado en la
 * regla del formulario, tal cual haría el usuario a mano, así que sigue sin guardarse hasta que se
 * pulse "Guardar".
 */
class OffsetSyncDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    private lateinit var stepAudioGroup: View
    private lateinit var stepVideoGroup: View
    private lateinit var btnAudioPlayPause: ImageButton
    private lateinit var audioProgress: SeekBar
    private lateinit var tvAudioPosition: TextView
    private lateinit var tvAudioDuration: TextView
    private lateinit var btnSyncNext: MaterialButton
    private lateinit var youtubePlayerView: YouTubePlayerView
    private lateinit var btnSyncFinish: MaterialButton

    private var youTubePlayer: YouTubePlayer? = null
    private var videoInitialized = false

    /** Última posición del vídeo que se sabe con certeza, igual que
     * [com.untar.ultimusic.ui.player.VideoScreenController.lastKnownPositionMs]: la librería solo
     * informa de la posición mientras el vídeo avanza, así que en pausa se queda con el último
     * valor recibido, que es justo el instante en el que el usuario ha pausado. */
    private var lastKnownVideoPositionMs = 0L

    /** Posición del audio capturada al pasar del paso 1 al 2 (ver [goToVideoStep]). Con las dos
     * capturadas se puede calcular el desplazamiento (ver [finish]). */
    private var capturedAudioPositionMs = 0L

    /** True mientras el usuario arrastra [audioProgress], igual que en `IPodDialogFragment`: evita
     * que la actualización periódica de [PlayerViewModel.progress] le pise la posición mientras la
     * está moviendo a mano. */
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_offset_sync, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.offsetSyncRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.offsetSyncToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        stepAudioGroup = view.findViewById(R.id.stepAudioGroup)
        stepVideoGroup = view.findViewById(R.id.stepVideoGroup)
        btnAudioPlayPause = view.findViewById(R.id.btnAudioPlayPause)
        audioProgress = view.findViewById(R.id.audioProgress)
        tvAudioPosition = view.findViewById(R.id.tvAudioPosition)
        tvAudioDuration = view.findViewById(R.id.tvAudioDuration)
        btnSyncNext = view.findViewById(R.id.btnSyncNext)
        youtubePlayerView = view.findViewById(R.id.offsetSyncYoutubePlayer)
        btnSyncFinish = view.findViewById(R.id.btnSyncFinish)

        // Paso 1 ya arranca sonando (ver la cabecera de la clase): quien nos abre ya ha llamado a
        // playerViewModel.play(song) con la canción que se está editando.
        btnAudioPlayPause.setOnClickListener { playerViewModel.togglePlayPause() }
        btnSyncNext.setOnClickListener { goToVideoStep() }

        // Arrastrar mueve la reproducción real, igual que la barra del iPod (ver
        // IPodDialogFragment): así se puede recorrer el audio para acabar de afinar el instante
        // reconocible en vez de esperar a que llegue solo.
        audioProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val durationMs = playerViewModel.progress.value.durationMs
                    tvAudioPosition.text = TimeFormat.mmss(durationMs * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                playerViewModel.seekToFraction(seekBar.progress / 1000f)
            }
        })

        btnSyncFinish.setOnClickListener { finish() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playerViewModel.isPlaying.collect { isPlaying ->
                        btnAudioPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
                launch {
                    playerViewModel.progress.collect { progress ->
                        tvAudioDuration.text = TimeFormat.mmss(progress.durationMs)
                        if (!userSeeking) {
                            tvAudioPosition.text = TimeFormat.mmss(progress.positionMs)
                            audioProgress.progress = if (progress.durationMs > 0) {
                                ((progress.positionMs * 1000) / progress.durationMs).toInt()
                            } else 0
                        }
                    }
                }
                // Regla del proyecto: todo lo amarillo usa el color dinámico. El aro de los botones
                // circulares es un GradientDrawable propio (ver ipod_round_button.xml): el icono se
                // retiñe con AccentTint.icons, pero el aro necesita AccentTint.stroke aparte, igual
                // que hace IPodDialogFragment con sus mismos botones.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        AccentTint.icons(view, accent, R.id.btnAudioPlayPause)
                        AccentTint.stroke(view, R.id.btnAudioPlayPause, accent, R.dimen.ipod_stroke_button_ring)
                        audioProgress.progressTintList = ColorStateList.valueOf(accent)
                        val tint = ColorStateList.valueOf(accent)
                        btnSyncNext.backgroundTintList = tint
                        btnSyncFinish.backgroundTintList = tint
                    }
                }
            }
        }
    }

    /**
     * Cierra el paso 1 y abre el paso 2: captura dónde se ha quedado el audio, lo pausa si seguía
     * sonando (para no dejarlo de fondo mientras se mira el vídeo, aunque el vídeo sea mudo) y
     * arranca el vídeo por primera vez (ver [videoInitialized]: inicialización perezosa, igual que
     * [com.untar.ultimusic.ui.player.VideoScreenController], para no gastar datos si el usuario
     * cancela antes de llegar aquí).
     */
    private fun goToVideoStep() {
        capturedAudioPositionMs = playerViewModel.currentPositionMs()
        if (playerViewModel.isPlaying.value) playerViewModel.togglePlayPause()

        stepAudioGroup.isVisible = false
        stepVideoGroup.isVisible = true

        if (!videoInitialized) {
            videoInitialized = true
            youtubePlayerView.initialize(youTubePlayerListener, playerOptions())
        }
    }

    private val youTubePlayerListener = object : AbstractYouTubePlayerListener() {
        override fun onReady(youTubePlayer: YouTubePlayer) {
            this@OffsetSyncDialogFragment.youTubePlayer = youTubePlayer
            // El vídeo nunca suena en esta app (ver VideoScreenController): el audio de verdad ya
            // se ha escuchado entero en el paso 1.
            youTubePlayer.mute()
            youTubePlayer.loadVideo(requireArguments().getString(ARG_VIDEO_ID).orEmpty(), 0f)
        }

        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
            lastKnownVideoPositionMs = (second * 1000).toLong()
        }
    }

    /**
     * Opciones del reproductor: `controls(1)`, al revés que
     * [com.untar.ultimusic.ui.player.VideoScreenController.playerOptions] — aquí SÍ se dejan los
     * mandos propios de YouTube (play/pausa y arrastre) en vez de tapar el vídeo con los nuestros,
     * ver el porqué en el XML (`dialog_offset_sync.xml`).
     */
    private fun playerOptions(): IFramePlayerOptions =
        IFramePlayerOptions.Builder(youtubePlayerView.context).controls(1).build()

    /**
     * Con las dos posiciones capturadas (audio en [goToVideoStep], vídeo aquí mismo), el
     * desplazamiento es su resta: es la misma fórmula que
     * [com.untar.ultimusic.ui.player.VideoScreenController.withOffset] pero despejada, porque el
     * usuario ha marcado a mano el mismo instante en los dos. Se acota al mismo tope que la regla
     * del editor ([MetadataEditorDialogFragment]) para que el valor devuelto siempre quepa en ella.
     */
    private fun finish() {
        val maxMs = MetadataEditorDialogFragment.OFFSET_MAX_MS.toLong()
        val offsetMs = (lastKnownVideoPositionMs - capturedAudioPositionMs).coerceIn(-maxMs, maxMs)
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_OFFSET_MS to offsetMs.toInt()))
        dismiss()
    }

    override fun onDestroyView() {
        // Obligatorio: sin esto el WebView interno sigue vivo consumiendo batería y datos (mismo
        // motivo que VideoScreenController.release).
        youtubePlayerView.release()
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "offset_sync_result"
        const val RESULT_OFFSET_MS = "offsetMs"

        private const val ARG_VIDEO_ID = "videoId"

        fun newInstance(videoId: String): OffsetSyncDialogFragment =
            OffsetSyncDialogFragment().apply {
                arguments = bundleOf(ARG_VIDEO_ID to videoId)
            }
    }
}
