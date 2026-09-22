package com.untar.ultimusic

import android.app.Application
import com.untar.ultimusic.data.ArtistGroupingPreferences
import com.untar.ultimusic.data.LibraryRepository
import com.untar.ultimusic.data.SortPreferences
import com.untar.ultimusic.data.remote.ApiCache
import com.untar.ultimusic.data.remote.GeniusTokenStore
import com.untar.ultimusic.data.remote.YouTubeApiKeyStore
import com.untar.ultimusic.data.remote.YouTubeStatsRefresh
import com.untar.ultimusic.recount.RecountReminder
import com.untar.ultimusic.recount.RecountReminderWorker
import com.untar.ultimusic.util.AppLocale
import com.untar.ultimusic.util.NetworkImage
import com.untar.ultimusic.util.PlaylistHistoryStore
import com.untar.ultimusic.util.PlaylistResumeStore
import com.untar.ultimusic.util.PlaylistShuffleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Existe solo para darles un `Context` a [ApiCache], [GeniusTokenStore], [YouTubeApiKeyStore],
 * [YouTubeStatsRefresh], [SortPreferences], [ArtistGroupingPreferences], [PlaylistResumeStore],
 * [PlaylistHistoryStore], [PlaylistShuffleStore] y [RecountReminder] antes de que exista ninguna
 * pantalla, y para dejar
 * encolado el aviso anual de UltiMusic Recount ([RecountReminderWorker]).
 *
 * Podría hacerse desde `MainActivity.onCreate`, pero entonces dependerían de acordarse de llamarlas
 * desde todos los sitios que puedan acabar pidiendo algo por red; el día que se olvidara uno, la
 * caché se desactivaría **en silencio** y el token de Genius parecería no estar configurado. Aquí no
 * hay nada que olvidar: Android llama a esto antes que a cualquier `Activity` o `Service` del
 * proceso.
 */
class UltiMusicApp : Application() {

    /** Para la poda de arranque. No es `lifecycleScope` de nadie a propósito: la poda no debe
     *  cancelarse porque el usuario cierre una pantalla. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Antes que cualquier otra cosa: si es la primera vez que arranca la aplicación, fija el
        // idioma según el del sistema (ver la cabecera de AppLocale.applyDefaultIfUnset).
        AppLocale.applyDefaultIfUnset()
        ApiCache.init(this)
        GeniusTokenStore.init(this)
        YouTubeApiKeyStore.init(this)
        YouTubeStatsRefresh.init(this)
        SortPreferences.init(this)
        ArtistGroupingPreferences.init(this)
        PlaylistResumeStore.init(this)
        PlaylistHistoryStore.init(this)
        PlaylistShuffleStore.init(this)
        RecountReminder.init(this)
        // Deja encolado el trabajo diario que ofrece el Recount la última semana de diciembre. Es
        // idempotente (ExistingPeriodicWorkPolicy.KEEP), así que llamarlo en cada arranque no
        // reinicia nada.
        RecountReminderWorker.schedule(this)
        // Poda por tiempo, una vez por arranque y fuera del hilo principal: abrir la base de datos y
        // borrar filas no puede retrasar el primer frame de la aplicación.
        appScope.launch { ApiCache.pruneExpired() }
        // Ídem para los temporales que deja NetworkImage.download en cacheDir: ver
        // NetworkImage.pruneOldTempFiles.
        appScope.launch { NetworkImage.pruneOldTempFiles(this@UltiMusicApp) }
        // Etiqueta de idioma de canciones que se quedaron con letra pero sin ella (ver
        // LibraryRepository.backfillMissingLanguageTags).
        appScope.launch { LibraryRepository.get(this@UltiMusicApp).backfillMissingLanguageTags() }
    }
}
