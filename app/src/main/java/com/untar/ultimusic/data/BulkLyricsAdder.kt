package com.untar.ultimusic.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.ApiRateLimitException
import com.untar.ultimusic.data.remote.LrcLibApi
import com.untar.ultimusic.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * El "Añadir todas las letras" de ajustes > ajustes visuales (ver
 * [com.untar.ultimusic.ui.settings.SettingsDialogFragment]): recorre las canciones que todavía no
 * tienen letra y les mete la primera coincidencia de lrclib.net (ver [LrcLibApi]), igual que hace a
 * mano el buscador del editor de metadatos
 * ([com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]) pero para toda la fonoteca de
 * una sola vez.
 *
 * Es un `object` — un único pase a la vez para toda la aplicación, igual que
 * [com.untar.ultimusic.data.remote.YouTubeStatsRefresh] — y corre en un [CoroutineScope] propio, NO
 * en el de ningún fragmento: así sigue vivo aunque
 * [com.untar.ultimusic.ui.settings.BulkLyricsProgressDialogFragment] se cierre (botón "Segundo
 * plano", o simplemente el usuario sale de Ajustes con el "atrás"). Es el mismo patrón que
 * `LibraryRepository.observerScope`, aplicado aquí porque esto no vive dentro de esa clase.
 *
 * Es best-effort, como el resto de llamadas a APIs externas de la aplicación: una canción sin
 * resultado en lrclib.net, o cuyo `search` falla (sin red, límite de peticiones que no se recupera a
 * tiempo...), se salta sin más y el recuento sigue con la siguiente. No hay reintento manual: el
 * usuario puede volver a lanzar "Añadir todas las letras" cuando quiera y solo se procesarán las que
 * SIGAN sin letra.
 */
object BulkLyricsAdder {

    /** Estado observado por [com.untar.ultimusic.ui.settings.BulkLyricsProgressDialogFragment]. */
    sealed interface State {
        data object Idle : State

        /**
         * [index]/[total] son 1-based ("vamos por la 5 de 1282"); ambos a 0 significa que el pase
         * acaba de arrancar y todavía se está calculando la lista de canciones sin letra (consulta a
         * BD, ver [start]). [startedAtMs] es fijo durante todo el pase, para que quien pinte el
         * tiempo transcurrido no tenga que guardar nada por su cuenta.
         */
        data class Running(val index: Int, val total: Int, val songTitle: String, val startedAtMs: Long) : State
        data class Finished(val added: Int, val total: Int) : State
        data object Cancelled : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /** true mientras el progreso se enseña por notificación en vez de por el diálogo (ver
     *  [enterBackgroundMode]): mientras el diálogo está en pantalla no hace falta duplicarlo. */
    @Volatile
    private var backgroundMode = false

    /**
     * Arranca un pase nuevo si no hay ya uno en marcha (ver [isRunning]; si lo hay, no hace nada —
     * quien llama debe reabrir el diálogo de progreso en vez de lanzar otro).
     */
    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext
        backgroundMode = false
        val startedAtMs = System.currentTimeMillis()
        // Se pone en marcha ANTES de lanzar la corrutina (que empieza suspendida, consultando la
        // BD): sin esto, entre que se llama a start() y que el bucle pinta la primera canción habría
        // un hueco en el que el estado seguiría siendo Idle, y el diálogo de progreso (que se cierra
        // solo al ver Idle) se cerraría nada más abrirse.
        _state.value = State.Running(index = 0, total = 0, songTitle = "", startedAtMs = startedAtMs)

        job = scope.launch {
            val repository = LibraryRepository.get(appContext)
            val pending = repository.songs.first().filter { it.lyrics.isNullOrBlank() }
            val total = pending.size

            var added = 0
            for ((i, song) in pending.withIndex()) {
                if (!isActive) return@launch
                _state.value = State.Running(i + 1, total, song.title, startedAtMs)
                if (backgroundMode) postProgressNotification(appContext, song.title, i + 1, total)

                val artist = song.artists.firstOrNull()?.name.orEmpty()
                val lyrics = runCatching { searchWithRetry(song.title, artist, song.duration) }
                    .getOrNull()
                    ?.firstOrNull()
                    ?.let { it.syncedLyrics ?: it.plainLyrics }

                if (!isActive) return@launch
                if (lyrics != null) {
                    repository.setLyrics(song, lyrics)
                    added++
                }
            }

            _state.value = State.Finished(added, total)
            if (backgroundMode) postFinishedNotification(appContext, added, total)
        }
    }

    /**
     * Una vez, con reintento si lrclib.net responde que estamos limitados: sin esto, un pase de
     * cientos de canciones que tropieza con un límite se comería el resto sin ni siquiera intentarlas
     * (ver [com.untar.ultimusic.data.remote.RateLimitGuard.ensureNotBlocked], que corta la petición
     * sin llegar a la red). La espera es cancelable (`delay` lo es), así que "Cancelar" sigue
     * respondiendo al momento aunque el pase esté a mitad de esta espera.
     */
    private suspend fun searchWithRetry(title: String, artist: String, durationMs: Long) =
        try {
            LrcLibApi.search(title, artist, durationMs)
        } catch (e: ApiRateLimitException) {
            delay(e.retryAfterMs.coerceAtMost(TimeUnit.MINUTES.toMillis(2)))
            LrcLibApi.search(title, artist, durationMs)
        }

    /** Botón "Cancelar" del diálogo de progreso. Corta el pase a la primera comprobación de [isActive]
     *  que encuentre (la siguiente canción, o antes si está a mitad de una espera por límite). */
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        _state.value = State.Cancelled
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Botón "Segundo plano" del diálogo de progreso: a partir de ahora el avance se enseña con una
     * notificación en vez de con el diálogo, que quien llama cierra justo después de esto.
     */
    fun enterBackgroundMode(context: Context) {
        backgroundMode = true
        (state.value as? State.Running)?.let { running ->
            postProgressNotification(context.applicationContext, running.songTitle, running.index, running.total)
        }
    }

    /**
     * Llamada por el diálogo de progreso al ver un estado final ([State.Finished] o
     * [State.Cancelled]): lo vuelve a dejar en [State.Idle] para que la próxima vez que se pulse
     * "Añadir todas las letras" se ofrezca la confirmación de siempre en vez de reabrir un diálogo ya
     * terminado.
     */
    fun reset() {
        if (_state.value is State.Finished || _state.value is State.Cancelled) {
            _state.value = State.Idle
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun postProgressNotification(context: Context, songTitle: String, index: Int, total: Int) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(context.getString(R.string.settings_bulk_lyrics_progress_title))
            .setContentText(context.getString(R.string.settings_bulk_lyrics_notification_text, songTitle, index, total))
            .setContentIntent(openAppPendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), index, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun postFinishedNotification(context: Context, added: Int, total: Int) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(context.getString(R.string.settings_bulk_lyrics_progress_title))
            .setContentText(context.getString(R.string.settings_bulk_lyrics_finished, added, total))
            .setContentIntent(openAppPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, NOTIFICATION_ID, intent, flags)
    }

    /** Mismo patrón perezoso que [com.untar.ultimusic.recount.RecountReminder.ensureChannel].
     *  `IMPORTANCE_LOW`, como la de reproducción: se actualiza sin parar mientras avanza y no debe
     *  sonar ni vibrar cada vez. */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_bulk_lyrics),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /** Canal e id propios: 1 es la notificación de reproducción, 2 la del aviso anual de Recount
     *  (ver [com.untar.ultimusic.recount.RecountReminder]). */
    private const val CHANNEL_ID = "bulk_lyrics"
    private const val NOTIFICATION_ID = 3
}
