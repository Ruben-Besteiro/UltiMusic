package com.untar.ultimusic

import android.app.Application
import com.untar.ultimusic.data.remote.ApiCache
import com.untar.ultimusic.data.remote.GeniusTokenStore
import com.untar.ultimusic.util.DisplaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Existe solo para darles un `Context` a [ApiCache], [GeniusTokenStore] y [DisplaySettings] antes de
 * que exista ninguna pantalla.
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
        ApiCache.init(this)
        GeniusTokenStore.init(this)
        DisplaySettings.init(this)
        // Poda por tiempo, una vez por arranque y fuera del hilo principal: abrir la base de datos y
        // borrar filas no puede retrasar el primer frame de la aplicación.
        appScope.launch { ApiCache.pruneExpired() }
    }
}
