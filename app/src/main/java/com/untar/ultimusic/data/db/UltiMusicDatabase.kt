package com.untar.ultimusic.data.db

import android.content.Context
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
import com.untar.ultimusic.data.db.entities.PlayEventEntity
import com.untar.ultimusic.data.db.entities.ProducerEntity
import com.untar.ultimusic.data.db.entities.SongAlbumCrossRef
import com.untar.ultimusic.data.db.entities.SongArtistCrossRef
import com.untar.ultimusic.data.db.entities.SongEntity
import com.untar.ultimusic.data.db.entities.SongProducerCrossRef
import com.untar.ultimusic.data.db.entities.SongTagCrossRef
import com.untar.ultimusic.data.db.entities.TagEntity
import com.untar.ultimusic.util.SafStorage
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
        SongTagCrossRef::class,
        PlayEventEntity::class
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
    // v28: sin cambio de esquema — retira la etiqueta predefinida "Favoritos" (MIGRATION_27_28 en
    // Migrations.kt), por decisión de producto: SystemTagKey.FAVORITES desaparece del enum y
    // seedDefaultTags ya no la siembra. El DELETE se lleva la fila de `tags` y, en cascada, la
    // membresía (`song_tag`) que hubiera de esa etiqueta.
    // v29: añade la tabla `play_events` (PlayEventEntity), el único dato que guarda UltiMusic
    // Recount: una fila por escucha que haya llegado al 50% de la canción, con su id, cuándo empezó
    // y cuánto sonó de verdad. Todo lo que enseña el Recount (top de canciones/artistas/géneros,
    // tiempos, tarta) se DERIVA de esas filas al mirarlo, cruzándolas con la fonoteca del momento,
    // así que una edición de metadatos reescribe también los Recounts de años pasados y una canción
    // borrada manda sus escuchas al cubo "Canciones borradas". Con migración escrita a mano
    // (MIGRATION_28_29) por el mismo motivo que todas las anteriores. Dos rarezas deliberadas de esa
    // tabla, explicadas en PlayEventEntity: no tiene clave foránea a `songs` (una cascada borraría
    // justo lo que hace falta conservar) y guarda el año desnormalizado (calcularlo con `'localtime'`
    // al consultar reasignaría escuchas de fin de año si el usuario cambia de zona horaria).
    // v30: sin cambio de esquema — migración de MANAGE_EXTERNAL_STORAGE a Storage Access Framework
    // (MIGRATION_29_30 en Migrations.kt, ver SafStorage): vacía `library_roots`/`greylist_folders`
    // (sus rutas absolutas ya no sirven de nada) sin tocar `songs`, cuyos `filePath` viejos se
    // arreglan en caliente al re-conceder carpetas, no en la migración. El `Callback.onCreate` de más
    // abajo deja de sembrar `library_roots` para una instalación nueva por el mismo motivo: con SAF no
    // hay forma de "sembrar" un permiso de árbol sin que el usuario pase por el selector del sistema.
    version = 30,
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
                        // migration25To26, MIGRATION_26_27, MIGRATION_27_28 y MIGRATION_29_30 conservan
                        // la biblioteca (ver Migrations.kt sobre por qué cada una se escribió a mano).
                        // Las que no son `val` necesitan el `context` -para sembrar las etiquetas
                        // predefinidas (nombres/colores salen de strings.xml/colors.xml, ver
                        // seedDefaultTags), en migration22To23 solo el color blanco de las etiquetas de
                        // idioma (R.color.um_tag_language), en migration23To24 el texto vigente de
                        // R.string.tag_recently_added_name, o en migration25To26 los colores vigentes
                        // de 4 etiquetas más el sembrado de "Remix / Cover"-, así que se construyen
                        // aquí, donde sí hay uno a mano; MIGRATION_19_20, MIGRATION_21_22,
                        // MIGRATION_24_25, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29 y
                        // MIGRATION_29_30 son `val` porque ninguna lee ningún recurso. El resto de
                        // saltos de versión anteriores nunca tuvieron migración, así que para esos (y
                        // para cualquier salto futuro sin migración) sigue habiendo
                        // fallbackToDestructiveMigration: el esquema se recrea vacío en vez de fallar
                        // al abrir la base de datos.
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
                            MIGRATION_26_27,
                            MIGRATION_27_28,
                            MIGRATION_28_29,
                            MIGRATION_29_30
                        )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        // Instalación nueva: `onCreate` es el único momento en el que se sabe que es
                        // de verdad nueva. `tags` (creada ya en v18) se siembra directamente con las 5
                        // etiquetas predefinidas vigentes (sin "Debug" ni "Favoritos"), sin pasar por
                        // migration18To19, MIGRATION_19_20, migration20To21, migration25To26 ni
                        // MIGRATION_27_28. `library_roots` YA NO se siembra aquí (a diferencia de antes
                        // de v30): con SAF no hay forma de conceder un permiso de árbol sin que el
                        // usuario pase por el selector del sistema, así que una instalación nueva
                        // arranca con esa tabla vacía y el usuario añade Download/Music/lo que quiera
                        // desde ajustes si le interesa (ver SettingsDialogFragment).
                        .addCallback(object : RoomDatabase.Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                seedDefaultTags(db, context.applicationContext)
                            }
                        })
                        .build()
                }.also { instance = it }
            }

        /**
         * Si la base de datos interna no existe todavía (instalación nueva, o reinstalación tras
         * desinstalar) y hay una copia en `UltiMusic/databases/` -la que deja
         * [com.untar.ultimusic.data.LibraryRepository.exportDatabaseCopy] cada vez que la app pasa a
         * segundo plano-, la restaura ahí antes de que Room llegue a abrirla. Así las ediciones del
         * usuario (que solo viven en Room, nunca en MediaStore) sobreviven a un desinstalar/reinstalar
         * SIEMPRE QUE el usuario ya haya vuelto a conceder la carpeta `UltiMusic` con el selector del
         * sistema en este arranque (ver [SafStorage]): a diferencia de antes de la migración a SAF, un
         * permiso de árbol normalmente NO sobrevive a un desinstalar/reinstalar (Android lo revoca con
         * la app), así que en ese caso concreto esta restauración automática no puede dispararse hasta
         * que el usuario haya concedido de nuevo `UltiMusic` -es un cambio de comportamiento esperado
         * de la migración, no un fallo-.
         *
         * Solo necesita la URI de `UltiMusic` (persistida en `SharedPreferences`, ver
         * [SafStorage.ultiMusicTreeUri]), no ninguna carpeta raíz adicional: es lo único que hace falta
         * ANTES de que exista Room.
         *
         * Si la base de datos interna ya existe no se toca nada: esta copia es solo para el arranque
         * en frío de una instalación sin datos propios todavía.
         */
        private fun restoreFromBackupIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (dbFile.exists()) return

            SafStorage.refreshRegistry(context)
            val ultiMusicRoot = SafStorage.ultiMusicDocPath(context) ?: return
            val backupDocPath = "$ultiMusicRoot/databases"
            if (!SafStorage.exists(context, "$backupDocPath/$DB_NAME")) return

            runCatching {
                dbFile.parentFile?.mkdirs()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val srcDocPath = "$backupDocPath/$DB_NAME$suffix"
                    if (!SafStorage.exists(context, srcDocPath)) continue
                    SafStorage.openInputStream(context, srcDocPath)?.use { input ->
                        File(dbFile.path + suffix).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
