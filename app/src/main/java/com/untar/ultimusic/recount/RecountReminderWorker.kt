package com.untar.ultimusic.recount

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Se despierta una vez al día y, si estamos en la última semana de diciembre y el usuario todavía no
 * ha entrado a su Recount de este año, publica la notificación (ver [RecountReminder]).
 *
 * Es el único WorkManager de la app, y está aquí por una razón concreta: la gracia de la
 * notificación es llegar cuando el usuario NO está usando UltiMusic. El patrón oportunista que el
 * proyecto usa para todo lo demás -`YouTubeStatsRefresh.isDue`, "compruébalo la próxima vez que se
 * abra la app"- no serviría, porque para entonces ya está saliendo el diálogo de arranque y la
 * notificación sobraría.
 *
 * Que se despierte a diario y decida dentro, en vez de programarse para el 25 de diciembre, es
 * deliberado: un trabajo periódico corto sobrevive a reinicios, cambios de hora y a que el sistema
 * lo retrase unas horas, mientras que una única cita a un año vista es justo lo que Android peor
 * conserva. El coste de comprobar 364 veces un mes y un día es cero.
 */
class RecountReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        if (RecountReminder.shouldRemind(now)) {
            RecountReminder.notify(
                applicationContext,
                Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            )
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "recount-reminder"

        /**
         * Deja el trabajo encolado. La llama [com.untar.ultimusic.UltiMusicApp] en cada arranque, y
         * con [ExistingPeriodicWorkPolicy.KEEP] eso no lo reinicia: si ya estaba puesto de un
         * arranque anterior se respeta el que hay, en vez de volver a empezar la cuenta cada vez que
         * se abre la app (que con `REPLACE` podría dejarlo sin dispararse nunca).
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecountReminderWorker>(1, TimeUnit.DAYS).build()
            )
        }
    }
}
