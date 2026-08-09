package com.untar.ultimusic.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.untar.ultimusic.R

/**
 * Construye "a mano" la notificación de reproducción, en vez de usar la
 * [androidx.media3.session.DefaultMediaNotificationProvider] de fábrica de Media3.
 *
 * El motivo es un único método: `createNotification` de la de fábrica es `final` y no deja
 * enganchar [NotificationCompat.Builder.setColor] en ningún sitio, así que no hay forma de
 * pedirle el color dinámico de la canción que suena ([DynamicColor][com.untar.ultimusic.util.DynamicColor],
 * el mismo que tiñe el resto de la aplicación). Esta clase sigue el mismo patrón que la de
 * fábrica —tres botones (anterior/play-pausa/siguiente), estilo
 * [MediaStyleNotificationHelper.MediaStyle] enganchado a la sesión, carátula cargada en segundo
 * plano con [CoverBitmapLoader]— pero termina con `.setColor(...).setColorized(true)`, así que la
 * notificación (y, gracias a `setColorized`, también la tarjeta de la pantalla de bloqueo) se
 * tiñe del color de la carátula en vez de quedarse con el gris de siempre de Android.
 *
 * Se la registra en [PlaybackService.onCreate] con `setMediaNotificationProvider`; a partir de ahí
 * Media3 la llama solo, cada vez que cambia algo que afecte a la notificación (canción, play/pausa,
 * carátula que termina de cargar...). No hay que llamarla a mano en ningún sitio.
 */
class UltiMusicNotificationProvider(private val service: PlaybackService) : MediaNotification.Provider {

    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        ensureChannel()

        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val showPauseButton = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(metadata.title)
            .setContentText(metadata.artist)
            .setSubText(metadata.albumTitle)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(actionFactory.createNotificationDismissalIntent(mediaSession))
            .setOnlyAlertOnce(true)
            .setOngoing(showPauseButton)
            // VISIBILITY_PUBLIC: que se vea entera en la pantalla de bloqueo, con sus controles,
            // sin tener que desbloquear el móvil antes.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(service.accentColor.value)
            .setColorized(true)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        builder.addAction(
            actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(service, R.drawable.ic_skip_previous),
                service.getString(R.string.notif_previous),
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
        )
        builder.addAction(
            actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(
                    service,
                    if (showPauseButton) R.drawable.ic_pause else R.drawable.ic_play
                ),
                service.getString(if (showPauseButton) R.string.notif_pause else R.string.notif_play),
                Player.COMMAND_PLAY_PAUSE
            )
        )
        builder.addAction(
            actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(service, R.drawable.ic_skip_next),
                service.getString(R.string.notif_next),
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            )
        )

        // La carátula se pide de forma asíncrona (CoverBitmapLoader lee de disco). Si para cuando
        // hace falta construir la notificación ya está en caché (isDone), se pone de una vez; si
        // no, se manda la notificación sin ella y se vuelve a mandar en cuanto termine de cargar
        // (mismo patrón que usa DefaultMediaNotificationProvider).
        val bitmapFuture = mediaSession.bitmapLoader.loadBitmapFromMetadata(metadata)
        if (bitmapFuture != null) {
            if (bitmapFuture.isDone) {
                runCatching { bitmapFuture.get() }.getOrNull()?.let { builder.setLargeIcon(it) }
            } else {
                val handler = Handler(player.applicationLooper)
                Futures.addCallback(
                    bitmapFuture,
                    object : FutureCallback<Bitmap> {
                        override fun onSuccess(result: Bitmap) {
                            onNotificationChangedCallback.onNotificationChanged(
                                MediaNotification(NOTIFICATION_ID, builder.setLargeIcon(result).build())
                            )
                        }

                        override fun onFailure(t: Throwable) {
                            // Sin carátula la notificación se queda con el icono pequeño: no pasa nada.
                        }
                    },
                    handler::post
                )
            }
        }

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean = false

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.notification_channel_playback),
            // LOW: se ve y se puede tocar, pero no suena ni vibra al actualizarse (cambia de
            // canción constantemente, sería muy molesto que avisara cada vez).
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
    }
}
