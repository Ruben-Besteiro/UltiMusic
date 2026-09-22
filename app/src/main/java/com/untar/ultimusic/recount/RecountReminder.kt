package com.untar.ultimusic.recount

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.MainActivity
import java.util.Calendar

/**
 * El aviso anual de UltiMusic Recount: la última semana de diciembre la app lo ofrece sola, con una
 * notificación y un diálogo al arrancar (ver [RecountReminderWorker] y
 * [com.untar.ultimusic.ui.MainActivity]).
 *
 * Las dos vías comparten un único interruptor, [markSeen], y por eso desaparecen a la vez: en cuanto
 * el usuario ENTRA al Recount -da igual por cuál de las dos, o incluso desde Ajustes-, ni la
 * notificación ni el diálogo vuelven a aparecer ese año. Descartar el diálogo con "Ahora no" no
 * marca nada: vuelve a salir en el siguiente arranque hasta que se entre o hasta que se acabe el
 * año. Es un aviso de una semana, no una campaña indefinida.
 *
 * Se guarda un único entero, el último año ya visto, en vez de un booleano: así el aviso vuelve solo
 * cada diciembre sin que nadie tenga que acordarse de rearmarlo el 1 de enero.
 *
 * Sigue el patrón de los demás `*Store` del proyecto (ver
 * [com.untar.ultimusic.data.SortPreferences]): `object` con doble comprobación bajo `synchronized`,
 * `applicationContext`, todo con llamada segura sobre [prefs], e [init] registrado en
 * [com.untar.ultimusic.UltiMusicApp].
 */
object RecountReminder {

    private const val PREFS_NAME = "recount_reminder"
    private const val KEY_SEEN_YEAR = "seen_year"

    /** El primer día de diciembre en que se empieza a avisar (la "última semana": del 25 al 31). */
    private const val FIRST_REMINDER_DAY = 25

    /** Canal e id propios: el canal `playback` y el id 1 son de la notificación de reproducción (ver
     *  [com.untar.ultimusic.playback.UltiMusicNotificationProvider]) y no se pueden reutilizar. */
    private const val CHANNEL_ID = "recount"
    const val NOTIFICATION_ID = 2

    /** Extra con el que la notificación le pide a [MainActivity] que abra el Recount al arrancar. */
    const val EXTRA_OPEN_RECOUNT = "com.untar.ultimusic.OPEN_RECOUNT"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de *Store. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * `true` si [now] cae en la última semana de diciembre, la ventana en la que el Recount se
     * ofrece. Es independiente de si el usuario ya entró este año: el aviso se apaga al entrar
     * ([shouldRemind]), pero el acceso desde Ajustes sigue disponible toda la semana.
     */
    fun isInReminderWeek(now: Long = System.currentTimeMillis()): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        return calendar.get(Calendar.MONTH) == Calendar.DECEMBER &&
            calendar.get(Calendar.DAY_OF_MONTH) >= FIRST_REMINDER_DAY
    }

    /**
     * `true` si toca ofrecer el Recount ahora mismo: estamos en la última semana de diciembre y el
     * usuario todavía no ha entrado este año.
     *
     * [now] entra por parámetro (y no se lee aquí dentro) para que el worker y la actividad puedan
     * hacerse la misma pregunta con el mismo instante, y para poder probarlo sin tocar el reloj.
     */
    fun shouldRemind(now: Long = System.currentTimeMillis()): Boolean {
        if (!isInReminderWeek(now)) return false
        val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        return prefs?.getInt(KEY_SEEN_YEAR, 0) != year
    }

    /**
     * El usuario ya ha entrado al Recount: se apaga el aviso de este año y se retira la notificación
     * si estaba puesta. Lo llama [MainActivity] al abrir la pantalla por cualquier vía, y Ajustes
     * cuando se entra desde allí (solo posible en la ventana de [isInReminderWeek]).
     *
     * Es idempotente y barata.
     */
    fun markSeen(context: Context, now: Long = System.currentTimeMillis()) {
        val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        prefs?.edit()?.putInt(KEY_SEEN_YEAR, year)?.apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Publica la notificación. La llama [RecountReminderWorker]; si el usuario denegó
     * `POST_NOTIFICATIONS` no hace nada y el aviso le llega igualmente por el diálogo de arranque,
     * que no necesita ningún permiso.
     */
    fun notify(context: Context, year: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_RECOUNT, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Mismo criterio que la notificación de reproducción (ver UltiMusicNotificationProvider):
            // un vector monocromo, no el logotipo. Android pinta el icono pequeño como silueta, así
            // que un dibujo a color se vería como una mancha blanca sin forma.
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(context.getString(R.string.recount_reminder_title, year))
            .setContentText(context.getString(R.string.recount_reminder_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Crea el canal si hace falta, igual de perezosamente que hace la notificación de reproducción
     * (ver [com.untar.ultimusic.playback.UltiMusicNotificationProvider]). `IMPORTANCE_DEFAULT` y no
     * `LOW` como aquella: esta sí quiere que se oiga, es lo único que avisa una vez al año.
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_recount),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
