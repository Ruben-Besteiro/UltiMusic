package com.untar.ultimusic.ui.player

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.SquareFrameLayout
import com.untar.ultimusic.ui.common.attachVerticalDrag
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.VideoModeSettings
import com.untar.ultimusic.util.YouTubeUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ventana a pantalla completa con forma de iPod Nano. Se abre de dos formas: tocando la flecha del
 * mini-reproductor (sube deslizándose sola, ver [Animation.UltiMusic.IPodEnterOnly]) o arrastrando
 * el propio mini-reproductor hacia arriba, en cuyo caso esta ventana se crea ya al empezar el
 * arrastre —escondida bajo la pantalla— y [followOpenDrag] la va destapando en vivo con el dedo
 * (ver [MainActivity.setupMiniPlayerDrag] y [suppressEnterAnim]). Se cierra siempre igual, venga de
 * donde venga la apertura: pulsando la "X", con el botón atrás o arrastrando hacia abajo desde una
 * zona sin controles (ver [attachVerticalDrag]); las tres vías pasan por [animateClose], que
 * traslada la ventana hasta salir por abajo antes de destruirla, así el gesto se ve siempre
 * continuo aunque se suelte a medio arrastrar. Comparte el [PlayerViewModel] de la actividad, así
 * que refleja siempre la misma reproducción que el mini-reproductor.
 *
 * Tiene DOS modos:
 *  - **Normal** (sin argumentos): expandido desde el mini-reproductor, muestra lo que suena y su
 *    cola. Todo se lee de [PlayerViewModel].
 *  - **Navegación de playlist** ([newInstance] con un nombre): abre mostrando el contenido de esa
 *    playlist para ELEGIR qué sonará después, con scroll+tap, con las flechas + play, o al azar con
 *    el botón de las 3 rayas (que aquí es un icono de aleatorio). El recuadro de info, la carátula,
 *    el play/pausa, el progreso y el acento de color siguen reflejando SIEMPRE lo que suena de
 *    verdad en la app (venga de esta playlist, de otra o de una canción suelta): solo la lista
 *    central cambia a la de esta playlist para poder elegir. Al elegir una fila o pulsar play,
 *    interrumpe lo que sonara y empieza esa colección (vía [PlayerViewModel.playCollection] /
 *    [PlayerViewModel.shuffleCollection]), y la ventana pasa a comportarse como el modo normal. Si
 *    al abrir la ventana esa misma playlist ya era la que sonaba, se entra directamente en modo
 *    normal (ver [PlayerViewModel.currentPlaylistName]). Además, cada fila se puede arrastrar para
 *    reordenar la playlist, y el nuevo orden se guarda en el archivo.
 *
 * Aparte de esos dos modos, la pantalla tiene un **modo vídeo** que se activa con el botón de la
 * derecha de la cabecera (ver [videoMode]).
 */
class IPodNanoDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()

    /** Nombre de la playlist si estamos en modo navegación; null en modo normal. */
    private val playlistName: String? get() = arguments?.getString(ARG_PLAYLIST)

    /**
     * True si esta instancia se abrió arrastrando el mini-reproductor (ver [forDrag]). En ese caso
     * la ventana no debe llevar NINGUNA animación propia: quien la abre así ya la traslada a mano en
     * vivo con el dedo (ver [followOpenDrag]), y una animación de ventana encima duplicaría el
     * movimiento.
     */
    private val suppressEnterAnim: Boolean get() = arguments?.getBoolean(ARG_SUPPRESS_ENTER_ANIM) ?: false

    /** Evita disparar [animateClose] dos veces (p.ej. arrastre + botón atrás casi a la vez). */
    private var closing = false

    /**
     * True mientras se está eligiendo canción de una playlist (modo navegación) sin haber pulsado
     * aún play/fila/aleatorio. Solo afecta al colector de la cola combinada (cola 1 + cola 2): se
     * salta mientras [browsing] es true porque la lista central la ocupa la playlist, no la cola
     * real. El resto de colectores (título, carátula, play/pausa, progreso, acento) NO se gobiernan
     * por esta variable: siempre reflejan lo que suena de verdad. Pasa a false en cuanto el usuario
     * elige una canción.
     */
    private var browsing = false

    /** Cursor de la fila seleccionada con las flechas (la que reproduciría el botón de play). */
    private var selectedIndex = 0

    /** Ayudante de arrastre para reordenar en modo navegación; se desengancha al salir de él. */
    private var itemTouchHelper: ItemTouchHelper? = null

    /**
     * Corrutina que, en modo navegación, mantiene la fila "altavoz" y el botón de menú al día con
     * la reproducción real. Se cancela al salir de la navegación (ver [startPlaybackFromBrowse]),
     * porque a partir de ahí ese trabajo ya lo hacen los controles del modo normal.
     */
    private var browseJob: Job? = null

    // ===================================================================================
    // Modo vídeo.
    //
    // Con el botón de la cabecera, la pantalla del iPod cambia la carátula (o la cola) por el
    // videoclip de la canción. Pulsándolo otra vez se vuelve exactamente a como estaba.
    //
    // El sonido SIEMPRE lo pone el archivo local (el ExoPlayer de [PlayerViewModel]): el modo vídeo
    // no lo pausa nunca. El videoclip de YouTube es una capa puramente visual y muda que se limita a
    // seguir la posición y el play/pausa del audio local (ver [VideoScreenController.sync]), así no
    // hay hueco de silencio al entrar ni nada que reconciliar al volver de segundo plano.
    //
    // El enlace del vídeo sale de `Song.videoUrl`, que rellena SIEMPRE el usuario: a mano en el
    // editor de metadatos, o eligiéndolo en el buscador ([VideoPickerDialogFragment]) que se abre
    // solo la primera vez, cuando el campo está vacío.
    // ===================================================================================

    /** True mientras la pantalla muestra el videoclip (mudo) en vez de la carátula/cola. */
    private var videoMode = false

    /**
     * Id de la canción cuyo vídeo se está viendo. Sirve para detectar que la canción ha cambiado
     * (con las flechas, o porque terminó) y salir solos del modo vídeo: el vídeo pertenece a una
     * canción concreta, y el de la siguiente puede no estar ni elegido todavía.
     */
    private var videoSongId: Long? = null

    /**
     * Qué se veía en la pantalla antes de entrar en modo vídeo (carátula o cola), para restaurarlo
     * al salir en vez de dejar siempre la carátula.
     */
    private var queueWasVisible = false

    /** Dueño del [YouTubePlayerView]. Se libera en [onDestroyView]. */
    private var videoController: VideoScreenController? = null

    /**
     * Id del vídeo que se está viendo, para poder cargar su miniatura oficial (ver
     * [YouTubeUrl.thumbnailUrl]) al entrar en modo vídeo con la canción pausada. Es distinto de
     * [videoSongId]: ese es el id de la CANCIÓN, este el del VÍDEO de YouTube.
     */
    private var currentVideoId: String? = null

    /**
     * True mientras se está viendo la miniatura estática en vez del WebView de YouTube. Solo pasa al
     * entrar en modo vídeo con la canción pausada y [VideoModeSettings] así lo diga (ver [startVideo]);
     * en cuanto arranca la reproducción se pone a false para siempre en esa sesión de vídeo, así que
     * pausas posteriores YA NO vuelven a la miniatura, se quedan con el fotograma congelado del propio
     * WebView (ver el colector de [PlayerViewModel.isPlaying]).
     */
    private var showingThumbnail = false

    /**
     * Cómo pintar y cablear el botón de las 3 rayas cuando NO se está en modo vídeo (modo normal o
     * navegación de playlist). Se guarda aquí, en vez de aplicarse directamente, porque en modo vídeo
     * ese botón lo ocupa el engranaje de ajustes (ver [applyMenuButton]): así [exitVideo] puede
     * reponer exactamente lo que tocaba sin tener que saber en qué modo estaba.
     */
    private var restoreMenuButton: () -> Unit = {}

    /**
     * Fija el icono+listener del botón de las 3 rayas para cuando NO se está en modo vídeo, y lo
     * recuerda en [restoreMenuButton]. Si ya estamos en modo vídeo al llamarla (p. ej. la canción
     * cambia mientras se navega una playlist con el vídeo puesto), NO se aplica todavía —eso taparía
     * el engranaje—, solo se recuerda para cuando se salga del modo vídeo.
     */
    private fun applyMenuButton(action: () -> Unit) {
        restoreMenuButton = action
        if (!videoMode) action()
    }

    /**
     * Vídeos elegidos en el buscador durante esta sesión, por id de canción.
     *
     * Hace falta porque [PlayerViewModel] guarda una **copia en memoria** de las canciones de la
     * cola: no observa la base de datos, así que el `videoUrl` que acabamos de escribir en Room no
     * aparece en `currentSong` hasta que la cola se reconstruye. Sin este mapa, apagar y volver a
     * encender el modo vídeo en la misma sesión abriría otra vez el buscador para una canción a la
     * que ya se le había elegido vídeo.
     */
    private val pickedVideoIds = mutableMapOf<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_IPodDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_ipod_nano, container, false)

    /**
     * El botón atrás del sistema, por defecto, cierra el diálogo de golpe sin animación. Se
     * intercepta para que pase por [animateClose] igual que la "X" y el arrastre, y así el cierre
     * se vea siempre igual venga de donde venga. Se parte de [ComponentDialog] en vez de un
     * [Dialog] a secas porque es lo que devuelve la implementación por defecto de
     * [DialogFragment.onCreateDialog]: perderlo dejaría sin dueño de ciclo de vida a la ventana del
     * diálogo (y sin soporte del gesto de "atrás" predictivo).
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        object : ComponentDialog(requireContext(), theme) {
            @Suppress("DEPRECATION")
            override fun onBackPressed() {
                val root = view
                if (root != null) animateClose(root) else super.onBackPressed()
            }
        }

    override fun onStart() {
        // Hay que fijar la animación de ventana ANTES de super.onStart(), que es quien muestra el
        // diálogo: hacerlo después llegaría tarde para la animación de entrada. Sin animación
        // propia de salida nunca (ver [Animation.UltiMusic.IPodEnterOnly]): el cierre siempre lo
        // anima [animateClose] a mano, para poder arrancarlo desde una posición a medio camino si
        // viene de un arrastre. Si además se abrió arrastrando el mini-reproductor, tampoco lleva
        // animación de entrada (ver [suppressEnterAnim]): ya la trae hecha a mano el arrastre.
        dialog?.window?.setWindowAnimations(
            if (suppressEnterAnim) 0 else R.style.Animation_UltiMusic_IPodEnterOnly
        )
        super.onStart()
        // Ocupar toda la pantalla (por defecto un diálogo se ajusta a su contenido).
        dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    /**
     * Traslada la ventana en vivo mientras el usuario mantiene el dedo arrastrando el
     * mini-reproductor hacia arriba: [fraction] 0f la deja escondida del todo bajo la pantalla y 1f
     * la deja en su sitio. La llama [MainActivity.setupMiniPlayerDrag] en cada MOVE del gesto. No
     * hace nada si la vista todavía no existe (el primer MOVE que crea esta ventana puede llegar
     * antes de que su vista esté lista).
     */
    fun followOpenDrag(fraction: Float) {
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        view?.translationY = screenHeight * (1f - fraction.coerceIn(0f, 1f))
    }

    /**
     * Al soltar el dedo tras arrastrar el mini-reproductor: si se superó el umbral, termina de
     * abrirse deslizándose el resto del camino; si no, se esconde otra vez y se destruye (con la
     * misma animación de cierre que cualquier otro cierre, ver [animateClose]).
     */
    fun finishOpenDrag(committed: Boolean) {
        val root = view ?: return
        if (committed) {
            root.animate().translationY(0f).setDuration(OPEN_DRAG_ANIM_MS).start()
        } else {
            animateClose(root)
        }
    }

    /**
     * Cierra la ventana trasladando [root] hasta salir por abajo y solo entonces la destruye.
     * Arranca siempre desde la posición actual de [root] (0 si estaba totalmente abierta, o a medio
     * camino si el cierre viene de soltar un arrastre a medias), así el gesto se ve siempre
     * continuo. La usan la "X", el botón atrás y el arrastre hacia abajo (ver [onViewCreated]).
     */
    private fun animateClose(root: View) {
        if (closing) return
        closing = true
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        root.animate()
            .translationY(screenHeight)
            .setDuration(CLOSE_ANIM_MS)
            .withEndAction { dismiss() }
            .start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // La actividad es edge-to-edge; añadimos padding para no dibujar bajo las barras de sistema.
        val root = view.findViewById<View>(R.id.ipodRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Igual que el mini-reproductor se abre arrastrando hacia arriba, el iPod se cierra
        // arrastrando hacia abajo (ver [attachVerticalDrag]), y [root] ya sigue el dedo en vivo
        // mientras se arrastra. Al soltar por encima del umbral, [animateClose] completa el
        // cierre desde donde esté (no hace falta "devolverla" a 0 antes: eso daría el salto que
        // este mismo mecanismo evita). Solo llega el gesto en las zonas sin un control propio
        // debajo (botones, barra de progreso, cola): eso ya evita que les robe el toque a esos
        // controles.
        val closeThreshold = 96 * resources.displayMetrics.density
        root.attachVerticalDrag(upward = false, triggerDistancePx = closeThreshold) { animateClose(root) }

        val topBox = view.findViewById<SquareFrameLayout>(R.id.topBox)
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
        val btnVideo = view.findViewById<ImageButton>(R.id.btnVideo)
        val youtubePlayer = view.findViewById<YouTubePlayerView>(R.id.youtubePlayer)
        val videoThumbnail = view.findViewById<ImageView>(R.id.videoThumbnail)

        val loader = CoverLoader.get(requireContext())

        // Adaptador de la cola en reproducción (modo normal).
        val queueAdapter = IPodQueueAdapter { position -> playerViewModel.jumpTo(position) }
        queueAdapter.setAccent(playerViewModel.accentColor.value)
        val queueLayoutManager = LinearLayoutManager(requireContext())
        queueList.layoutManager = queueLayoutManager
        queueList.adapter = queueAdapter

        btnClose.setOnClickListener { animateClose(root) }

        // ---------------------------------------------------------------------------------------
        // Entrada y salida del modo vídeo.
        //
        // `exitVideo` se declara ANTES de crear el controlador porque este último lo necesita en su
        // callback de error (si el vídeo falla, se sale solo del modo vídeo). Por eso usa el campo
        // [videoController] en vez de la variable local `controller`, que todavía no existe aquí.
        // ---------------------------------------------------------------------------------------

        /** Oculta el videoclip y vuelve a la carátula/cola. El audio local no se ha tocado nunca. */
        val exitVideo = {
            videoMode = false
            videoSongId = null
            currentVideoId = null
            showingThumbnail = false
            videoController?.pause()

            youtubePlayer.isVisible = false
            videoThumbnail.isVisible = false
            cover.isVisible = !queueWasVisible
            queueList.isVisible = queueWasVisible
            btnVideo.setImageResource(R.drawable.ic_video)
            // El botón de las 3 rayas vuelve a ser lo que tocaba fuera de modo vídeo (menú normal o
            // el de la navegación de playlist, ver [applyMenuButton]).
            restoreMenuButton()
            // Vuelve a ser cuadrada, como fuera del modo vídeo.
            topBox.animateAspectRatio(1f)
        }

        /**
         * "Ajustes del reproductor de vídeo": de momento, la única opción es qué se ve al entrar en
         * modo vídeo con la canción ya pausada (ver [VideoModeSettings] y [startVideo]) — el
         * fotograma congelado del propio vídeo, con el HUD de YouTube (título, botón de reanudar,
         * logo…), o su miniatura oficial sin HUD. Solo afecta a ESA entrada: una vez dentro del modo
         * vídeo, pausar a mitad de reproducción siempre deja el fotograma congelado (ver el colector
         * de [PlayerViewModel.isPlaying] más abajo), nunca vuelve a la miniatura.
         */
        val showVideoSettingsDialog = {
            val options = arrayOf(
                getString(R.string.video_settings_option_frame),
                getString(R.string.video_settings_option_thumbnail)
            )
            val checked = if (VideoModeSettings.showThumbnailWhenPaused(requireContext())) 1 else 0
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.video_settings_title)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    VideoModeSettings.setShowThumbnailWhenPaused(requireContext(), which == 1)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Dueño del reproductor de YouTube. Es mudo: la barra de progreso y el botón de play/pausa
        // los llevan siempre los colectores del audio local (más abajo), que además lo mantienen
        // sincronizado con [VideoScreenController.sync]. Si el vídeo falla, se sale del modo vídeo
        // solo: el audio local sigue sonando sin problema.
        val controller = VideoScreenController(
            view = youtubePlayer,
            onPlaybackError = { if (videoMode) exitVideo() }
        )
        videoController = controller

        /**
         * Muestra el videoclip (mudo) arrancando justo donde va el audio local, sin tocarlo. Por eso
         * no hay hueco de silencio: el audio nunca se pausa para esperar al vídeo.
         */
        val startVideo = { videoId: String, song: Song ->
            videoMode = true
            videoSongId = song.id
            currentVideoId = videoId
            // Se recuerda si se estaba viendo la cola para devolver la pantalla a como estaba.
            queueWasVisible = queueList.isVisible
            cover.isVisible = false
            queueList.isVisible = false
            // El botón de las 3 rayas pasa a ser el engranaje de ajustes (ver [showVideoSettingsDialog]).
            btnMenu.setImageResource(R.drawable.ic_settings)
            btnMenu.setOnClickListener { showVideoSettingsDialog() }

            // Si la canción ya estaba en pausa, [VideoModeSettings] decide qué se ve: la miniatura
            // (por defecto) o el fotograma congelado del propio vídeo. Si estaba sonando, siempre el
            // vídeo en directo: no hay nada que "entrar en pausa" que decidir.
            val playingNow = playerViewModel.isPlaying.value
            val useThumbnail = !playingNow && VideoModeSettings.showThumbnailWhenPaused(requireContext())
            showingThumbnail = useThumbnail
            youtubePlayer.isVisible = !useThumbnail
            videoThumbnail.isVisible = useThumbnail
            if (useThumbnail) {
                // Mismo ImageLoader que las carátulas: así no se duplica la caché en memoria con una
                // segunda instancia de Coil solo para esto.
                videoThumbnail.load(YouTubeUrl.thumbnailUrl(videoId), loader)
            }

            btnVideo.setImageResource(R.drawable.ic_music_note)
            // La pantalla del iPod pasa de cuadrada a 16:9 para no dejar bandas vacías arriba y
            // abajo del videoclip.
            topBox.animateAspectRatio(VIDEO_ASPECT_RATIO)
            controller.load(videoId, playerViewModel.currentPositionMs(), playingNow)
        }

        // --- Cableado del modo NORMAL (se sobrescribe si entramos en navegación) ---
        val setupNormalControls = {
            // El botón de las 3 rayas alterna la pantalla entre carátula y cola. En modo vídeo lo
            // ocupa el engranaje de ajustes (ver [applyMenuButton] y [startVideo]), así que esto no
            // se aplica hasta que se salga de él.
            applyMenuButton {
                btnMenu.setImageResource(R.drawable.ic_menu)
                btnMenu.setOnClickListener {
                    val showQueue = cover.isVisible
                    cover.isVisible = !showQueue
                    queueList.isVisible = showQueue
                }
            }
            btnPrev.setOnClickListener { playerViewModel.previous() }
            btnNext.setOnClickListener { playerViewModel.next() }
            // El play/pausa siempre gobierna el audio local, esté o no visible el vídeo.
            btnPlayPause.setOnClickListener { playerViewModel.togglePlayPause() }
        }
        setupNormalControls()

        // Arrastrar/tocar la barra mueve la reproducción; mientras se toca, mostramos el tiempo
        // destino en tvPosition y evitamos que la actualización periódica pise la posición. Esto
        // controla lo que suena de verdad, así que funciona igual en modo navegación: mientras se
        // elige canción de una playlist, la barra sigue perteneciendo a la reproducción real.
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
                val fraction = sb.progress / 1000f
                playerViewModel.seekToFraction(fraction)
                if (videoMode) {
                    val dur = playerViewModel.currentSong.value?.duration ?: 0L
                    // Salto inmediato: no esperamos al siguiente tick de sincronización.
                    controller.seekTo((dur * fraction).toLong())
                }
            }
        })

        btnVideo.setOnClickListener {
            if (videoMode) {
                exitVideo()
                return@setOnClickListener
            }
            val song = playerViewModel.currentSong.value
            if (song == null) {
                Toast.makeText(requireContext(), R.string.video_no_song, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Lo elegido en esta sesión manda sobre lo que traiga la canción (ver [pickedVideoIds]).
            val videoId = pickedVideoIds[song.id] ?: YouTubeUrl.videoId(song.videoUrl)
            if (videoId != null) {
                startVideo(videoId, song)
                return@setOnClickListener
            }
            if (song.videoUrl.isNullOrBlank()) {
                // Todavía no tiene vídeo: que lo elija el usuario. Al volver del buscador se guarda
                // y ya no se vuelve a preguntar nunca más por esta canción.
                VideoPickerDialogFragment.newInstance(searchQuery(song))
                    .show(childFragmentManager, TAG_VIDEO_PICKER)
                return@setOnClickListener
            }
            // Tiene algo escrito, pero no es un enlace de YouTube reconocible.
            Toast.makeText(requireContext(), R.string.video_bad_url, Toast.LENGTH_LONG).show()
        }

        // El buscador devuelve el vídeo elegido por aquí (ver [VideoPickerDialogFragment]).
        childFragmentManager.setFragmentResultListener(
            VideoPickerDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val videoId = bundle.getString(VideoPickerDialogFragment.RESULT_VIDEO_ID)
            val song = playerViewModel.currentSong.value
            if (videoId != null && song != null) {
                pickedVideoIds[song.id] = videoId
                // Se guarda en la canción; como Room es reactivo, el editor de metadatos y todo lo
                // demás ven el enlace nuevo al instante.
                viewLifecycleOwner.lifecycleScope.launch {
                    LibraryRepository.get(requireContext())
                        .setVideoUrl(song.id, YouTubeUrl.watchUrl(videoId))
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.video_picker_saved, song.title),
                    Toast.LENGTH_SHORT
                ).show()
                startVideo(videoId, song)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // El título, la carátula y el resto de info reflejan siempre lo que suena de
                    // verdad, esté o no la ventana en modo navegación de una playlist: nunca se debe
                    // fingir que no suena nada si en realidad sí suena algo (aunque sea de otro origen).
                    playerViewModel.currentSong.collect { song ->
                        // El vídeo pertenece a UNA canción: si ha cambiado (flechas, fin de la
                        // canción, salto en la cola...), se sale solo del modo vídeo y el sonido
                        // vuelve al archivo local, que es el que sabe seguir la cola. El vídeo de la
                        // siguiente canción puede no estar ni elegido todavía.
                        if (videoMode && song?.id != videoSongId) exitVideo()
                        if (song != null) {
                            cover.load(CoverArt.cover(requireContext(), song), loader) {
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
                        // El botón siempre refleja el audio local, esté o no visible el vídeo.
                        btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                        // El vídeo (mudo) se pone en el mismo estado, al instante, sin esperar al
                        // siguiente tick de progreso.
                        if (videoMode) {
                            videoController?.sync(playerViewModel.currentPositionMs(), isPlaying)
                            // La miniatura solo tapa la entrada en pausa (ver [startVideo]): en
                            // cuanto arranca la reproducción se descubre el vídeo para siempre en
                            // esta sesión. Pausas posteriores se quedan con el fotograma congelado
                            // del propio WebView (con su HUD), nunca vuelven a la miniatura.
                            if (showingThumbnail && isPlaying) {
                                showingThumbnail = false
                                youtubePlayer.isVisible = true
                                videoThumbnail.isVisible = false
                            }
                        }
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
                        // Tick periódico (~500ms) que mantiene el vídeo mudo pegado al audio local;
                        // es lo que autocorrige cualquier deriva al volver de segundo plano.
                        if (videoMode) {
                            videoController?.sync(p.positionMs, playerViewModel.isPlaying.value)
                        }
                    }
                }
                launch {
                    // Todo lo que en el XML es amarillo (contornos del cuerpo, de la pantalla, del
                    // recuadro de información y de la rueda, más los cuatro botones y la barra de
                    // progreso) pasa a llevar el color de la carátula que suena.
                    playerViewModel.accentColor.collect { accent ->
                        tintIPod(view, accent)
                    }
                }
                launch {
                    // La vista de cola muestra la cola 1 (historial + actual + encoladas) seguida de
                    // la cola 2 (el resto mezclado): así se ve todo lo que viene a continuación.
                    combine(
                        playerViewModel.queue1,
                        playerViewModel.queue2,
                        playerViewModel.currentIndex
                    ) { q1, q2, i -> Pair(q1 + q2, i) }
                        .collect { (combined, index) ->
                            if (browsing) return@collect
                            queueAdapter.submit(combined, index)
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

        // Si nos han abierto para una playlist que NO es ya lo que está sonando, arrancamos en modo
        // navegación. Si esa misma playlist ya es la cola actual (el usuario reabre el iPod sobre
        // ella), nos quedamos en modo normal para reflejar lo que suena de verdad.
        val name = playlistName
        if (name != null && playerViewModel.currentPlaylistName.value != name) {
            enterBrowseMode(
                name, cover, queueList, queueLayoutManager,
                btnMenu, btnPrev, btnNext, btnPlayPause,
                queueAdapter, setupNormalControls
            )
        }
    }

    /**
     * Configura la ventana como navegador de la playlist [name]: lista arrastrable en pantalla.
     * El botón de las 3 rayas, las flechas y el play se comportan de una forma u otra según si la
     * canción que suena de verdad está en esta playlist (ver [PlaylistQueueAdapter.containsNowPlaying]
     * y la función local `refreshBrowseControls`): si no está, es selección pura (aleatorio +
     * flechas moviendo un cursor sin reproducir); si está, ya no tiene sentido "elegir": 3 rayas
     * alterna carátula/lista como en modo normal, y las flechas van a la canción anterior/siguiente
     * **de esta playlist** (no a la de la cola real genérica, que puede ser una colección distinta).
     */
    private fun enterBrowseMode(
        name: String,
        cover: ShapeableImageView,
        queueList: RecyclerView,
        layoutManager: LinearLayoutManager,
        btnMenu: ImageButton,
        btnPrev: ImageButton,
        btnNext: ImageButton,
        btnPlayPause: ImageButton,
        queueAdapter: IPodQueueAdapter,
        setupNormalControls: () -> Unit
    ) {
        browsing = true
        selectedIndex = 0

        // Muestra la lista (para hacer scroll y pinchar) en lugar de la carátula. El resto de la
        // info (título, carátula, play/pausa, progreso, acento) NO se toca aquí: los colectores de
        // más arriba siguen activos y pintan lo que suena de verdad, aunque estemos navegando.
        cover.isVisible = false
        queueList.isVisible = true

        lateinit var playlistAdapter: PlaylistQueueAdapter
        playlistAdapter = PlaylistQueueAdapter(
            onItemClick = { pos ->
                // Si esa fila es justo la que suena de verdad, no tiene sentido "reproducirla de
                // nuevo" (la reiniciaría desde 0:00): solo alternamos pausa/reproducción real.
                if (playlistAdapter.isRowNowPlaying(pos)) {
                    playerViewModel.togglePlayPause()
                } else {
                    startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                        playerViewModel.playCollection(it.currentSongs(), pos, name)
                    }
                }
            },
            onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) },
            // Al soltar tras arrastrar, persistimos el nuevo orden en el archivo de la playlist.
            onReordered = { newList ->
                playlistsViewModel.reorder(name, newList.map { File(it.filePath).name })
            }
        )
        playlistAdapter.setAccent(playerViewModel.accentColor.value)
        queueList.adapter = playlistAdapter

        // Arrastre solo vertical, iniciado desde el manejador (no por pulsación larga).
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                playlistAdapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                playlistAdapter.commitReorder()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(queueList) }

        // Función local que resalta la fila del cursor y la centra. El recuadro de info y la
        // carátula no se tocan aquí: siguen reflejando lo que suena de verdad (ver colectores).
        fun renderSelected() {
            playlistAdapter.setSelected(selectedIndex)
            queueList.post { layoutManager.scrollToPositionWithOffset(selectedIndex, queueList.height / 2) }
        }

        // El botón de las 3 rayas y las flechas dependen de si la canción que suena de verdad está
        // en esta playlist: si NO está, seguimos en selección pura (aleatorio + cursor sin
        // reproducir). Si SÍ está, no tiene sentido "elegir" nada —ya se está mostrando lo que
        // realmente suena—, así que 3 rayas alterna carátula/lista como en modo normal, y las
        // flechas van a la canción anterior/siguiente **de esta playlist** (no de la cola real, que
        // puede ser otra cosa distinta: por ejemplo, si sonaba una canción suelta desde Canciones, su
        // "siguiente" real es cualquier canción al azar de la biblioteca, no la de esta lista). El
        // cursor pasa a seguir siempre a la fila que suena, para que el play (más abajo) la alterne
        // en vez de reiniciarla.
        fun refreshBrowseControls() {
            if (playlistAdapter.containsNowPlaying()) {
                selectedIndex = playlistAdapter.nowPlayingIndex()
                renderSelected()
                // Igual que en modo normal: en modo vídeo este botón lo ocupa el engranaje de
                // ajustes (ver [applyMenuButton]), así que esto no se aplica hasta salir de él.
                applyMenuButton {
                    btnMenu.setImageResource(R.drawable.ic_menu)
                    btnMenu.setOnClickListener {
                        val showQueue = cover.isVisible
                        cover.isVisible = !showQueue
                        queueList.isVisible = showQueue
                    }
                }
                // Ir a la anterior/siguiente canción de ESTA playlist equivale a pinchar la fila de
                // al lado: interrumpe lo que sonaba e inicia la playlist desde ahí (sale de
                // navegación), igual que startPlaybackFromBrowse hace con cualquier otra fila.
                btnPrev.setOnClickListener {
                    val idx = playlistAdapter.nowPlayingIndex()
                    if (idx > 0) {
                        startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                            playerViewModel.playCollection(it.currentSongs(), idx - 1, name)
                        }
                    }
                }
                btnNext.setOnClickListener {
                    val idx = playlistAdapter.nowPlayingIndex()
                    if (idx < playlistAdapter.itemCount - 1) {
                        startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                            playerViewModel.playCollection(it.currentSongs(), idx + 1, name)
                        }
                    }
                }
            } else {
                applyMenuButton {
                    btnMenu.setImageResource(R.drawable.ic_shuffle)
                    btnMenu.setOnClickListener {
                        startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                            playerViewModel.shuffleCollection(it.currentSongs(), name)
                        }
                    }
                }
                btnPrev.setOnClickListener {
                    if (selectedIndex > 0) { selectedIndex--; renderSelected() }
                }
                btnNext.setOnClickListener {
                    if (selectedIndex < playlistAdapter.itemCount - 1) { selectedIndex++; renderSelected() }
                }
            }
        }
        refreshBrowseControls()

        // El play reproduce la canción seleccionada, salvo que el cursor esté ya sobre la que suena
        // de verdad: entonces no tiene sentido reiniciarla, solo alternamos pausa/reproducción real.
        btnPlayPause.setOnClickListener {
            if (playlistAdapter.isSelectedNowPlaying()) {
                playerViewModel.togglePlayPause()
            } else {
                startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                    playerViewModel.playCollection(it.currentSongs(), selectedIndex, name)
                }
            }
        }

        // Mientras se navega, mantiene el adaptador y los controles (menú + flechas) al día con la
        // reproducción real (por si cambia mientras la ventana sigue abierta en modo navegación). Se
        // cancela al salir de este modo (ver [startPlaybackFromBrowse]).
        browseJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.currentSong.collect { song ->
                    playlistAdapter.setNowPlaying(song?.filePath)
                    refreshBrowseControls()
                }
            }
        }

        // Cargamos las canciones de la playlist (resolviendo nombres de archivo a canciones reales).
        viewLifecycleOwner.lifecycleScope.launch {
            val index = playlistsViewModel.songIndex()
            val songs = PlaylistRepository.get().resolveSongs(name, index)
            val nowPlayingPath = playerViewModel.currentSong.value?.filePath
            // Si lo que suena de verdad está en esta playlist, el cursor arranca en su fila (en vez
            // de en la primera) para que las flechas y el play partan de donde uno lo esperaría.
            val initialIndex = songs.indexOfFirst { it.filePath == nowPlayingPath }.let { if (it >= 0) it else 0 }
            playlistAdapter.submit(songs, initialIndex)
            playlistAdapter.setNowPlaying(nowPlayingPath)
            selectedIndex = initialIndex
            renderSelected()
            refreshBrowseControls()
        }
    }

    /**
     * Sale del modo navegación y arranca la reproducción indicada por [play]. Restaura los controles
     * normales; a partir de aquí los colectores de [PlayerViewModel] retoman el pintado.
     *
     * No fuerza la vista de carátula: la pantalla se queda como esté (si se estaba viendo la cola
     * de la playlist, ahora pasará a verse la cola real), igual da si se ha elegido tocando una
     * fila, con las flechas, al azar o con el botón de play — las cuatro formas de elegir canción
     * se comportan igual.
     */
    private fun startPlaybackFromBrowse(
        queueList: RecyclerView,
        queueAdapter: IPodQueueAdapter,
        cover: ShapeableImageView,
        btnMenu: ImageButton,
        setupNormalControls: () -> Unit,
        play: (PlaylistQueueAdapter) -> Unit
    ) {
        val playlistAdapter = queueList.adapter as? PlaylistQueueAdapter ?: return
        browsing = false
        browseJob?.cancel()
        browseJob = null
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        queueAdapter.setAccent(playerViewModel.accentColor.value)
        queueList.adapter = queueAdapter
        setupNormalControls()
        play(playlistAdapter)
    }

    /**
     * Aplica el color [accent] a los botones, la barra de progreso, los contornos del iPod (cuerpo,
     * pantalla, recuadro de info, anillo y centro de la rueda), los rellenos del cuerpo y del centro
     * de la rueda, y el icono de altavoz de la fila "sonando ahora" de la cola.
     *
     * Es el sitio donde se ve mejor la regla de los dos amarillos (ver [AccentTint]): todo lo que en
     * el XML era `um_yellow` lleva el acento tal cual, y lo que era `um_yellow_dark` lo lleva
     * atenuado ([DynamicColor.dim]).
     */
    private fun tintIPod(view: View, accent: Int) {
        AccentTint.icons(view, accent, R.id.btnMenu, R.id.btnPrev, R.id.btnNext, R.id.btnPlayPauseBig)
        view.findViewById<SeekBar>(R.id.ipodProgress).progressTintList =
            ColorStateList.valueOf(accent)

        val strokeWidths = listOf(
            R.id.ipodBody to R.dimen.ipod_stroke_body,
            R.id.topBox to R.dimen.ipod_stroke_display,
            R.id.infoBox to R.dimen.ipod_stroke_info_box,
            R.id.clickWheel to R.dimen.ipod_stroke_wheel_ring,
            R.id.wheelCenter to R.dimen.ipod_stroke_wheel_center
        )
        for ((id, widthDimen) in strokeWidths) {
            AccentTint.stroke(view, id, accent, widthDimen)
        }

        // El cuerpo y el centro de la rueda son los dos sitios de la app pintados con
        // `@color/um_yellow_dark`, así que son los dos que llevan el acento atenuado. Antes se
        // quedaban con el amarillo oscuro fijo del XML aunque sonara una canción azul, porque aquí
        // solo se repintaban los contornos.
        val dimAccent = DynamicColor.dim(accent)
        AccentTint.fill(view, R.id.ipodBody, dimAccent)
        AccentTint.fill(view, R.id.wheelCenter, dimAccent)

        // El icono de altavoz de la fila "sonando ahora" también sigue el acento, tanto en la cola
        // real (IPodQueueAdapter) como en la lista de una playlist en modo navegación
        // (PlaylistQueueAdapter): solo uno de los dos es el adaptador activo en cada momento.
        when (val adapter = view.findViewById<RecyclerView>(R.id.queueList).adapter) {
            is IPodQueueAdapter -> adapter.setAccent(accent)
            is PlaylistQueueAdapter -> adapter.setAccent(accent)
        }
    }

    /** Construye la línea "Artista · Álbum · Año" omitiendo el año si no existe. */
    private fun metaLine(song: Song): String {
        val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
        val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
        return listOfNotNull(artist, album, song.year?.toString()).joinToString(" · ")
    }

    /**
     * Texto con el que se abre el buscador de YouTube: "Título Artista". No se mete el álbum ni el
     * año porque estrechan la búsqueda más de la cuenta y muchos videoclips no los llevan en su
     * título. Si la canción es un remix, se busca por su propio título, no por el original: el
     * videoclip que se quiere ver es el del remix.
     */
    private fun searchQuery(song: Song): String {
        val artist = song.artists.firstOrNull()?.name
            ?.takeIf { it != MusicScanner.UNKNOWN_ARTIST }
        return listOfNotNull(song.title, artist).joinToString(" ")
    }

    override fun onDestroyView() {
        // El audio local nunca se pausó por el modo vídeo, así que aquí no hay nada que devolver.
        // El reproductor de YouTube lleva un WebView dentro: hay que liberarlo a mano o seguiría
        // vivo en segundo plano gastando batería y datos (ver [VideoScreenController.release]).
        videoController?.release()
        videoController = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PLAYLIST = "playlist"
        private const val ARG_SUPPRESS_ENTER_ANIM = "suppress_enter_anim"
        private const val TAG_VIDEO_PICKER = "video_picker"

        /** Alto/ancho de un vídeo 16:9, para que la pantalla del iPod se ajuste a él en modo vídeo. */
        private const val VIDEO_ASPECT_RATIO = 9f / 16f

        /** Duración de la animación que completa la apertura al soltar por encima del umbral. */
        private const val OPEN_DRAG_ANIM_MS = 180L

        /** Duración de la animación de cierre (ver [animateClose]). */
        private const val CLOSE_ANIM_MS = 200L

        /**
         * Abre el iPod mostrando la playlist [playlistName] para elegir qué sonará. Si esa playlist
         * ya es la que suena, se abre en modo normal en su lugar (ver [browsing]).
         */
        fun newInstance(playlistName: String) = IPodNanoDialogFragment().apply {
            arguments = bundleOf(ARG_PLAYLIST to playlistName)
        }

        /**
         * Instancia para abrir arrastrando el mini-reproductor hacia arriba (ver
         * [MainActivity.setupMiniPlayerDrag]): sin animación de ventana propia y con [followOpenDrag]
         * disponible para trasladarla en vivo desde el primer momento.
         */
        fun forDrag() = IPodNanoDialogFragment().apply {
            arguments = bundleOf(ARG_SUPPRESS_ENTER_ANIM to true)
        }
    }
}
