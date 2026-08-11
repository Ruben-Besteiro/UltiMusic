package com.untar.ultimusic.data.db.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una respuesta HTTP guardada tal cual vino, para no volver a pedirla. La clave es la URL entera
 * (con sus parámetros de búsqueda, página y tienda), así que dos búsquedas distintas —o dos páginas
 * de la misma— nunca se pisan y no hace falta inventar ninguna clave compuesta.
 *
 * Se guarda el **cuerpo sin parsear**, no los modelos ya construidos: así una sola tabla vale para
 * las tres APIs (iTunes, Genius, lrclib.net) sin saber nada de la forma de sus respuestas, y si algún
 * día cambia cómo se parsean, lo cacheado sigue sirviendo.
 *
 * Solo se guardan respuestas **200 OK**. Los fallos no se cachean a propósito: si se guardaran, un
 * corte de red de un segundo dejaría "no se ha podido buscar" congelado durante días y el botón de
 * reintentar no serviría de nada.
 */
@Entity(tableName = "api_cache")
data class ApiCacheEntity(
    @PrimaryKey val url: String,
    val body: String,
    /** Cuándo se pidió, para el TTL y para saber cuáles tirar primero al podar. */
    val fetchedAt: Long
)
