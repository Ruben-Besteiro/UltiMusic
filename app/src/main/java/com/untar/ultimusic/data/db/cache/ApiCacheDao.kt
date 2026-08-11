package com.untar.ultimusic.data.db.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Acceso a la caché de respuestas HTTP. Los métodos son **bloqueantes** (no `suspend`) a propósito:
 * se llaman desde dentro de los `httpGet`, que ya corren en `Dispatchers.IO` (ver [HttpJson]
 * [com.untar.ultimusic.data.remote.HttpJson]). Hacerlos `suspend` obligaría a volver a saltar de
 * hilo sin ganar nada.
 */
@Dao
interface ApiCacheDao {

    @Query("SELECT * FROM api_cache WHERE url = :url")
    fun find(url: String): ApiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: ApiCacheEntity)

    @Query("DELETE FROM api_cache WHERE fetchedAt < :threshold")
    fun deleteOlderThan(threshold: Long)

    @Query("SELECT COUNT(*) FROM api_cache")
    fun count(): Int

    /** Tira las [howMany] entradas más viejas. Es el recorte por tamaño, para que la caché no crezca
     *  sin fin en una biblioteca enorme donde se editen miles de canciones. */
    @Query("DELETE FROM api_cache WHERE url IN (SELECT url FROM api_cache ORDER BY fetchedAt ASC LIMIT :howMany)")
    fun deleteOldest(howMany: Int)

    @Query("DELETE FROM api_cache")
    fun clear()
}
