package com.untarlamanteca.ultimusic.ui.player

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
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
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.data.playlist.PlaylistRepository
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song
import com.untarlamanteca.ultimusic.ui.PlayerViewModel
import com.untarlamanteca.ultimusic.ui.playlists.PlaylistsViewModel
import com.untarlamanteca.ultimusic.util.CoverArt
import com.untarlamanteca.ultimusic.util.CoverLoader
import com.untarlamanteca.ultimusic.util.TimeFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ventana a pantalla completa con forma de iPod Nano. Sube deslizándose desde abajo (animación del
 * tema del diálogo) y al pulsar la "X" baja y se destruye. Comparte el [PlayerViewModel] de la
 * actividad, así que refleja siempre la misma reproducción que el mini-reproductor.
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
 */
class IPodNanoDialogFragment : DialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()

    /** Nombre de la playlist si estamos en modo navegación; null en modo normal. */
    private val playlistName: String? get() = arguments?.getString(ARG_PLAYLIST)

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

        val loader = CoverLoader.get(requireContext())

        // Adaptador de la cola en reproducción (modo normal).
        val queueAdapter = IPodQueueAdapter { position -> playerViewModel.jumpTo(position) }
        val queueLayoutManager = LinearLayoutManager(requireContext())
        queueList.layoutManager = queueLayoutManager
        queueList.adapter = queueAdapter

        btnClose.setOnClickListener { dismiss() }

        // --- Cableado del modo NORMAL (se sobrescribe si entramos en navegación) ---
        val setupNormalControls = {
            btnMenu.setImageResource(R.drawable.ic_menu)
            // El botón de las 3 rayas alterna la pantalla entre carátula y cola.
            btnMenu.setOnClickListener {
                val showQueue = cover.isVisible
                cover.isVisible = !showQueue
                queueList.isVisible = showQueue
            }
            btnPrev.setOnClickListener { playerViewModel.previous() }
            btnNext.setOnClickListener { playerViewModel.next() }
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
                playerViewModel.seekToFraction(sb.progress / 1000f)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // El título, la carátula y el resto de info reflejan siempre lo que suena de
                    // verdad, esté o no la ventana en modo navegación de una playlist: nunca se debe
                    // fingir que no suena nada si en realidad sí suena algo (aunque sea de otro origen).
                    playerViewModel.currentSong.collect { song ->
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
                    ) { q1, q2, i -> Triple(q1 + q2, i, q1.size) }
                        .collect { (combined, index, queue1Size) ->
                            if (browsing) return@collect
                            queueAdapter.submit(combined, index, queue1Size)
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
                btnMenu.setImageResource(R.drawable.ic_menu)
                btnMenu.setOnClickListener {
                    val showQueue = cover.isVisible
                    cover.isVisible = !showQueue
                    queueList.isVisible = showQueue
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
                btnMenu.setImageResource(R.drawable.ic_shuffle)
                btnMenu.setOnClickListener {
                    startPlaybackFromBrowse(queueList, queueAdapter, cover, btnMenu, setupNormalControls) {
                        playerViewModel.shuffleCollection(it.currentSongs(), name)
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
        queueList.adapter = queueAdapter
        setupNormalControls()
        play(playlistAdapter)
    }

    /**
     * Repinta de [accent] todos los contornos y botones del iPod.
     *
     * Los contornos están definidos en XML como `<shape>` con un `<stroke>` amarillo. En tiempo de
     * ejecución un `<shape>` es un [GradientDrawable], cuyo contorno se puede cambiar con
     * `setStroke`. Hace falta `mutate()` antes: por defecto, todas las vistas que usan el mismo
     * drawable comparten su estado interno, así que sin mutarlo cambiaríamos el color de golpe a
     * todos los sitios donde se use ese archivo (y de forma persistente durante toda la ejecución).
     * `mutate()` le da a esta vista una copia propia.
     */
    private fun tintIPod(view: View, accent: Int) {
        fun stroke(id: Int, widthDp: Float) {
            val drawable = view.findViewById<View>(id)?.background?.mutate() as? GradientDrawable
            drawable?.setStroke(dpToPx(widthDp), accent)
        }
        stroke(R.id.ipodBody, 2f)
        stroke(R.id.topBox, 2f)
        stroke(R.id.infoBox, 1f)
        stroke(R.id.clickWheel, 2f)
        stroke(R.id.wheelCenter, 2f)

        val tint = ColorStateList.valueOf(accent)
        for (id in listOf(R.id.btnMenu, R.id.btnPrev, R.id.btnNext, R.id.btnPlayPauseBig)) {
            ImageViewCompat.setImageTintList(view.findViewById(id), tint)
        }
        view.findViewById<SeekBar>(R.id.ipodProgress).progressTintList = tint
    }

    /** Los grosores de contorno del XML están en dp; [GradientDrawable.setStroke] los quiere en px. */
    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
    ).toInt()

    /** Construye la línea "Artista · Álbum · Año" omitiendo el año si no existe. */
    private fun metaLine(song: Song): String {
        val artist = song.artists.firstOrNull()?.name ?: MusicScanner.UNKNOWN_ARTIST
        val album = song.albums.firstOrNull()?.title ?: MusicScanner.UNKNOWN_ALBUM
        return listOfNotNull(artist, album, song.year?.toString()).joinToString(" · ")
    }

    companion object {
        private const val ARG_PLAYLIST = "playlist"

        /**
         * Abre el iPod mostrando la playlist [playlistName] para elegir qué sonará. Si esa playlist
         * ya es la que suena, se abre en modo normal en su lugar (ver [browsing]).
         */
        fun newInstance(playlistName: String) = IPodNanoDialogFragment().apply {
            arguments = bundleOf(ARG_PLAYLIST to playlistName)
        }
    }
}
