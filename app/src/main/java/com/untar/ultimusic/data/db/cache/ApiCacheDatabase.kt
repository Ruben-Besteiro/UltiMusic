package com.untar.ultimusic.data.db.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos de la caché de respuestas HTTP. Es **una base de datos aparte**, en su propio
 * archivo, y no una tabla más de
 * [UltiMusicDatabase][com.untar.ultimusic.data.db.UltiMusicDatabase]. Tres motivos, el primero de
 * ellos serio:
 *
 * 1. La de la biblioteca está construida con `fallbackToDestructiveMigration(dropAllTables = true)`:
 *    cualquier cambio de esquema **borra los datos del usuario**, que son justo los que no se pueden
 *    recuperar de ningún sitio (todo lo que edita a mano vive solo ahí, nunca en MediaStore). Meter
 *    aquí una tabla obligaría a subirle la versión y a vaciar la biblioteca de todo el mundo por
 *    añadir una caché. Inaceptable.
 * 2. Aquí ese mismo `fallbackToDestructiveMigration` es justo lo que se quiere: son datos
 *    desechables por definición, y si el esquema cambia, tirarlos no cuesta nada.
 * 3. `LibraryRepository.exportDatabaseCopy()` copia la base de datos de la biblioteca a
 *    `~/UltiMusic/databases` cada vez que la app pasa a segundo plano. La caché no tiene por qué ir
 *    en esa copia, ni engordarla.
 */
@Database(entities = [ApiCacheEntity::class], version = 1, exportSchema = false)
abstract class ApiCacheDatabase : RoomDatabase() {

    abstract fun apiCacheDao(): ApiCacheDao

    companion object {
        const val DB_NAME = "api_cache.db"

        @Volatile
        private var instance: ApiCacheDatabase? = null

        fun get(context: Context): ApiCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ApiCacheDatabase::class.java,
                    DB_NAME
                )
                    // Ver el punto 2 de la cabecera: aquí tirar los datos es gratis.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
