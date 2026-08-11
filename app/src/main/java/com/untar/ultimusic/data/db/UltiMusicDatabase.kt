package com.untar.ultimusic.data.db

import android.content.Context
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.untar.ultimusic.data.db.entities.AlbumArtistCrossRef
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.GreylistFolderEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef
import java.io.File

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
        SongProducerCrossRef::class,
        GreylistFolderEntity::class
    ],
    version = 12,
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
                instance ?: run {
                    restoreFromBackupIfNeeded(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        UltiMusicDatabase::class.java,
                        DB_NAME
                    )
                        // Durante el desarrollo aún no hay migraciones; si el esquema cambia, se recrea.
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
                }.also { instance = it }
            }

        /**
         * Si la base de datos interna no existe todavía (instalación nueva, o reinstalación tras
         * desinstalar) y hay una copia en `~/UltiMusic/databases/` -la que deja
         * [com.untar.ultimusic.data.LibraryRepository.exportDatabaseCopy] cada vez que la app pasa a
         * segundo plano-, la restaura ahí antes de que Room llegue a abrirla. Así las ediciones del
         * usuario (que solo viven en Room, nunca en MediaStore) sobreviven a un
         * desinstalar/reinstalar siempre que esa carpeta siga en el almacenamiento del dispositivo.
         *
         * Si la base de datos interna ya existe no se toca nada: esta copia es solo para el arranque
         * en frío de una instalación sin datos propios todavía.
         */
        private fun restoreFromBackupIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (dbFile.exists()) return

            val backupDir = File(Environment.getExternalStorageDirectory(), "UltiMusic/databases")
            val backupFile = File(backupDir, DB_NAME)
            if (!backupFile.exists()) return

            runCatching {
                dbFile.parentFile?.mkdirs()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val src = File(backupDir, DB_NAME + suffix)
                    if (src.exists()) {
                        src.copyTo(File(dbFile.path + suffix), overwrite = true)
                    }
                }
            }
        }
    }
}
