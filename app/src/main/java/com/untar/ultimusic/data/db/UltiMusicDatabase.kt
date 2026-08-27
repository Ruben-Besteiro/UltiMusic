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
import com.untar.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef
import com.untar.ultimusic.data.db.entities.SongTagCrossRef
import com.untar.ultimusic.data.db.entities.TagEntity
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
        GreylistFolderEntity::class,
        LibraryRootEntity::class,
        TagEntity::class,
        SongTagCrossRef::class
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
    // v18: sistema de Etiquetas (TagEntity/SongTagCrossRef, tablas `tags`/`song_tag`) más
    // SongEntity.dateAdded (fecha de creación del archivo, para la etiqueta predefinida "Descargadas
    // recientemente"), con migración escrita a mano (migration17To18 en Migrations.kt) por el mismo
    // motivo que las anteriores. Se siembra con las 4 etiquetas predefinidas (Favoritos, Descargadas
    // recientemente, En ninguna lista, Sin etiquetas personalizadas), ver seedDefaultTags.
    // v19: sin cambio de esquema — solo siembra la 5ª etiqueta predefinida "Debug" (migration18To19
    // en Migrations.kt, reutiliza seedDefaultTags), pensada para probar el flujo de añadir/quitar
    // etiquetas de una canción antes de que existan etiquetas personalizadas de verdad.
    // v20: sin cambio de esquema — retira esa misma etiqueta "Debug" (MIGRATION_19_20 en
    // Migrations.kt), ya innecesaria ahora que existen etiquetas personalizadas de verdad (ver
    // TagEditorDialogFragment). SystemTagKey.DEBUG desaparece del enum; seedDefaultTags ya no la
    // siembra para instalaciones nuevas.
    // v21: sin cambio de esquema — siembra la 5ª etiqueta predefinida "Vídeo sincronizado"
    // (migration20To21 en Migrations.kt, reutiliza seedDefaultTags para instalación nueva). A
    // diferencia de "Debug", esta usa membresía real de verdad (como Favoritos): LibraryRepository
    // la añade/quita sola de `song_tag` según Song.videoOffsetMs (ver
    // LibraryRepository.syncSyncedVideoTag), y el usuario también puede tocarla a mano desde la
    // ficha de la etiqueta (botón "+"/X de cada fila, ver CollectionDetailDialogFragment). La
    // migración hace además un backfill: las canciones que ya tuvieran un desplazamiento guardado
    // desde antes de esta versión entran en la etiqueta de una vez, sin esperar a que se les vuelva
    // a tocar el desplazamiento.
    // v22: retira SongEntity.ogAlbum ("Álbum original" ya no existe en el editor de metadatos, ni a
    // mano ni por autorrelleno de Genius), con migración escrita a mano (MIGRATION_21_22 en
    // Migrations.kt) por el mismo motivo que las anteriores: recrea la tabla `songs` sin esa columna
    // y copia el resto de datos tal cual, sin tocar ediciones, carátulas ni enlaces de vídeo.
    // v23: añade TagEntity.isAutoAssigned para las etiquetas de IDIOMA (una por idioma detectado en
    // la letra, ver LibraryRepository.syncLanguageTag), con migración escrita a mano (migration22To23
    // en Migrations.kt) por el mismo motivo que las anteriores. A diferencia de las 5 predefinidas, no
    // hay un valor fijo de SystemTagKey por idioma -se crean sobre la marcha, con el nombre del
    // idioma y blancas (ver R.color.um_tag_language)-, así que necesitan su propio candado en vez de
    // colgar de `systemKey`. La migración hace además un backfill: las canciones que ya tuvieran
    // `language` relleno desde antes de esta versión entran en su etiqueta de idioma de una vez, sin
    // esperar a que se les vuelva a tocar la letra.
    // v24: sin cambio de esquema — corrige el `name` ya sembrado de la etiqueta predefinida
    // "Descargada recientemente" (migration23To24 en Migrations.kt), que se había quedado con el
    // texto viejo ("Canciones descargadas recientemente") de cuando se sembró por primera vez: pone
    // al día `tags.name` con el texto actual de R.string.tag_recently_added_name.
    // v25: sin cambio de esquema — vuelve a sembrar `library_roots` con Download/Music (MIGRATION_24_25
    // en Migrations.kt) si la tabla está del todo vacía: una instalación "limpia" puede en realidad
    // restaurar la copia de ~/UltiMusic/databases/ (ver restoreFromBackupIfNeeded más abajo), que se
    // salta tanto el Callback.onCreate como MIGRATION_15_16, así que si esa copia tenía la tabla vacía
    // -por ejemplo, justo después de quitar las dos carpetas a mano y que la app exportara esa foto al
    // pasar a segundo plano- Download/Music no volvían a aparecer solos.
    // v26: sin cambio de esquema — recolorea 4 de las etiquetas predefinidas ("En ninguna lista" y
    // "Sin etiquetas personalizadas" a un gris casi negro compartido, "Descargada recientemente" y
    // "Vídeo sincronizado" a blanco) y siembra la 6ª, "Remix / Cover" (migration25To26 en
    // Migrations.kt, reutiliza seedDefaultTags para instalación nueva). Igual que "Vídeo
    // sincronizado", usa membresía real (systemKey == SystemTagKey.REMIX_COVER, fila en `song_tag`,
    // no calculada): LibraryRepository.syncRemixCoverTag la mantiene sola según Song.ogTitle ("Título
    // original" del editor de metadatos), y el usuario también puede tocarla a mano desde la ficha de
    // la etiqueta. Mismo backfill que "Vídeo sincronizado" en v21: las canciones que ya tuvieran
    // "Título original" relleno desde antes de esta versión entran en la etiqueta de una vez.
    // v27: la relación canción↔álbum vuelve a ser N:M (tabla de cruce `song_album`, ver
    // SongAlbumCrossRef), con MIGRATION_26_27 (Migrations.kt) escrita a mano por el mismo motivo que
    // todas las de arriba: es justo la migración inversa de MIGRATION_12_13 (que en su día la había
    // simplificado a N:1, columnas `albumId`/`trackNumber`/`discNumber` directas en `songs`), y ahora
    // vuelve a hacer falta poder catalogar una canción en más de un álbum (un recopilatorio y el
    // álbum original, por ejemplo) desde el editor de metadatos ("+ Añadir otro álbum", ver
    // MetadataEditorDialogFragment). Las tres columnas viejas se van de `songs` (había que recrear la
    // tabla, SQLite no tiene DROP COLUMN en minSdk 24) y sus valores migran a `song_album` como
    // álbum principal (`position = 0`) de cada canción que ya tuviera uno.
    version = 27,
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
                        // MIGRATION_12_13, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        // migration17To18, migration18To19, MIGRATION_19_20, migration20To21,
                        // MIGRATION_21_22, migration22To23, migration23To24, MIGRATION_24_25,
                        // migration25To26 y MIGRATION_26_27 conservan la biblioteca (ver Migrations.kt
                        // sobre por qué cada una se escribió a mano). Las que no son `val` necesitan el
                        // `context` -para sembrar las etiquetas predefinidas (nombres/colores salen de
                        // strings.xml/colors.xml, ver seedDefaultTags), en migration22To23 solo el
                        // color blanco de las etiquetas de idioma (R.color.um_tag_language), en
                        // migration23To24 el texto vigente de R.string.tag_recently_added_name, o en
                        // migration25To26 los colores vigentes de 4 etiquetas más el sembrado de
                        // "Remix / Cover"-, así que se construyen aquí, donde sí hay uno a mano;
                        // MIGRATION_19_20, MIGRATION_21_22, MIGRATION_24_25 y MIGRATION_26_27 son `val`
                        // porque ninguna lee ningún recurso. El resto de saltos de versión anteriores
                        // nunca tuvieron migración, así que para esos (y para cualquier salto futuro
                        // sin migración) sigue habiendo fallbackToDestructiveMigration: el esquema se
                        // recrea vacío en vez de fallar al abrir la base de datos.
                        .addMigrations(
                            MIGRATION_12_13,
                            MIGRATION_14_15,
                            MIGRATION_15_16,
                            MIGRATION_16_17,
                            migration17To18(context.applicationContext),
                            migration18To19(context.applicationContext),
                            MIGRATION_19_20,
                            migration20To21(context.applicationContext),
                            MIGRATION_21_22,
                            migration22To23(context.applicationContext),
                            migration23To24(context.applicationContext),
                            MIGRATION_24_25,
                            migration25To26(context.applicationContext),
                            MIGRATION_26_27
                        )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        // Instalación nueva: la tabla `library_roots` se crea ya en v16 (con el
                        // esquema completo de golpe) y `onCreate` es el único momento en el que se
                        // sabe que es de verdad nueva, sin pasar nunca por MIGRATION_15_16 -que es
                        // quien siembra Download/Music en quien SÍ actualiza desde v15-. Lo mismo
                        // aplica a `tags` (creada ya en v18) y seedDefaultTags/migration17To18 —
                        // instalación nueva siembra directamente las 6 etiquetas predefinidas
                        // vigentes (sin "Debug"), sin pasar por migration18To19, MIGRATION_19_20,
                        // migration20To21 ni migration25To26.
                        .addCallback(object : RoomDatabase.Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                seedDefaultLibraryRoots(db)
                                seedDefaultTags(db, context.applicationContext)
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
