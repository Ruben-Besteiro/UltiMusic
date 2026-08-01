package com.untar.ultimusic.ui.player

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import com.untar.ultimusic.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlin.math.abs

/**
 * Envuelve el [YouTubePlayerView] para que el iPod no tenga que saber nada de la librería de
 * YouTube: le pide "carga este vídeo desde este milisegundo" y luego lo mantiene sincronizado con
 * lo que va marcando el audio local. Existe como clase aparte porque `IPodNanoDialogFragment` ya es
 * largo y porque así toda la conversación con la librería queda en un solo sitio.
 *
 * **El vídeo nunca suena.** El audio de verdad es siempre el archivo local (ExoPlayer, en
 * `PlayerViewModel`); este vídeo es una capa puramente visual, silenciada con [YouTubePlayer.mute],
 * que se limita a seguir la posición y el play/pausa del audio local (ver [sync]). Así no hay que
 * traspasar el sonido de uno a otro al entrar o salir del modo vídeo: el audio local no se pausa
 * nunca, y por tanto no hay hueco de silencio ni nada que reconciliar al volver de segundo plano.
 *
 * **Inicialización perezosa.** En el XML el reproductor lleva `enableAutomaticInitialization="false"`,
 * lo que desactiva el arranque automático de la librería. Sin eso, el `WebView` interno se prepararía
 * y descargaría el reproductor de YouTube nada más abrir la ventana del iPod, aunque el usuario nunca
 * llegara a pulsar el botón de vídeo: gastaría datos para nada. Así solo se inicializa la primera vez
 * que hace falta de verdad (ver [load]).
 *
 * **Todo pasa por [onReady].** La librería no está lista para recibir órdenes hasta que su `WebView`
 * ha cargado el reproductor, y eso tarda. Por eso [load] guarda el vídeo en [pending] si aún no ha
 * llegado ese momento, y lo lanza en cuanto llega.
 *
 * Ojo: al desactivar la inicialización automática, la librería tampoco libera sola sus recursos, así
 * que quien use esta clase DEBE llamar a [release] al destruir la vista.
 */
class VideoScreenController(
    private val view: YouTubePlayerView,
    /** Avisa de que el vídeo no se puede reproducir (por ejemplo, embebido desactivado). */
    private val onPlaybackError: () -> Unit
) {

    private var player: YouTubePlayer? = null

    init {
        // El vídeo lo pinta, por dentro, un WebView; y un WebView con vídeo suele promocionar el
        // fotograma a una capa acelerada por hardware que Android compone directamente por fuera
        // del árbol de vistas (como una SurfaceView). Eso lo deja SIEMPRE por encima de todo lo
        // demás en la ventana, sin importar el orden en el XML: por eso tapaba la barra de
        // progreso, la caja de info, la rueda... Forzándolo a capa de software vuelve a pintarse
        // como una vista normal y respeta el Z-order. El vídeo es pequeño y mudo, así que el coste
        // de rendimiento de renderizarlo por software es imperceptible.
        // DEBUG TEMPORAL: probando si esto es lo que deja el vídeo en negro.
        // findWebView(view)?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        // El vídeo es un rectángulo de esquinas rectas, pero vive dentro del recuadro redondeado
        // de la pantalla del iPod (mismo radio que usa la carátula, ver ipod_display_corner_radius):
        // sin recortarlo, esas esquinas asoman por fuera del marco y tapan el cuerpo del iPod.
        val radius = view.resources.getDimension(R.dimen.ipod_display_corner_radius)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
    }

    /** True en cuanto se ha pedido la inicialización, para no pedirla dos veces. */
    private var initializing = false

    /** Vídeo pedido antes de que el reproductor estuviera listo (ver [load]). */
    private var pending: Request? = null

    /** Lo que hace falta para arrancar un vídeo, para poder guardarlo si aún no se puede. */
    private data class Request(val videoId: String, val startMs: Long, val isPlaying: Boolean)

    /** Última posición y estado conocidos del vídeo, para no repetir órdenes en [sync]. */
    private var lastKnownPositionMs = 0L
    private var lastKnownPlaying = false

    /**
     * True mientras se espera a que un vídeo recién cargado empiece de verdad a reproducirse para
     * pausarlo (ver [start] y [onStateChange]). Pausar en el mismo instante de pedir la carga deja
     * la pantalla en negro: YouTube todavía no tiene ni un fotograma que enseñar. Esperando a que
     * llegue el estado PLAYING antes de pausar, el WebView ya tiene algo pintado y, al pausar,
     * enseña su miniatura habitual con el HUD propio (título, botón de play…) en vez de negro.
     */
    private var pauseOnceStarted = false

    private val listener = object : AbstractYouTubePlayerListener() {

        override fun onReady(youTubePlayer: YouTubePlayer) {
            android.util.Log.d("UMVideoDebug", "onReady")
            // Antes de nada: mudo. Ni un fotograma debe sonar, porque el audio real ya lo está
            // poniendo el ExoPlayer local.
            youTubePlayer.mute()
            hideCaptions()
            player = youTubePlayer
            // Si mientras se inicializaba ya se pidió un vídeo, se lanza ahora.
            pending?.let { request ->
                pending = null
                youTubePlayer.start(request)
            }
        }

        /** La librería lo llama una vez por segundo mientras el vídeo avanza. */
        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
            lastKnownPositionMs = (second * 1000).toLong()
        }

        override fun onStateChange(
            youTubePlayer: YouTubePlayer,
            state: PlayerConstants.PlayerState
        ) {
            android.util.Log.d("UMVideoDebug", "onStateChange $state")
            lastKnownPlaying = state == PlayerConstants.PlayerState.PLAYING
            // Ver [pauseOnceStarted]: en cuanto el vídeo arranca de verdad (ya hay fotograma), se
            // pausa si el audio local estaba en pausa.
            if (pauseOnceStarted && state == PlayerConstants.PlayerState.PLAYING) {
                pauseOnceStarted = false
                youTubePlayer.pause()
            }
            // Cada vídeo nuevo puede recargar su propio módulo de subtítulos forzados (ver
            // [hideCaptions]), así que se repite en cada cambio de estado y no solo en [onReady].
            hideCaptions()
        }

        override fun onError(
            youTubePlayer: YouTubePlayer,
            error: PlayerConstants.PlayerError
        ) {
            android.util.Log.d("UMVideoDebug", "onError $error")
            onPlaybackError()
        }

        override fun onApiChange(youTubePlayer: YouTubePlayer) {
            android.util.Log.d("UMVideoDebug", "onApiChange")
        }

        override fun onVideoLoadedFraction(youTubePlayer: YouTubePlayer, loadedFraction: Float) {
            android.util.Log.d("UMVideoDebug", "onVideoLoadedFraction $loadedFraction")
        }
    }

    /**
     * Carga [videoId] desde [startMs], que es la posición que lleva el audio local.
     *
     * [isPlaying] tiene que valer lo que esté haciendo el audio local: si la canción está en pausa,
     * el vídeo se ve congelado también. Cambiar de modo no es darle al play.
     */
    fun load(videoId: String, startMs: Long, isPlaying: Boolean) {
        lastKnownPositionMs = startMs
        val request = Request(videoId, startMs, isPlaying)
        val ready = player
        if (ready != null) {
            ready.start(request)
            return
        }
        // Aún no está listo: se apunta y se lanza la inicialización si es la primera vez.
        pending = request
        if (!initializing) {
            initializing = true
            view.initialize(listener, playerOptions())
        }
    }

    /**
     * Ajusta el vídeo para que coincida con [targetPositionMs]/[targetIsPlaying], que son la
     * posición y el estado reales del audio local. Se llama tanto al alternar play/pausa como en
     * cada tick de progreso del audio (unos 500 ms), así que también es lo que autocorrige
     * cualquier deriva acumulada al volver de segundo plano, sin que el usuario tenga que hacer nada.
     */
    fun sync(targetPositionMs: Long, targetIsPlaying: Boolean, driftThresholdMs: Long = 500) {
        val ready = player ?: return
        if (targetIsPlaying != lastKnownPlaying) {
            if (targetIsPlaying) ready.play() else ready.pause()
        }
        if (abs(targetPositionMs - lastKnownPositionMs) > driftThresholdMs) {
            ready.seekTo(targetPositionMs / 1000f)
        }
    }

    /** Salta al instante a [positionMs], sin esperar al siguiente tick de [sync]. */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs / 1000f)
    }

    /**
     * `loadVideo` deja el vídeo cargándose y reproduciéndose (mudo). Si el audio local no estaba
     * sonando, se pausa en cuanto arranque de verdad (ver [pauseOnceStarted]), no en el mismo
     * instante de pedir la carga. La librería trabaja en segundos con decimales, de ahí la división.
     */
    private fun YouTubePlayer.start(request: Request) {
        mute()
        pauseOnceStarted = !request.isPlaying
        loadVideo(request.videoId, request.startMs / 1000f)
    }

    /**
     * Opciones con las que se arranca el reproductor. `controls = 0` **oculta los mandos propios de
     * YouTube**: sin esto, al tocar el vídeo aparece encima su barra de controles (play, progreso,
     * título, compartir…), que estorba porque el iPod ya tiene sus propios mandos y son esos los que
     * gobiernan el vídeo. Es un parámetro documentado del IFrame Player API, así que ocultarlos está
     * permitido; tapar el reproductor con una vista propia para tragarse los toques NO lo estaría.
     *
     * El `Builder` necesita el contexto porque él solo rellena el `origin` con el paquete de la app,
     * que es lo que YouTube exige desde julio de 2025 para dejar reproducir un vídeo embebido. Por
     * eso hay que construir las opciones a partir del `Builder` y no a mano: si se pierde el
     * `origin`, el reproductor vuelve a fallar con el error 152.
     */
    private fun playerOptions(): IFramePlayerOptions =
        IFramePlayerOptions.Builder(view.context).controls(0).build()

    /**
     * Fuerza a apagar los subtítulos llamando a `player.unloadModule('captions')`.
     *
     * `controls(0)` quita los mandos de YouTube, pero no los subtítulos: si el dispositivo tiene
     * activados los subtítulos de accesibilidad de Android (Ajustes > Accesibilidad > Subtítulos),
     * YouTube los respeta y los pinta igualmente, ignorando el `cc_load_policy = 0` que ya pone por
     * defecto la librería. Sin controles visibles no hay botón "CC" con el que el usuario pueda
     * quitarlos a mano, así que hay que forzarlo desde fuera.
     *
     * Se probó primero a inyectar CSS (`display: none`) en el documento del `WebView`, pero no
     * funcionaba: el vídeo y los subtítulos los pinta un `<iframe>` de youtube.com que la propia
     * librería mete dentro de esa página, y por política de mismo origen el CSS del documento
     * exterior no puede alcanzar el contenido de un iframe de otro origen.
     *
     * La variable `player` (el objeto `YT.Player`), en cambio, SÍ vive en el documento exterior —
     * es la propia librería quien la crea ahí (ver `ayp_youtube_player.html`, el HTML que carga
     * internamente) — y sus métodos hablan con el iframe mediante `postMessage`, que sí cruza esa
     * frontera de origen sin tocar su DOM. `unloadModule('captions')` es uno de esos métodos: apaga
     * el módulo de subtítulos del reproductor, forzados o no.
     *
     * Un vídeo nuevo puede volver a cargar su propio módulo de subtítulos, así que esto no basta con
     * llamarlo una vez: se repite en cada cambio de estado (ver [onStateChange]), no solo en
     * [onReady]. Es una llamada barata e inofensiva si ya estaban apagados.
     */
    private fun hideCaptions() {
        val webView = findWebView(view) ?: return
        webView.evaluateJavascript(
            "if (typeof player !== 'undefined' && player.unloadModule) { player.unloadModule('captions'); }",
            null
        )
    }

    /**
     * Busca el `WebView` que la librería esconde varias capas dentro de [YouTubePlayerView]. No
     * expone ninguna forma pública de llegar a él, así que hay que recorrer el árbol de vistas a
     * mano; existe ya en cuanto se infla el layout, antes de [load].
     */
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Congela el vídeo. Se usa al salir del modo vídeo, para que no siga corriendo mudo detrás. */
    fun pause() {
        player?.pause()
    }

    /** Libera el `WebView` interno. Obligatorio: si no, sigue vivo consumiendo batería y datos. */
    fun release() {
        player = null
        view.release()
    }
}
