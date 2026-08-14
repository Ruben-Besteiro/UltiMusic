package com.untar.ultimusic.data.db

import android.content.Context
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.untar.ultimusic.data.db.entities.AlbumArtistCrossRef
import com.untar.ultimusic.data.db.entities.AlbumEntity
import com.untar.ultimusic.data.db.entities.ArtistEntity
import com.untar.ultimusic.data.db.entities.GreylistFolderEntity
import com.untar.ultimusic.data.db.entities.LibraryRootEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
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
        AlbumArtistCrossRef::class,
        SongProducerCrossRef::class,
        GreylistFolderEntity::class,
        LibraryRootEntity::class
    ],
    // v15: añade SongEntity.youtubeChannelId (canal del vídeo de cada canción) y
    // ArtistEntity.youtubeChannelId/youtubeChannelSubscriberCount (popularidad del artista, ver
    // ArtistEntity). A diferencia de casi todos los saltos de versión anteriores, esta SÍ tiene
    // migración escrita a mano (MIGRATION_14_15, ver Migrations.kt): son solo columnas nuevas, y
    // destruir la biblioteca entera por dos ALTER TABLE sería un coste innecesario para el usuario.
    // v16: añade la tabla `library_roots` (carpetas raíz adicionales de la fonoteca, ver
    // LibraryRootEntity), con migración escrita a mano (MIGRATION_15_16) por el mismo motivo. Se
    // siembra con Download/Music de fábrica (ver seedDefaultLibraryRoots en Migrations.kt).
    // v17: renombra ArtistEntity.youtubeChannelViewCount a youtubeChannelSubscriberCount (en
    // realidad guarda suscriptores, no visitas; ver MIGRATION_16_17 en Migrations.kt), con migración
    // escrita a mano por el mismo motivo que las dos anteriores: es una fonoteca ya poblada la que
    // se perdería si se dejara recrear a lo bruto.
    version = 17,
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
                        // MIGRATION_12_13, MIGRATION_14_15, MIGRATION_15_16 y MIGRATION_16_17
                        // conservan la biblioteca (ver Migrations.kt sobre por qué cada una se
                        // escribió a mano). El resto de saltos de versión anteriores nunca tuvieron
                        // migración, así que para esos (y para cualquier salto futuro sin migración)
                        // sigue habiendo fallbackToDestructiveMigration: el esquema se recrea vacío
                        // en vez de fallar al abrir la base de datos.
                        .addMigrations(MIGRATION_12_13, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        // Instalación nueva: la tabla `library_roots` se crea ya en v16 (con el
                        // esquema completo de golpe) y `onCreate` es el único momento en el que se
                        // sabe que es de verdad nueva, sin pasar nunca por MIGRATION_15_16 -que es
                        // quien siembra Download/Music en quien SÍ actualiza desde v15-.
                        .addCallback(object : RoomDatabase.Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                seedDefaultLibraryRoots(db)
                            }
                        })
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
