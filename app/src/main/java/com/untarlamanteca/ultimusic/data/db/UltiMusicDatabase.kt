package com.untarlamanteca.ultimusic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.untarlamanteca.ultimusic.data.db.entities.AlbumArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.ProducerEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongArtistCrossRef
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongProducerCrossRef

/**
 * Base de datos de la biblioteca de UltiMusic. Vive en el almacenamiento interno de la app
 * (`/data/data/<paquete>/databases/ultimusic.db`) y es la ÚNICA fuente de verdad de los modelos.
 */

@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        ProducerEntity::class,
        SongArtistCrossRef::class,
        SongAlbumCrossRef::class,
        AlbumArtistCrossRef::class,
        SongProducerCrossRef::class
    ],
    // v2: el productor deja de ser una columna de texto de la canción y pasa a tener tabla propia.
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class UltiMusicDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {
        const val DB_NAME = "ultimusic.db"

        @Volatile
        private var instance: UltiMusicDatabase? = null

        fun get(context: Context): UltiMusicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UltiMusicDatabase::class.java,
                    DB_NAME
                )
                    // Durante el desarrollo aún no hay migraciones; si el esquema cambia, se recrea.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
