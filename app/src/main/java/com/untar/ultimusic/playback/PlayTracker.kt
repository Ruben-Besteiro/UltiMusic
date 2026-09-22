package com.untar.ultimusic.playback

import android.content.Context
import android.os.SystemClock
import com.untar.ultimusic.data.RecountRepository
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cuenta cuánto suena de verdad cada canción y, cuando pasa del 50%, anota la escucha para UltiMusic
 * Recount (ver [RecountRepository] y
 * [com.untar.ultimusic.data.db.entities.PlayEventEntity]).
 *
 * Es una máquina de estados de una sola sesión: se abre cuando una canción pasa a ser la actual, se
 * pausa y se reanuda con la reproducción, y se cierra cuando llega otra canción o se muere el
 * servicio. Al cerrarse decide si esa sesión llegó al umbral y merece una fila.
 *
 * **Mide tiempo, no posición.** El acumulado sube solo mientras el reproductor está sonando de
 * verdad, así que:
 *
 * - Saltar al 90% y dejar que termine NO cuenta como escucha: se oyó el 10%.
 * - Pausar en mitad de la canción, irse a comer y volver tampoco regala nada.
 * - Rebobinar y volver a oír un trozo SÍ suma (es tiempo escuchado), y por eso el acumulado puede
 *   acabar por encima de la duración de la canción. Se guarda tal cual, sin recortar: sigue siendo
 *   una sola escucha, solo que con más minutos detrás.
 * - Escuchar la misma canción dos veces seguidas son dos sesiones distintas, y por tanto dos
 *   escuchas, porque cada vuelta vuelve a pasar por `loadCurrent`.
 * - **Un solo salto hacia delante ([onSeek]) descalifica la sesión entera**, aunque luego se
 *   escuche el resto entero y se supere de sobra el 50%: es justo el caso que se quiere excluir
 *   (oír solo un trozo eligiendo dónde, en vez de la canción de principio a fin). Una vez
 *   descalificada no hay vuelta atrás dentro de la misma sesión; rebobinar después no la salva.
 *   El salto que coloca la canción en su posición guardada al reanudar (`seekToMs` de
 *   `loadCurrent`/`startPlayback`) no cuenta para esto: pasa antes de que [setPlaying] marque que
 *   ha sonado algo de verdad, así que no es "saltarse" nada que el usuario ya estuviera oyendo.
 *
 * El reloj es [SystemClock.elapsedRealtime] a propósito, no [System.currentTimeMillis]: el primero
 * cuenta desde el arranque del dispositivo y nadie lo puede mover, mientras que el segundo pega un
 * salto si el usuario cambia la hora a mano o si el sistema la sincroniza por red. Con el reloj de
 * pared, ese salto se sumaría entero al tiempo escuchado y podría convertir 20 segundos de música en
 * una escucha completa. `currentTimeMillis` se usa SOLO para el sello de cuándo empezó la canción,
 * que es lo que decide a qué año va la escucha.
 *
 * Todo está `@Synchronized` porque los avisos del reproductor llegan por el hilo principal pero
 * [flush] también se llama desde `onDestroy`, y una escucha contada dos veces (o ninguna) por una
 * carrera entre esas dos rutas sería un fallo silencioso e imposible de ver luego en la pantalla.
 */
class PlayTracker(context: Context) {

    private val repository = RecountRepository.get(context)

    /**
     * Scope propio, no el `serviceScope` de [PlaybackService]. Es deliberado: `onDestroy` cancela ese
     * scope, y el [flush] final -justo el que salva la última canción que estaba sonando- se quedaría
     * a medias antes de llegar a escribir en la base de datos.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var songId: Long? = null
    private var durationMs: Long = 0L
    private var startedAt: Long = 0L
    private var accumulatedMs: Long = 0L

    /** Instante ([SystemClock.elapsedRealtime]) en que empezó el tramo que se está midiendo, o null
     *  si ahora mismo no suena nada. */
    private var soundingSince: Long? = null

    /** Si ya ha sonado algo de verdad en esta sesión (es decir, si [soundingSince] ha llegado a
     *  valer algo distinto de null alguna vez). Antes de esto, un salto es la posición inicial de
     *  la canción (p. ej. al reanudar donde se dejó), no un salto sobre algo que se estuviera
     *  oyendo, y por tanto no descalifica nada (ver [onSeek]). */
    private var hasStartedSounding = false

    /** Si esta sesión ha tenido algún salto hacia delante mientras sonaba de verdad. Si es `true`,
     *  [flush] no la anota pase lo que pase con [accumulatedMs] (ver [onSeek]). */
    private var disqualified = false

    /**
     * Una canción pasa a ser la actual: se cierra la sesión anterior (que puede acabar anotando su
     * escucha) y se abre una nueva a cero.
     *
     * No se toca [soundingSince]: la sesión nace parada y empieza a contar cuando el reproductor
     * avise de que suena de verdad. Entre este momento y ese aviso hay un `prepare()` de por medio
     * -décimas de segundo de buffering- que no es música escuchada.
     */
    @Synchronized
    fun start(song: Song) {
        flush()
        songId = song.id
        durationMs = song.duration
        startedAt = System.currentTimeMillis()
        accumulatedMs = 0L
        soundingSince = null
        hasStartedSounding = false
        disqualified = false
    }

    /**
     * El reproductor ha empezado o dejado de sonar. Idempotente: repetir el mismo estado no suma ni
     * pierde nada, que es lo que permite llamarla sin miedo desde cualquier aviso del reproductor.
     */
    @Synchronized
    fun setPlaying(isPlaying: Boolean) {
        if (songId == null) return
        if (isPlaying) {
            if (soundingSince == null) soundingSince = SystemClock.elapsedRealtime()
            hasStartedSounding = true
        } else {
            accumulateNow()
        }
    }

    /**
     * Aviso de que el reproductor ha saltado de [oldPositionMs] a [newPositionMs] dentro de la
     * canción actual (ver [Player.Listener.onPositionDiscontinuity] con motivo
     * `DISCONTINUITY_REASON_SEEK` en [PlaybackService]). Solo un salto hacia delante -y solo si ya
     * ha sonado algo de esta sesión, ver [hasStartedSounding]- descalifica la escucha; rebobinar
     * sigue sin penalizar nada.
     */
    @Synchronized
    fun onSeek(oldPositionMs: Long, newPositionMs: Long) {
        if (songId == null || !hasStartedSounding) return
        if (newPositionMs > oldPositionMs) disqualified = true
    }

    /**
     * Cierra la sesión en curso y, si llegó al umbral, la anota. Se llama al cambiar de canción y al
     * morir el servicio.
     *
     * Una canción sin duración conocida (`duration == 0`, archivo cuya etiqueta no se pudo leer)
     * nunca cuenta: no hay contra qué comparar el 50%, y darla por buena a partir de cualquier
     * cantidad de segundos sería inventarse el criterio.
     */
    @Synchronized
    fun flush() {
        accumulateNow()
        val id = songId ?: return
        val played = accumulatedMs
        val started = startedAt
        val duration = durationMs
        val wasDisqualified = disqualified
        songId = null
        accumulatedMs = 0L
        soundingSince = null
        hasStartedSounding = false
        disqualified = false
        if (wasDisqualified || duration <= 0L || played < duration / 2) return
        scope.launch { repository.record(id, started, played) }
    }

    /** Suma a [accumulatedMs] lo que llevaba sonando el tramo actual y lo cierra. */
    private fun accumulateNow() {
        val since = soundingSince ?: return
        accumulatedMs += (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L)
        soundingSince = null
    }
}
