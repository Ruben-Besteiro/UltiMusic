package com.untar.ultimusic.data.remote

import android.content.Context
import com.untar.ultimusic.data.db.cache.ApiCacheDao
import com.untar.ultimusic.data.db.cache.ApiCacheDatabase
import com.untar.ultimusic.data.db.cache.ApiCacheEntity
import java.util.concurrent.TimeUnit

/**
 * Caché de respuestas HTTP compartida por las tres APIs. La usa [HttpJson] antes de abrir ninguna
 * conexión, así que ni [ItunesApi] ni [GeniusApi] ni [LrcLibApi] tienen que saber que existe.
 *
 * **Para qué.** Sin ella, editar dos veces la misma canción son dos búsquedas idénticas contra
 * iTunes; girar la pantalla, otra; y en una biblioteca donde muchas canciones comparten artista o
 * álbum, las mismas consultas se repiten sin parar. Con muchos usuarios eso es lo que acerca los
 * límites de las tres APIs — y en Genius, donde el token va incrustado en el APK y la cuota es
 * común, lo acerca para todos a la vez.
 *
 * **El contexto.** Este es un `object` sin `Context`, igual que las tres APIs, así que se lo tiene
 * que dar alguien: lo hace [UltiMusicApp][com.untar.ultimusic.UltiMusicApp] en su `onCreate`, antes
 * de que exista ninguna pantalla. Se hace ahí y no en `MainActivity` justamente para que no pueda
 * darse el caso de "todavía no inicializada": si hiciera falta acordarse de llamarla desde cada
 * sitio, el día que se olvidara la caché se desactivaría en silencio, que es el mismo problema que
 * arregla [ApiRateLimitException] por el otro lado.
 */
object ApiCache {

    /**
     * Cuánto vale una respuesta guardada. Una semana: los metadatos de un disco publicado no cambian
     * de un día para otro, y las letras de lrclib.net tampoco (y si mejoran, tener la anterior una
     * semana más no le hace daño a nadie).
     */
    val DEFAULT_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)

    /** Tope de entradas guardadas. Al pasarse se tiran las más viejas; ver [trimIfNeeded]. */
    private const val MAX_ENTRIES = 500

    /** Cuántas se tiran de golpe al llegar al tope, para no podar en cada escritura. */
    private const val TRIM_BATCH = 100

    @Volatile
    private var dao: ApiCacheDao? = null

    /** La llama [UltiMusicApp][com.untar.ultimusic.UltiMusicApp]. Idempotente. */
    fun init(context: Context) {
        if (dao != null) return
        synchronized(this) {
            if (dao != null) return
            dao = ApiCacheDatabase.get(context).apiCacheDao()
        }
    }

    /**
     * El cuerpo guardado para [url] si sigue siendo joven, o null si no hay nada, si ha caducado o
     * si la caché no está lista todavía. Nunca lanza: fallar al leer una caché no puede tumbar una
     * búsqueda que sin ella funcionaría igual, solo más lenta.
     */
    fun get(url: String, ttlMs: Long = DEFAULT_TTL_MS): String? {
        val cacheDao = dao ?: return null
        return runCatching {
            val entry = cacheDao.find(url) ?: return null
            if (System.currentTimeMillis() - entry.fetchedAt > ttlMs) null else entry.body
        }.getOrNull()
    }

    /** Guarda el cuerpo de una respuesta 200. Como [get], nunca lanza. */
    fun put(url: String, body: String) {
        val cacheDao = dao ?: return
        runCatching {
            cacheDao.put(ApiCacheEntity(url = url, body = body, fetchedAt = System.currentTimeMillis()))
            trimIfNeeded(cacheDao)
        }
    }

    /** Poda por número de entradas. Va después de cada escritura, pero solo hace algo cuando de
     *  verdad se ha pasado del tope, y entonces tira [TRIM_BATCH] de golpe: así el `COUNT` que sí se
     *  ejecuta siempre es la única operación habitual, y sobre una tabla de centenares de filas es
     *  irrelevante. */
    private fun trimIfNeeded(dao: ApiCacheDao) {
        if (dao.count() <= MAX_ENTRIES) return
        dao.deleteOldest(TRIM_BATCH)
    }

    /** Tira lo caducado. La llama [UltiMusicApp][com.untar.ultimusic.UltiMusicApp] una vez por
     *  arranque, en segundo plano: es la poda por tiempo, complementaria a la de tamaño. */
    fun pruneExpired(ttlMs: Long = DEFAULT_TTL_MS) {
        val cacheDao = dao ?: return
        runCatching { cacheDao.deleteOlderThan(System.currentTimeMillis() - ttlMs) }
    }
}
