package com.untar.ultimusic.ui.player

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Envuelve el [YouTubePlayerView] para que el iPod no tenga que saber nada de la librería de
 * YouTube: le pide "carga este vídeo desde este milisegundo" y expone lo necesario para traspasar
 * el sonido con el audio local al entrar y salir del modo vídeo. Existe como clase aparte porque
 * `IPodDialogFragment` ya es largo y porque así toda la conversación con la librería queda en un
 * solo sitio.
 *
 * **Solo suena una cosa a la vez.** Los términos de servicio de la API de YouTube prohíben aplicar
 * una pista de audio distinta a su vídeo (`developers.google.com/youtube/terms/developer-policies`,
 * sección III.I.7), así que este vídeo NUNCA sirve de capa muda sobre el audio local: mientras esté
 * visible suena su propio audio real (ver [setAudible]), y `IPodDialogFragment` congela el audio
 * local justo antes de mostrarlo. Al salir del modo vídeo pasa lo contrario: este controlador queda
 * mudo y en pausa, y quien llama traduce la posición de vuelta al audio local con [localPositionMs]
 * (resta el mismo [offsetMs] que sumó [load] para entrar). Por el mismo motivo (sección III.I.9,
 * prohíbe un "background player" que no esté visible) `IPodDialogFragment` tiene que llamar a
 * [setAudible] con `false` en cuanto la ventana deja de estar en primer plano, no solo al salir del
 * modo vídeo a mano.
 *
 * **Controles reales.** [playerOptions] pide `controls(1)`: el propio reproductor de YouTube enseña
 * sus mandos (play/pausa, barra, subtítulos, pantalla completa...) y los toques le llegan sin que
 * nada los intercepte por el camino. Es la otra condición del mismo apartado de esa política
 * (sección III.I.6, prohíbe modificar o bloquear cualquier función del reproductor): antes se
 * escondían los mandos (`controls(0)`, opción documentada y sí permitida) y además se forzaba el
 * apagado de los subtítulos llamando a un método interno no documentado de la librería
 * (`unloadModule('captions')`); ahora, con los mandos reales visibles, el usuario los apaga él mismo
 * si quiere, sin que la aplicación toque nada del reproductor.
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
    private val onPlaybackError: () -> Unit,
    /**
     * Avisa de si hay que tapar el WebView con una ruedecilla de carga propia: `true` justo al
     * pedir un vídeo (ver [load]), `false` en cuanto llega el primer aviso del reproductor sobre
     * ese vídeo (ver [awaitingFirstState]), que es cuando YouTube ya pinta algo por su cuenta
     * (su propia ruedecilla, o el vídeo directamente) y tener las dos a la vez sobraría.
     */
    private val onLoadingChanged: (Boolean) -> Unit = {}
) {

    private var player: YouTubePlayer? = null

    /**
     * True mientras el vídeo debe sonar de verdad (ver [setAudible]). Se guarda como campo, y no
     * solo como una llamada a [YouTubePlayer.mute]/[YouTubePlayer.unMute], porque hay que poder
     * pedirlo ANTES de que el reproductor esté listo (ver [onReady]) o antes de que arranque un
     * vídeo concreto (ver el `start` de [YouTubePlayer]): en ambos casos se aplica en cuanto el
     * reproductor pueda recibir la orden, no antes.
     */
    private var audible = false

    /**
     * Milisegundos que se suma a la posición del audio local para obtener la del vídeo: positivo lo
     * adelanta, negativo lo atrasa. Lo fijan los ajustes del reproductor de vídeo (ver [setOffset])
     * y lo aplican [load] y [seekTo] para que todo el mundo hable siempre en "posición de vídeo" a
     * partir de la posición real del audio, y [localPositionMs] para deshacer esa cuenta al salir.
     */
    private var offsetMs: Long = 0

    /** True en cuanto se ha pedido la inicialización, para no pedirla dos veces. */
    private var initializing = false

    /**
     * Vídeo pedido antes de que el reproductor estuviera listo (ver [load]). Guarda cómo consultar
     * la posición y el estado del audio, no sus valores en el momento del pedido: la primera
     * inicialización del `WebView` (ver [load]) puede tardar segundos, y el audio local no se
     * espera. Si aquí se guardara el número fijo de entonces, el vídeo arrancaría ya atrasado por
     * lo que haya tardado esa inicialización, offset aparte.
     */
    private var pending: Request? = null

    /** Lo que hace falta para arrancar un vídeo, para poder guardarlo si aún no se puede. */
    private data class Request(
        val videoId: String,
        val positionMs: () -> Long,
        val isPlaying: () -> Boolean
    )

    /** Última posición y estado conocidos del vídeo (ver [onCurrentSecond]/[onStateChange]), que
     * leen [localPositionMs] y [wasPlaying] para reanudar el audio local al salir del modo vídeo. */
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

    /**
     * False desde que se pide un vídeo (ver [YouTubePlayer.start]) hasta que llega su primer estado
     * PLAYING (ver [onStateChange]). La IFrame Player API de YouTube ignora una orden de
     * `play()`/`pause()` mandada mientras el vídeo todavía no ha arrancado de verdad (sigue en
     * buffering o "cued"), y en cuanto esté listo se pone en marcha él solo pase lo que pase con esa
     * orden perdida; por eso el pedido inicial de pausa no se manda directo, se guarda en
     * [pauseOnceStarted] y se aplica de forma fiable en cuanto llega el primer PLAYING.
     */
    private var videoStarted = false

    /**
     * True entre que se pide un vídeo (ver [load]) y que llega el primer aviso de estado del
     * reproductor sobre él (ver [onStateChange]), que es cuando la ruedecilla de carga propia (ver
     * [onLoadingChanged]) deja de hacer falta porque YouTube ya se encarga de pintar algo.
     */
    private var awaitingFirstState = false

    private val listener = object : AbstractYouTubePlayerListener() {

        override fun onReady(youTubePlayer: YouTubePlayer) {
            android.util.Log.d("UMVideoDebug", "onReady")
            // Aplica el mudo/sonido pedido antes de que el reproductor estuviera listo (ver
            // [audible]); por defecto empieza mudo, como cualquier vídeo recién insertado.
            if (audible) youTubePlayer.unMute() else youTubePlayer.mute()
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
            if (awaitingFirstState) {
                awaitingFirstState = false
                onLoadingChanged(false)
            }
            lastKnownPlaying = state == PlayerConstants.PlayerState.PLAYING
            if (state == PlayerConstants.PlayerState.PLAYING) {
                videoStarted = true
                // Ver [pauseOnceStarted]: en cuanto el vídeo arranca de verdad (ya hay fotograma), se
                // pausa si el audio local estaba en pausa en el momento de entrar en modo vídeo.
                if (pauseOnceStarted) {
                    pauseOnceStarted = false
                    youTubePlayer.pause()
                }
            }
        }

        override fun onError(
            youTubePlayer: YouTubePlayer,
            error: PlayerConstants.PlayerError
        ) {
            android.util.Log.d("UMVideoDebug", "onError $error")
            if (awaitingFirstState) {
                awaitingFirstState = false
                onLoadingChanged(false)
            }
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
     * Carga [videoId] a partir de lo que devuelvan [positionMs] e [isPlaying], que son la posición y
     * el estado del audio local EN EL MOMENTO DE ENTRAR en modo vídeo (quien llama ya lo ha
     * congelado para entonces, ver `IPodDialogFragment.startVideo`). Se piden como funciones, y no
     * como valores ya leídos, porque si el reproductor aún no está listo (ver más abajo) el vídeo no
     * arranca hasta que llegue [onReady], que puede tardar segundos en la primera inicialización;
     * llamarlas en ese momento, y no ahora, es lo único que evita que el vídeo nazca en un punto
     * distinto al que se congeló el audio local.
     *
     * [offsetMs] es el desplazamiento guardado de la canción (ver `Song.videoOffsetMs`); se aplica
     * aquí y queda recordado para que [seekTo] y [localPositionMs] lo sigan aplicando mientras dure
     * este vídeo.
     */
    fun load(videoId: String, positionMs: () -> Long, isPlaying: () -> Boolean, offsetMs: Long = 0) {
        this.offsetMs = offsetMs
        lastKnownPositionMs = withOffset(positionMs())
        // Se adelanta aquí, y no se deja solo a [onStateChange], para que [wasPlaying] conteste lo
        // pedido incluso si el vídeo falla antes de llegar a ningún estado (ver [onError] en
        // IPodDialogFragment.exitVideo): sin esto, un fallo inmediato dejaría el audio local pausado
        // para siempre, aunque estuviera sonando justo antes de entrar en modo vídeo.
        lastKnownPlaying = isPlaying()
        awaitingFirstState = true
        onLoadingChanged(true)
        val request = Request(videoId, positionMs, isPlaying)
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
     * Pide sonido real ([value] `true`) o mudo ([value] `false`). `IPodDialogFragment` lo pone a
     * `true` justo antes de [load] al entrar en modo vídeo (a partir de ahí el vídeo es la única
     * fuente de audio, nunca a la vez que el archivo local) y a `false` al salir de modo vídeo o en
     * cuanto la ventana deja de estar en primer plano, antes de [pause] — los términos de servicio de
     * la API de YouTube prohíben tanto aplicarle una pista de audio ajena (sección III.I.7) como
     * dejarlo sonando sin estar a la vista (sección III.I.9, "background player").
     *
     * Se guarda en [audible] y no solo se manda al reproductor porque puede pedirse antes de que
     * exista (ver [onReady]) o antes de arrancar el vídeo concreto que lo aplicará (ver el `start`
     * de [YouTubePlayer]).
     */
    fun setAudible(value: Boolean) {
        audible = value
        val ready = player ?: return
        if (value) ready.unMute() else ready.mute()
    }

    /** Salta al instante a [positionMs] (posición del audio local). */
    fun seekTo(positionMs: Long) {
        player?.seekTo(withOffset(positionMs) / 1000f)
    }

    /**
     * Cambia el desplazamiento en caliente a [newOffsetMs] y reposiciona el vídeo al instante a
     * partir de [currentAudioPositionMs] (la posición del audio local en el momento de ajustarlo, que
     * mientras dura el modo vídeo está congelada, ver [load]), para que el ajuste se note en tiempo
     * real mientras se edita en los ajustes del reproductor de vídeo.
     */
    fun setOffset(newOffsetMs: Long, currentAudioPositionMs: Long) {
        offsetMs = newOffsetMs
        seekTo(currentAudioPositionMs)
    }

    /**
     * Posición equivalente del audio local para donde vaya el vídeo AHORA MISMO (resta [offsetMs],
     * lo contrario de [withOffset]). La usa `IPodDialogFragment` al salir del modo vídeo —a mano o
     * porque la ventana pasó a segundo plano— para reanudar el audio local justo donde se quedó el
     * vídeo, en vez de donde se congeló al entrar. [lastKnownPositionMs] se actualiza como mucho una
     * vez por segundo (ver [onCurrentSecond]), así que puede ir hasta un segundo por detrás de la
     * posición real; no hace falta más precisión para reanudar audio, y no vale la pena preguntarle
     * al reproductor su posición exacta de forma asíncrona justo al salir.
     */
    fun localPositionMs(): Long = (lastKnownPositionMs - offsetMs).coerceAtLeast(0L)

    /** Si el vídeo estaba sonando la última vez que se supo de él (ver [lastKnownPlaying]). Mismo uso
     * que [localPositionMs]: reanudar el audio local en el mismo estado en que quedó el vídeo. */
    fun wasPlaying(): Boolean = lastKnownPlaying

    /** Traduce una posición del audio local a la posición equivalente del vídeo, aplicando
     * [offsetMs]. Nunca negativa: YouTube no admite pedir un vídeo desde antes de su inicio. */
    private fun withOffset(audioPositionMs: Long): Long = (audioPositionMs + offsetMs).coerceAtLeast(0L)

    /**
     * `loadVideo` deja el vídeo cargándose y reproduciéndose. Si el audio local no estaba sonando, se
     * pausa en cuanto arranque de verdad (ver [pauseOnceStarted]), no en el mismo instante de pedir
     * la carga. La librería trabaja en segundos con decimales, de ahí la división.
     *
     * [Request.positionMs] e [Request.isPlaying] se leen aquí, justo antes de arrancar, y no antes:
     * ver [load] para por qué importa.
     */
    private fun YouTubePlayer.start(request: Request) {
        if (audible) unMute() else mute()
        videoStarted = false
        val playing = request.isPlaying()
        pauseOnceStarted = !playing
        loadVideo(request.videoId, withOffset(request.positionMs()) / 1000f)
    }

    /**
     * Opciones con las que se arranca el reproductor. `controls = 1` (el valor por defecto, pero se
     * deja explícito) deja los mandos propios de YouTube visibles y funcionando: play/pausa, barra,
     * subtítulos, pantalla completa... Antes se pedía `controls(0)` para esconderlos (una opción
     * documentada, y por tanto permitida) y se tapaban los toques al vídeo con un contenedor propio
     * para que no le llegara ninguno (ver el XML de `videoContainer`, ya no es así); eso dejaba el
     * reproductor inerte, y los términos de servicio de la API de YouTube prohíben precisamente
     * modificar o bloquear cualquier función suya (sección III.I.6). Ahora el vídeo es un reproductor
     * de YouTube normal: sus mandos son los que gobiernan play/pausa/subtítulos mientras está visible.
     *
     * El `Builder` necesita el contexto porque él solo rellena el `origin` con el paquete de la app,
     * que es lo que YouTube exige desde julio de 2025 para dejar reproducir un vídeo embebido. Por
     * eso hay que construir las opciones a partir del `Builder` y no a mano: si se pierde el
     * `origin`, el reproductor vuelve a fallar con el error 152.
     */
    private fun playerOptions(): IFramePlayerOptions =
        IFramePlayerOptions.Builder(view.context).controls(1).build()

    /** Congela el vídeo. Se usa al salir del modo vídeo (a mano o porque la ventana pasó a segundo
     * plano), siempre después de [setAudible] con `false`. */
    fun pause() {
        player?.pause()
    }

    /** Libera el `WebView` interno. Obligatorio: si no, sigue vivo consumiendo batería y datos. */
    fun release() {
        player = null
        view.release()
    }
}
