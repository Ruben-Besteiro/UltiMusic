package com.untar.ultimusic.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.playlist.PlaylistRepository
import com.untar.ultimusic.data.scan.MusicScanner
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.SquareFrameLayout
import com.untar.ultimusic.ui.common.TouchBlockerFrameLayout
import com.untar.ultimusic.ui.common.ValueRuler
import com.untar.ultimusic.ui.common.attachVerticalDrag
import com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment
import com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment
import com.untar.ultimusic.ui.library.DetailDialogFragment
import com.untar.ultimusic.ui.library.PersonKind
import com.untar.ultimusic.ui.playlists.AddToPlaylistDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import com.untar.ultimusic.util.DynamicColor
import com.untar.ultimusic.util.LrcParser
import com.untar.ultimusic.util.LyricLine
import com.untar.ultimusic.util.TimeFormat
import com.untar.ultimusic.util.YouTubeUrl
import com.untar.ultimusic.util.joinNonBlank
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ventana a pantalla completa con forma de iPod. Se abre de dos formas: tocando la carátula del
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
 * Siempre muestra lo que suena de verdad y su cola, todo leído de [PlayerViewModel]: no tiene un
 * modo de navegación propio para elegir qué sonará. Ese papel lo hace, antes de llegar aquí, la
 * ficha de una lista o un género (ver
 * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment]), que ya deja sonando la
 * colección elegida; esta ventana se limita a reflejarlo.
 *
 * Aparte de eso, la pantalla tiene un **modo vídeo** que se activa con el botón de la derecha de la
 * cabecera (ver [videoMode]).
 */
class IPodDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    /**
     * True si esta instancia se abrió arrastrando el mini-reproductor (ver [forDrag]). En ese caso
     * la ventana no debe llevar NINGUNA animación propia: quien la abre así ya la traslada a mano en
     * vivo con el dedo (ver [followOpenDrag]), y una animación de ventana encima duplicaría el
     * movimiento.
     */
    private val suppressEnterAnim: Boolean get() = arguments?.getBoolean(ARG_SUPPRESS_ENTER_ANIM) ?: false

    /** Evita disparar [animateClose] dos veces (p.ej. arrastre + botón atrás casi a la vez). */
    private var closing = false

    /** Ayudante de arrastre para reordenar la fila que se suelte de la cola real (ver
     * [attachReorderTouchHelper]). */
    private var itemTouchHelper: ItemTouchHelper? = null

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
    ): View = inflater.inflate(R.layout.dialog_ipod, container, false)

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
        // Recorta topBox a esquinas redondeadas (mismo radio que dibuja el marco de
        // ipod_display) para que el vídeo no asome esquinas rectas por fuera del marco: a
        // diferencia de `cover` (que se redondea sola, ver más abajo), el YouTubePlayerView no
        // tiene manera propia de recortarse en forma redondeada. Antes se intentó recortar solo él
        // (ver VideoScreenController), pero su wrap_content no siempre coincidía con el tamaño real
        // de topBox y dejaba el redondeado descuadrado (esquinas de arriba bien, las de abajo no);
        // recortar el contenedor entero vale igual en modo carátula (cuadrado) que en modo vídeo
        // (16:9, ver animateAspectRatio más abajo) sin recalcular nada al cambiar de forma.
        //
        // OJO: este recorte del contenedor NO basta para redondear un RecyclerView (ver el propio
        // recorte de `queueList` más abajo) — cada vista con contenido rectangular visible necesita
        // el suyo; topBox solo cubre lo que no tiene ninguna otra forma de recortarse.
        val cornerRadius = view.resources.getDimension(R.dimen.ipod_display_corner_radius)
        topBox.clipToOutline = true
        topBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadius)
            }
        }
        val cover = view.findViewById<ShapeableImageView>(R.id.cover)
        val queueList = view.findViewById<RecyclerView>(R.id.queueList)
        // El recorte de topBox de arriba no basta para redondearlo a él: `cover` se ve bien porque
        // se redondea SOLA con su propio `shapeAppearanceOverlay` (una ShapeableImageView recorta su
        // propio contenido, no depende del padre), pero un RecyclerView no tiene ese mecanismo — y
        // hasta que llevó fondo propio (el velo semitransparente sobre la carátula) no se notaba,
        // porque no había nada que asomara por las esquinas. Por eso necesita su PROPIO recorte, con
        // el mismo radio que topBox.
        queueList.clipToOutline = true
        queueList.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadius)
            }
        }
        val tvPosition = view.findViewById<TextView>(R.id.tvPosition)
        val tvDuration = view.findViewById<TextView>(R.id.tvDuration)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
        val lyricsBox = view.findViewById<RecyclerView>(R.id.lyricsBox)
        val progressBar = view.findViewById<SeekBar>(R.id.ipodProgress)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val btnMenu = view.findViewById<ImageButton>(R.id.btnMenu)
        val btnPrev = view.findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = view.findViewById<ImageButton>(R.id.btnNext)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPauseBig)
        val btnSongMenu = view.findViewById<ImageButton>(R.id.btnSongMenu)
        val btnVideo = view.findViewById<ImageButton>(R.id.btnVideo)
        val youtubePlayer = view.findViewById<YouTubePlayerView>(R.id.youtubePlayer)
        // Envuelve a youtubePlayer y le traga los toques (ver TouchBlockerFrameLayout); por eso
        // tiene que estar tan oculto como él fuera de modo vídeo, o se queda tapando a `cover` y
        // `queueList` (que están debajo en topBox) y sus filas dejan de responder al tacto. El
        // toque simple sobre el vídeo se reconoce igualmente (ver onTap más abajo, junto a
        // btnPlayPause): así el vídeo es interactuable sin dejar pasar el toque al reproductor.
        val videoContainer = view.findViewById<TouchBlockerFrameLayout>(R.id.videoContainer)
        val videoLoadingSpinner = view.findViewById<CircularProgressIndicator>(R.id.videoLoadingSpinner)
        val videoOffsetBox = view.findViewById<View>(R.id.videoOffsetBox)
        val videoOffsetRuler = view.findViewById<ValueRuler>(R.id.ipodOffsetRuler)
        val videoOffsetValue = view.findViewById<EditText>(R.id.ipodOffsetValue)

        val loader = CoverLoader.get(requireContext())

        // Adaptador de la cola en reproducción. Se puede reordenar arrastrando (ver
        // [attachReorderTouchHelper]).
        val queueAdapter = IPodQueueAdapter(
            onItemClick = { position -> playerViewModel.jumpTo(position) },
            onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) },
            onReordered = { newQueue -> playerViewModel.reorderQueue(newQueue) }
        )
        queueAdapter.setAccent(playerViewModel.accentColor.value)
        val queueLayoutManager = LinearLayoutManager(requireContext())
        queueList.layoutManager = queueLayoutManager
        queueList.adapter = queueAdapter
        attachReorderTouchHelper(
            queueList,
            onMove = { from, to -> queueAdapter.moveItem(from, to) },
            onDropped = { queueAdapter.commitReorder() }
        )

        // Letra (ver IPodLyricsAdapter). lyricLines guarda las marcas de tiempo (vacío si la letra
        // no está sincronizada); es lo que usa el collect de playerViewModel.progress más abajo
        // para saber qué línea resaltar y centrar en cada momento.
        val lyricsAdapter = IPodLyricsAdapter()
        lyricsAdapter.setAccent(playerViewModel.accentColor.value)
        val lyricsLayoutManager = LinearLayoutManager(requireContext())
        lyricsBox.layoutManager = lyricsLayoutManager
        lyricsBox.adapter = lyricsAdapter
        var lyricLines: List<LyricLine> = emptyList()
        // Desplazamiento letra/audio de la canción que suena (ver Song.lyricsOffsetMs, ajustado en
        // el editor de metadatos); se suma a la posición real antes de buscar la línea actual, igual
        // que videoController.sync suma su propio offset a la posición del vídeo.
        var lyricsOffsetMs = 0L
        // Desplazamiento de vídeo ya aplicado al controlador activo (ver Song.videoOffsetMs). Solo
        // sirve para detectar, en el collect de currentSong de más abajo, que el editor de metadatos
        // acaba de guardar un valor distinto: no se toca mientras se arrastra el slider (eso no llega
        // aquí hasta guardar, ver MetadataEditorDialogFragment.setupOffsetControls), solo al guardar.
        var appliedVideoOffsetMs = 0L

        // Sin letra guardada, tocar el recuadro abre el mismo buscador de lrclib.net que el campo
        // "Letra" del editor de metadatos ([MetadataEditorDialogFragment.openLyricsPicker], la
        // ÚNICA forma de ponerle letra a una canción): así se puede añadir/editar desde aquí
        // también, sin tener que abrir el editor completo. El resultado se guarda directo en Room
        // (ver el listener de [LyricsSuggestionsDialogFragment.RESULT_KEY] más abajo), como el
        // enlace del vídeo.
        //
        // Un `setOnClickListener` normal NO sirve aquí: `RecyclerView.onTouchEvent` gestiona el
        // toque entero para el scroll/fling y nunca llama a `performClick()`, así que el click
        // nunca llegaría a dispararse. Hace falta interceptar el toque a mano con un
        // `OnItemTouchListener` + `GestureDetector` (la vía que recomienda la propia documentación
        // de RecyclerView para detectar un toque simple sin robarle el gesto al scroll).
        val lyricsTapDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val song = playerViewModel.currentSong.value ?: return false
                    if (!song.lyrics.isNullOrBlank()) return false
                    val artist = song.artists.firstOrNull()?.name
                        ?.takeIf { it != MusicScanner.UNKNOWN_ARTIST }
                        .orEmpty()
                    LyricsSuggestionsDialogFragment.newInstance(song.title, artist, song.duration)
                        .show(childFragmentManager, TAG_LYRICS_SUGGESTIONS)
                    return true
                }
            }
        )
        lyricsBox.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean =
                lyricsTapDetector.onTouchEvent(e)
        })

        /**
         * Decide si [queueAdapter] pinta el divisor de "final de cola" en su última fila: solo si la
         * cola es corta y no llega a llenar la pantalla (si la llena, no hay hueco en blanco debajo
         * que desambiguar, y el divisor quedaría fuera de la vista hasta hacer scroll). Como depende
         * de una medida de layout, va en un `post{}` **anidado** al que centra la fila actual: hay que
         * esperar a que ESE scroll también se asiente antes de preguntar si algo es o no arrastrable.
         * Solo se aplica a la cola real, nunca a la lista de una lista en modo navegación.
         */
        val refreshEndDivider = {
            if (queueList.adapter === queueAdapter) {
                queueList.post {
                    val fitsWithoutScrolling =
                        !queueList.canScrollVertically(1) && !queueList.canScrollVertically(-1)
                    queueAdapter.setShowEndDivider(fitsWithoutScrolling)
                }
            }
        }

        btnClose.setOnClickListener { animateClose(root) }

        // Botón de 3 puntos: mismo menú que el de cada fila en la pestaña Canciones (encolar,
        // añadir a lista, editar metadatos, borrar), pero aplicado a la canción que suena de
        // verdad ([PlayerViewModel.currentSong]), esté o no la ventana en modo navegación: ahí es
        // donde vive esta canción en pantalla (carátula, título, letra...), así que es la que este
        // botón debe poder editar/encolar/borrar. Sin canción sonando no hay nada que ofrecer.
        btnSongMenu.setOnClickListener { anchor ->
            val song = playerViewModel.currentSong.value ?: return@setOnClickListener
            showSongMenu(anchor, song)
        }

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
            videoController?.pause()

            youtubePlayer.isVisible = false
            videoContainer.isVisible = false
            videoLoadingSpinner.isVisible = false
            videoOffsetBox.isVisible = false
            // La carátula vuelve a verse siempre (en modo vídeo era lo único que se ocultaba del
            // todo, ver [startVideo]); lo que decide si además se ve la cola encima, semitransparente,
            // es queueWasVisible.
            cover.isVisible = true
            queueList.isVisible = queueWasVisible
            refreshEndDivider()
            updateInfoBox(queueList, tvTitle, tvMeta)
            btnVideo.setImageResource(R.drawable.ic_video)
            // Vuelve a ser cuadrada, como fuera del modo vídeo.
            topBox.animateAspectRatio(1f)
        }

        // Dueño del reproductor de YouTube. Es mudo: la barra de progreso y el botón de play/pausa
        // los llevan siempre los colectores del audio local (más abajo), que además lo mantienen
        // sincronizado con [VideoScreenController.sync]. Si el vídeo falla, se sale del modo vídeo
        // solo: el audio local sigue sonando sin problema.
        val controller = VideoScreenController(
            view = youtubePlayer,
            onPlaybackError = { if (videoMode) exitVideo() },
            onLoadingChanged = { loading -> videoLoadingSpinner.isVisible = loading }
        )
        videoController = controller

        // Regla (+ cifra editable a su derecha) de desplazamiento del modo vídeo. Viven fuera de
        // startVideo/exitVideo porque solo hace falta engancharlas una vez; esas dos funciones solo
        // las muestran/ocultan y les ponen el valor de la canción (ver [showIpodOffset]).
        //
        // [updatingIpodOffsetUi] evita que sincronizar la regla y la cifra entre sí se confunda con
        // un cambio del usuario y entre en bucle; es el mismo patrón que
        // MetadataEditorDialogFragment.updatingOffsetUi, del que esta caja es una versión reducida
        // (sin guardado explícito: aquí no hay botón "Guardar", así que se aplica y se guarda solo).
        var updatingIpodOffsetUi = false

        /** Refleja [ms] en la regla y en la cifra a la vez, cuadrando la regla a su muesca de
         * [MetadataEditorDialogFragment.OFFSET_STEP_MS] más cercana. Calco de
         * MetadataEditorDialogFragment.showOffset. */
        fun showIpodOffset(ms: Int) {
            updatingIpodOffsetUi = true
            val notch = Math.round(ms / MetadataEditorDialogFragment.OFFSET_STEP_MS.toFloat())
            if (videoOffsetRuler.currentValue != notch) videoOffsetRuler.setValue(notch, notify = false)
            if (videoOffsetValue.text.toString() != ms.toString()) {
                videoOffsetValue.setText(ms.toString())
                videoOffsetValue.setSelection(videoOffsetValue.text.length)
            }
            updatingIpodOffsetUi = false
        }

        /** Lo escrito en la cifra, dentro del rango permitido; 0 si está vacío o a medio escribir.
         * Calco de MetadataEditorDialogFragment.clampedOffsetOrZero. */
        fun clampedIpodOffsetOrZero(): Int =
            videoOffsetValue.text.toString().toIntOrNull()
                ?.coerceIn(-MetadataEditorDialogFragment.OFFSET_MAX_MS, MetadataEditorDialogFragment.OFFSET_MAX_MS)
                ?: 0

        /** Aplica [offsetMs] al vídeo en directo y lo guarda en la canción (ver
         * LibraryRepository.setVideoOffsetMs), tanto si viene de soltar la regla como de confirmar
         * la cifra escrita a mano. */
        fun applyIpodOffset(offsetMs: Long) {
            controller.setOffset(offsetMs, playerViewModel.currentPositionMs())
            val song = playerViewModel.currentSong.value ?: return
            viewLifecycleOwner.lifecycleScope.launch {
                LibraryRepository.get(requireContext()).setVideoOffsetMs(song, offsetMs)
                playerViewModel.refreshSong(song.copy(videoOffsetMs = offsetMs))
            }
        }

        videoOffsetRuler.minValue = -MetadataEditorDialogFragment.OFFSET_MAX_MS / MetadataEditorDialogFragment.OFFSET_STEP_MS
        videoOffsetRuler.maxValue = MetadataEditorDialogFragment.OFFSET_MAX_MS / MetadataEditorDialogFragment.OFFSET_STEP_MS
        videoOffsetRuler.onValueChanged = { notch ->
            if (!updatingIpodOffsetUi) {
                val ms = notch * MetadataEditorDialogFragment.OFFSET_STEP_MS
                showIpodOffset(ms)
                // En caliente, en cada muesca arrastrada: se nota al instante en el vídeo (ver
                // VideoScreenController.setOffset), sin esperar a soltar el dedo ni al siguiente
                // tick de sync(). El guardado en Room, en cambio, espera a [onReleased].
                controller.setOffset(ms.toLong(), playerViewModel.currentPositionMs())
            }
        }
        videoOffsetRuler.onReleased = {
            // Solo se guarda al soltar (ver ValueRuler.onReleased): arrastrar no debe escribir en
            // Room en cada píxel, solo el valor definitivo.
            applyIpodOffset((videoOffsetRuler.currentValue * MetadataEditorDialogFragment.OFFSET_STEP_MS).toLong())
        }

        // La caja admite cualquier milisegundo exacto escrito a mano. Mientras se escribe, se
        // refleja en la regla (cuadrando a la muesca de 100 ms más cercana) Y se previsualiza en el
        // vídeo al instante, igual que arrastrar la regla (setValue va con notify=false para no
        // duplicar esa previsualización a través de onValueChanged). Lo que SÍ espera a confirmar
        // (ver más abajo) es el guardado en Room: aplicarlo ya en cada tecla es barato (un seekTo
        // más), pero escribir en la base de datos en cada pulsación sería tan ruidoso como guardar
        // en cada píxel arrastrado.
        videoOffsetValue.doAfterTextChanged { text ->
            if (updatingIpodOffsetUi) return@doAfterTextChanged
            val typed = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            updatingIpodOffsetUi = true
            val notch = Math.round(typed / MetadataEditorDialogFragment.OFFSET_STEP_MS.toFloat())
            if (videoOffsetRuler.currentValue != notch) videoOffsetRuler.setValue(notch, notify = false)
            updatingIpodOffsetUi = false
            val clamped = typed.coerceIn(-MetadataEditorDialogFragment.OFFSET_MAX_MS, MetadataEditorDialogFragment.OFFSET_MAX_MS)
            controller.setOffset(clamped.toLong(), playerViewModel.currentPositionMs())
        }
        // Al pulsar "hecho" la caja se cuadra dentro del rango (por si quedó a medio escribir un
        // "-" o se pasó del tope), se aplica de una vez y se cierra el teclado, igual que
        // MetadataEditorDialogFragment.setupOffsetControls.
        videoOffsetValue.setOnEditorActionListener { _, _, _ ->
            val clamped = clampedIpodOffsetOrZero()
            showIpodOffset(clamped)
            applyIpodOffset(clamped.toLong())
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(videoOffsetValue.windowToken, 0)
            videoOffsetValue.clearFocus()
            true
        }
        videoOffsetValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val clamped = clampedIpodOffsetOrZero()
                showIpodOffset(clamped)
                applyIpodOffset(clamped.toLong())
            }
        }

        /**
         * Muestra el videoclip (mudo) arrancando justo donde va el audio local, sin tocarlo. Por eso
         * no hay hueco de silencio: el audio nunca se pausa para esperar al vídeo.
         */
        val startVideo = { videoId: String, song: Song ->
            videoMode = true
            videoSongId = song.id
            // Se recuerda si se estaba viendo la cola para devolver la pantalla a como estaba.
            queueWasVisible = queueList.isVisible
            cover.isVisible = false
            queueList.isVisible = false
            updateInfoBox(queueList, tvTitle, tvMeta)

            val playingNow = playerViewModel.isPlaying.value
            youtubePlayer.isVisible = true
            videoContainer.isVisible = true
            videoOffsetBox.isVisible = true
            showIpodOffset(song.videoOffsetMs.toInt())

            btnVideo.setImageResource(R.drawable.ic_music_note)
            // La pantalla del iPod pasa de cuadrada a 16:9 para no dejar bandas vacías arriba y
            // abajo del videoclip.
            topBox.animateAspectRatio(VIDEO_ASPECT_RATIO)
            controller.load(videoId, playerViewModel.currentPositionMs(), playingNow, song.videoOffsetMs)
            appliedVideoOffsetMs = song.videoOffsetMs
        }

        // --- Cableado del modo NORMAL (se sobrescribe si entramos en navegación) ---
        val setupNormalControls = {
            // El botón de las 3 rayas muestra/oculta la cola por encima de la carátula (que se ve
            // siempre, incluso con la cola encima: ver el velo semitransparente de queueList en el
            // XML). Se comporta exactamente igual en modo vídeo: ahí muestra/oculta la misma cola,
            // ahora por encima del videoclip (ver el orden de queueList y videoContainer en el XML).
            btnMenu.setImageResource(R.drawable.ic_menu)
            btnMenu.setOnClickListener {
                queueList.isVisible = !queueList.isVisible
                refreshEndDivider()
                updateInfoBox(queueList, tvTitle, tvMeta)
            }
            btnPrev.setOnClickListener { playerViewModel.skipToPrevious() }
            btnNext.setOnClickListener { playerViewModel.skipToNext() }
            // El play/pausa siempre gobierna el audio local, esté o no visible el vídeo. Sin nada
            // en reproducción (arranque en limpio) lleva el icono de aleatorio (ver el collect de
            // currentSong+isPlaying más abajo) y arranca una canción al azar de la biblioteca que
            // no esté en la lista gris; a partir de ahí vuelve a comportarse como play/pausa normal.
            btnPlayPause.setOnClickListener {
                if (playerViewModel.currentSong.value == null) {
                    playerViewModel.playRandomFromLibrary()
                } else {
                    playerViewModel.togglePlayPause()
                }
            }
        }
        setupNormalControls()

        // Arrastrar/tocar la barra mueve la reproducción; mientras se toca, mostramos el tiempo
        // destino en tvPosition y evitamos que la actualización periódica pise la posición. Esto
        // controla lo que suena de verdad, así que funciona igual en modo navegación: mientras se
        // elige canción de una lista, la barra sigue perteneciendo a la reproducción real.
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
                    val videoUrl = YouTubeUrl.watchUrl(videoId)
                    val thumbnailName = LibraryRepository.get(requireContext()).setVideoUrl(song, videoUrl)
                    playerViewModel.refreshSong(
                        song.copy(videoUrl = videoUrl, videoThumbnailName = thumbnailName)
                    )
                }
                Toast.makeText(
                    requireContext(),
                    TextUtils.expandTemplate(resources.getText(R.string.video_picker_saved), song.title),
                    Toast.LENGTH_SHORT
                ).show()
                startVideo(videoId, song)
            }
        }

        // El buscador de letras devuelve el texto elegido por aquí (ver su listener de arriba en
        // lyricsBox). Se guarda directo en Room, sin formulario abierto de por medio, igual que el
        // enlace del vídeo: al reemitir, currentSong llega ya con la letra nueva y el collect de
        // más abajo la pinta sola.
        childFragmentManager.setFragmentResultListener(
            LyricsSuggestionsDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val lyrics = bundle.getString(LyricsSuggestionsDialogFragment.RESULT_LYRICS)
            val song = playerViewModel.currentSong.value
            if (lyrics != null && song != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    LibraryRepository.get(requireContext()).setLyrics(song, lyrics)
                    playerViewModel.refreshSong(song.copy(lyrics = lyrics))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // El título, la carátula y el resto de info reflejan siempre lo que suena de
                    // verdad, esté o no la ventana en modo navegación de una lista: nunca se debe
                    // fingir que no suena nada si en realidad sí suena algo (aunque sea de otro origen).
                    //
                    // Se combina con coverRevision (ver su comentario en PlaybackService) para que
                    // editar la carátula de la canción que suena la recargue aquí también, aunque el
                    // nombre de archivo se reutilice y currentSong no cambie "de verdad".
                    combine(playerViewModel.currentSong, playerViewModel.coverRevision) { song, _ -> song }
                        .collect { song ->
                        // El vídeo pertenece a UNA canción: si ha cambiado (flechas, fin de la
                        // canción, salto en la cola...), se sale solo del modo vídeo y el sonido
                        // vuelve al archivo local, que es el que sabe seguir la cola. El vídeo de la
                        // siguiente canción puede no estar ni elegido todavía.
                        if (videoMode && song?.id != videoSongId) exitVideo()
                        // El editor de metadatos guarda el nuevo desplazamiento en Room y, si esta es
                        // la canción que suena, PlayerViewModel.refreshSong reemite currentSong con él
                        // ya puesto (ver MetadataEditorDialogFragment). Si el vídeo de esa canción está
                        // en pantalla, se reposiciona al instante con el offset nuevo, en vez de esperar
                        // a salir y volver a entrar en modo vídeo. La comparación con
                        // [appliedVideoOffsetMs] evita reposicionar (con el saltito que eso da) en cada
                        // guardado que no toque el offset, p. ej. al editar solo la letra. La misma
                        // rama recoloca la regla y la cifra propias del iPod (ver [showIpodOffset]
                        // más arriba): así se refleja tanto un cambio hecho en el editor de
                        // metadatos como el que acaban de guardar ellas mismas al soltar/confirmar.
                        if (videoMode && song != null && song.id == videoSongId &&
                            song.videoOffsetMs != appliedVideoOffsetMs
                        ) {
                            appliedVideoOffsetMs = song.videoOffsetMs
                            videoController?.setOffset(song.videoOffsetMs, playerViewModel.currentPositionMs())
                            showIpodOffset(song.videoOffsetMs.toInt())
                        }
                        if (song != null) {
                            cover.load(CoverArt.cover(requireContext(), song), loader) {
                                error(R.drawable.cover_placeholder)
                            }
                        } else {
                            cover.setImageResource(R.drawable.cover_placeholder)
                        }
                        updateInfoBox(queueList, tvTitle, tvMeta)
                        // La letra es la que el usuario haya escrito o elegido en el editor de
                        // metadatos. Si tiene marcas de tiempo LRC (una sugerencia sincronizada de
                        // lrclib.net, ver LrcParser) se pinta línea a línea, resaltando la que toca
                        // cantar ahora (lo hace el collect de progress, más abajo); si no, una única
                        // fila con el texto entero — o el aviso de "sin letra" si no hay ninguna.
                        val rawLyrics = song?.lyrics?.takeIf { it.isNotBlank() }
                        lyricLines = rawLyrics?.let { LrcParser.parse(it) } ?: emptyList()
                        lyricsOffsetMs = song?.lyricsOffsetMs ?: 0L
                        if (lyricLines.isNotEmpty()) {
                            lyricsAdapter.submit(lyricLines.map { it.text }, synced = true)
                        } else if (song != null) {
                            // Solo avisamos de "sin letra" si de verdad suena algo: sin canción (app
                            // recién abierta en frío) el recuadro debe quedarse vacío, no fingir que
                            // hay una canción sin letra.
                            lyricsAdapter.submit(
                                listOf(rawLyrics ?: getString(R.string.ipod_no_lyrics)),
                                synced = false
                            )
                        } else {
                            lyricsAdapter.submit(emptyList(), synced = false)
                        }
                        // Sin nada en reproducción no hay nada que arrastrar: se deshabilita la barra.
                        progressBar.isEnabled = song != null
                    }
                }
                launch {
                    // El botón siempre refleja el audio local, esté o no visible el vídeo. Sin
                    // canción, muestra el icono de aleatorio en vez de play (ver su listener en
                    // [setupNormalControls]).
                    combine(playerViewModel.currentSong, playerViewModel.isPlaying) { song, isPlaying ->
                        song to isPlaying
                    }.collect { (song, isPlaying) ->
                        btnPlayPause.setImageResource(
                            when {
                                song == null -> R.drawable.ic_shuffle
                                isPlaying -> R.drawable.ic_pause
                                else -> R.drawable.ic_play
                            }
                        )
                        // El vídeo (mudo) se pone en el mismo estado, al instante, sin esperar al
                        // siguiente tick de progreso.
                        if (videoMode) {
                            videoController?.sync(playerViewModel.currentPositionMs(), isPlaying)
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
                        // Letra sincronizada: qué línea toca cantar ahora. Con letra sin sincronizar
                        // lyricLines está vacía y aquí no se hace nada (la única fila que hay se
                        // queda tal cual la pintó el collect de currentSong).
                        if (lyricLines.isNotEmpty()) {
                            val index = LrcParser.currentIndex(lyricLines, p.positionMs + lyricsOffsetMs)
                            if (lyricsAdapter.setCurrentLine(index) && index >= 0) {
                                // Mismo truco que centra la fila actual de la cola (queueList más
                                // abajo): pone el TOPE de la fila a media altura del contenedor. Con
                                // filas de una sola línea, tan altas todas más o menos igual, se ve
                                // centrada de verdad.
                                lyricsLayoutManager.scrollToPositionWithOffset(index, lyricsBox.height / 2)
                            }
                        }
                    }
                }
                launch {
                    // Todo lo que en el XML es amarillo (contornos del cuerpo, de la pantalla, del
                    // recuadro de información y de la rueda, más los cuatro botones y la barra de
                    // progreso) pasa a llevar el color de la carátula que suena.
                    playerViewModel.accentColor.collect { accent ->
                        tintIPod(view, accent)
                        lyricsAdapter.setAccent(accent)
                    }
                }
                launch {
                    // La vista de cola muestra la cola tal cual está: historial ya reproducido,
                    // actual y lo encolado a mano. coverRevision entra por lo mismo que en el
                    // collect de currentSong de más arriba: sin ella, editar la carátula de una
                    // canción de la cola no se reflejaría aquí si el nombre de archivo se reutiliza.
                    combine(
                        playerViewModel.queue,
                        playerViewModel.currentIndex,
                        playerViewModel.coverRevision
                    ) { q, i, _ -> q to i }
                        .collect { (queue, index) ->
                            queueAdapter.submit(queue, index)
                            // El resumen de la caja de info ("Quedan N canciones" + duración) cuenta
                            // lo que hay en la cola, así que hay que rehacerlo también cuando cambia
                            // la cola sin cambiar la canción: encolar algo a mano con la cola a la
                            // vista, por ejemplo.
                            updateInfoBox(queueList, tvTitle, tvMeta)
                            // Centrar la fila actual (la altura ya está disponible en post{}).
                            queueList.post {
                                queueLayoutManager.scrollToPositionWithOffset(
                                    index, queueList.height / 2
                                )
                                refreshEndDivider()
                            }
                        }
                }
            }
        }

    }

    /**
     * Engancha a [queueList] un arrastre solo vertical, iniciado desde el manejador de cada fila (no
     * por pulsación larga): [onMove] mueve en memoria mientras se arrastra, y [onDropped] persiste el
     * orden final al soltar.
     *
     * Desengancha primero cualquier [itemTouchHelper] anterior, por si esta ventana ya llevaba uno
     * enganchado de una llamada previa.
     */
    private fun attachReorderTouchHelper(
        queueList: RecyclerView,
        onMove: (from: Int, to: Int) -> Unit,
        onDropped: () -> Unit
    ) {
        itemTouchHelper?.attachToRecyclerView(null)
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                onMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                onDropped()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(queueList) }
    }

    /**
     * Aplica el color [accent] a los botones, la barra de progreso, los contornos del iPod (cuerpo,
     * pantalla, recuadro de info, fila de botones, círculos de cada botón y recuadro de la letra),
     * el relleno del cuerpo, y el icono de altavoz de la fila "sonando ahora" de la cola.
     *
     * Es el sitio donde se ve mejor la regla de los dos amarillos (ver [AccentTint]): todo lo que en
     * el XML era `um_yellow` lleva el acento tal cual, y lo que era `um_yellow_dark` lo lleva
     * atenuado ([DynamicColor.dim]).
     */
    private fun tintIPod(view: View, accent: Int) {
        AccentTint.icons(
            view, accent,
            R.id.btnMenu, R.id.btnPrev, R.id.btnNext, R.id.btnPlayPauseBig, R.id.btnSongMenu
        )
        view.findViewById<SeekBar>(R.id.ipodProgress).progressTintList =
            ColorStateList.valueOf(accent)
        // ValueRuler no es un GradientDrawable de fondo (ver AccentTint.stroke): su color propio se
        // fija directo, igual que offsetRuler en el editor de metadatos.
        view.findViewById<ValueRuler>(R.id.ipodOffsetRuler).accentColor = accent

        val strokeWidths = listOf(
            R.id.ipodBody to R.dimen.ipod_stroke_body,
            R.id.topBox to R.dimen.ipod_stroke_display,
            R.id.infoBox to R.dimen.ipod_stroke_info_box,
            R.id.buttonRow to R.dimen.ipod_stroke_info_box,
            R.id.lyricsBox to R.dimen.ipod_stroke_info_box,
            R.id.videoOffsetBox to R.dimen.ipod_stroke_info_box,
            R.id.btnMenu to R.dimen.ipod_stroke_button_ring,
            R.id.btnPrev to R.dimen.ipod_stroke_button_ring,
            R.id.btnPlayPauseBig to R.dimen.ipod_stroke_button_ring,
            R.id.btnSongMenu to R.dimen.ipod_stroke_button_ring,
            R.id.btnNext to R.dimen.ipod_stroke_button_ring
        )
        for ((id, widthDimen) in strokeWidths) {
            AccentTint.stroke(view, id, accent, widthDimen)
        }

        // El cuerpo es el único sitio de la app pintado con `@color/um_yellow_dark`, así que es el
        // único que lleva el acento atenuado. Antes se quedaba con el amarillo oscuro fijo del XML
        // aunque sonara una canción azul, porque aquí solo se repintaban los contornos.
        AccentTint.fill(view, R.id.ipodBody, DynamicColor.dim(accent))

        // El icono de altavoz de la fila "sonando ahora" también sigue el acento.
        (view.findViewById<RecyclerView>(R.id.queueList).adapter as? IPodQueueAdapter)?.setAccent(accent)
    }

    /**
     * Qué decir en [R.id.tvMeta] mientras se ve la cola en vez de la carátula (ver [updateInfoBox]):
     * una cola suelta o una lista admiten seguir añadiendo canciones a mano, así que invitan a
     * hacerlo; el resto (género, álbum, artista, productor, o una colección sin tipo conocido) son
     * fijas y solo avisan de que se han acabado.
     */
    private fun queueEndText(): String {
        if (playerViewModel.queue.value.isEmpty()) return getString(R.string.queue_empty)
        if (playerViewModel.isLooseQueue.value) return getString(R.string.queue_hint_loose)
        return if (playerViewModel.currentCollectionKind.value == CollectionKind.LISTA) {
            getString(R.string.queue_hint_playlist)
        } else {
            getString(R.string.queue_end)
        }
    }

    /** Construye la línea "Artista | Álbum | Año" omitiendo el año si no existe. */
    private fun metaLine(song: Song): String {
        val artist = song.artists.joinToString(", ") { it.name }.ifBlank { MusicScanner.UNKNOWN_ARTIST }
        val album = song.album?.title ?: MusicScanner.UNKNOWN_ALBUM
        return joinNonBlank(artist, album, song.year?.toString())
    }

    /**
     * Actualiza [R.id.infoBox] según si se ve la cola/lista o la carátula.
     *
     * En modo cola/lista la caja resume lo que QUEDA por sonar: [tvTitle] lleva "Quedan N canciones"
     * (sin contar la actual, que ya está sonando) y [tvMeta], debajo, lo que duran todas ellas
     * juntas. Cuando no queda ninguna —la actual es la última— no hay nada que resumir, así que
     * [tvTitle] se oculta del todo (`GONE`, no solo vacío) para que [tvMeta] suba y ocupe su sitio
     * con el aviso de la cola ([queueEndText]).
     *
     * En modo carátula, [tvTitle] vuelve a verse con el título de la canción y [tvMeta] baja a su
     * papel normal de subtítulo. Sin canción (app recién abierta en frío, antes de elegir nada) no
     * hay título ni subtítulo que mostrar, así que se trata igual que la cola vacía: [tvTitle] se
     * oculta y [tvMeta] lleva el aviso, ocupando toda la caja.
     */
    private fun updateInfoBox(
        queueList: RecyclerView,
        tvTitle: TextView,
        tvMeta: TextView
    ) {
        val song = playerViewModel.currentSong.value
        if (queueList.isVisible) {
            // drop() ya devuelve vacío si la actual es la última (o si no hay cola).
            val remaining = playerViewModel.queue.value
                .drop(playerViewModel.currentIndex.value + 1)
            if (remaining.isEmpty()) {
                tvTitle.isVisible = false
                tvMeta.text = queueEndText()
            } else {
                tvTitle.isVisible = true
                tvTitle.text = resources.getQuantityString(
                    R.plurals.queue_remaining, remaining.size, remaining.size
                )
                tvMeta.text = TimeFormat.hhmmss(remaining.sumOf { it.duration })
            }
        } else if (song != null) {
            tvTitle.isVisible = true
            tvTitle.text = song.title
            tvMeta.text = metaLine(song)
        } else {
            tvTitle.isVisible = false
            tvMeta.text = getString(R.string.ipod_nothing_playing)
        }
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

    /**
     * Menú de 3 puntos de [song] (ver [R.id.btnSongMenu]): idéntico al de cada fila de la pestaña
     * Canciones (mismo [R.menu.menu_song_item]), salvo que aquí la canción no es la de una fila
     * pulsada sino la que suena de verdad. "Añadir a la cola" no reordena nada: [song] ya está
     * sonando, así que solo repite el mismo comportamiento que en Canciones (útil para volver a
     * encolarla más adelante en la cola sin buscarla).
     */
    private fun showSongMenu(anchor: View, song: Song) {
        PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.menu_song_item, menu)
            // Solo tiene sentido "ir a" lo que la canción de verdad tenga.
            menu.findItem(R.id.action_go_to_album)?.isVisible = song.album != null
            menu.findItem(R.id.action_go_to_artist)?.isVisible = song.artists.isNotEmpty()
            menu.findItem(R.id.action_go_to_producer)?.isVisible = song.producers.isNotEmpty()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_to_queue -> { playerViewModel.addToQueue(song); true }
                    R.id.action_add_to_playlist -> { showAddToPlaylist(song); true }
                    R.id.action_go_to_album -> {
                        song.album?.let { DetailDialogFragment.showAlbum(requireActivity(), it.id) }
                        true
                    }
                    R.id.action_go_to_artist -> {
                        song.artists.firstOrNull()?.let {
                            DetailDialogFragment.showPerson(requireActivity(), PersonKind.ARTIST, it.id)
                        }
                        true
                    }
                    R.id.action_go_to_producer -> {
                        song.producers.firstOrNull()?.let {
                            DetailDialogFragment.showPerson(requireActivity(), PersonKind.PRODUCER, it.id)
                        }
                        true
                    }
                    R.id.action_edit_metadata -> {
                        if (childFragmentManager.findFragmentByTag(TAG_METADATA_EDITOR) == null) {
                            MetadataEditorDialogFragment.newInstance(song.id)
                                .show(childFragmentManager, TAG_METADATA_EDITOR)
                        }
                        true
                    }
                    R.id.action_delete_song -> { showDeleteSongDialog(song); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Igual que `SongsFragment.showAddToPlaylist`. */
    private fun showAddToPlaylist(song: Song) {
        if (childFragmentManager.findFragmentByTag(AddToPlaylistDialogFragment.TAG) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val filename = File(song.filePath).name
            val repo = PlaylistRepository.get()
            val names = repo.listPlaylistNames()
            val contained = repo.playlistsContainingAll(listOf(filename))
            val checked = BooleanArray(names.size) { names[it] in contained }
            AddToPlaylistDialogFragment.newInstance(listOf(filename), names, checked)
                .show(childFragmentManager, AddToPlaylistDialogFragment.TAG)
        }
    }

    /**
     * Confirmación antes de borrar de verdad el archivo del dispositivo. Igual que
     * `SongsFragment.showDeleteDialog`, pero sin `SongsViewModel` (esta ventana no tiene uno propio):
     * borra directamente con [LibraryRepository] y olvida la canción de todas las listas.
     */
    private fun showDeleteSongDialog(song: Song) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song)
            .setMessage(TextUtils.expandTemplate(resources.getText(R.string.delete_song_confirm), song.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.delete_song) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    LibraryRepository.get(requireContext()).deleteSong(song)
                    PlaylistRepository.get().removeSongFromAll(File(song.filePath).name)
                }
            }
            .show()
        AccentTint.buttons(dialog, playerViewModel.accentColor.value)
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
        private const val ARG_SUPPRESS_ENTER_ANIM = "suppress_enter_anim"
        private const val TAG_VIDEO_PICKER = "video_picker"
        private const val TAG_METADATA_EDITOR = "metadataEditor"
        private const val TAG_LYRICS_SUGGESTIONS = "lyrics_suggestions"

        /** Alto/ancho de un vídeo 16:9, para que la pantalla del iPod se ajuste a él en modo vídeo. */
        private const val VIDEO_ASPECT_RATIO = 9f / 16f

        /** Duración de la animación que completa la apertura al soltar por encima del umbral. */
        private const val OPEN_DRAG_ANIM_MS = 180L

        /** Duración de la animación de cierre (ver [animateClose]). */
        private const val CLOSE_ANIM_MS = 200L

        /**
         * Instancia para abrir arrastrando el mini-reproductor hacia arriba (ver
         * [MainActivity.setupMiniPlayerDrag]): sin animación de ventana propia y con [followOpenDrag]
         * disponible para trasladarla en vivo desde el primer momento.
         */
        fun forDrag() = IPodDialogFragment().apply {
            arguments = bundleOf(ARG_SUPPRESS_ENTER_ANIM to true)
        }
    }
}
