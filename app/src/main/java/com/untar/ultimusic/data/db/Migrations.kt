package com.untar.ultimusic.data.db

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.untar.ultimusic.R
import com.untar.ultimusic.model.SystemTagKey
import java.io.File

/**
 * v12 → v13: la relación canción↔álbum deja de ser N:N (tabla de cruce `song_album`) y pasa a ser
 * N:1 (columna `albumId` directa en `songs`, con `trackNumber`/`discNumber` al lado), ver
 * [com.untar.ultimusic.data.db.entities.SongEntity]. Es la única migración de verdad que tiene la
 * app: todas las subidas de versión anteriores se dejaban recrear con
 * `fallbackToDestructiveMigration` (ver [UltiMusicDatabase]), pero esta se escribe a mano porque
 * borrar la biblioteca de quien ya tenga la app instalada por un cambio de modelado interno sería
 * tirar ediciones, carátulas y enlaces de vídeo que no se pueden recuperar.
 *
 * `ALTER TABLE ... ADD COLUMN` admite una cláusula `REFERENCES` igual que en un `CREATE TABLE`
 * (SQLite la guarda tal cual en el esquema de la tabla), así que la columna `albumId` sale ya con su
 * clave foránea sin necesidad de recrear la tabla `songs` entera.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE songs ADD COLUMN albumId INTEGER REFERENCES albums(id) ON DELETE SET NULL"
        )
        db.execSQL("ALTER TABLE songs ADD COLUMN trackNumber INTEGER")
        db.execSQL("ALTER TABLE songs ADD COLUMN discNumber INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_albumId ON songs(albumId)")

        // Antes una canción podía estar en varios álbumes (N:N); ahora solo puede estar en uno, así
        // que si `song_album` trae más de una fila para la misma canción hay que elegir. Se queda con
        // el álbum de menor id: mismo criterio de "gana el más antiguo" que ya usa
        // LibraryDao.mergeDuplicateAlbums al fundir álbumes duplicados.
        db.execSQL(
            """
            UPDATE songs SET
                albumId = (
                    SELECT albumId FROM song_album
                    WHERE song_album.songId = songs.id ORDER BY albumId LIMIT 1
                ),
                trackNumber = (
                    SELECT trackNumber FROM song_album
                    WHERE song_album.songId = songs.id ORDER BY albumId LIMIT 1
                ),
                discNumber = (
                    SELECT discNumber FROM song_album
                    WHERE song_album.songId = songs.id ORDER BY albumId LIMIT 1
                )
            WHERE EXISTS (SELECT 1 FROM song_album WHERE song_album.songId = songs.id)
            """
        )

        db.execSQL("DROP TABLE song_album")
    }
}

/**
 * v14 → v15: tres columnas nuevas, ninguna tabla ni dato que reconstruir, así que a diferencia de
 * [MIGRATION_12_13] esta migración es solo `ALTER TABLE ... ADD COLUMN` sin ningún `UPDATE` detrás:
 *
 * - `songs.youtubeChannelId`: qué canal de YouTube subió el vídeo de cada canción (ver
 *   [com.untar.ultimusic.data.db.entities.SongEntity.youtubeChannelId]).
 * - `artists.youtubeChannelId`/`artists.youtubeChannelSubscriberCount`: la "popularidad" del artista
 *   —el canal que más se repite entre sus canciones donde es el principal, y el número de
 *   suscriptores de ese canal— (ver [com.untar.ultimusic.data.db.entities.ArtistEntity]).
 *
 * Se escribe a mano, a diferencia de casi todos los saltos de versión anteriores (que se dejan
 * recrear con `fallbackToDestructiveMigration`, ver [UltiMusicDatabase]): con solo columnas nuevas,
 * todas nullable y sin valor por defecto que inventar, destruir la biblioteca entera —ediciones,
 * carátulas, enlaces de vídeo— sería tirar datos irrecuperables por un cambio que no lo justifica.
 * Las tres quedan a NULL en las filas existentes hasta el primer refresco de YouTube tras actualizar.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN youtubeChannelId TEXT")
        db.execSQL("ALTER TABLE artists ADD COLUMN youtubeChannelId TEXT")
        db.execSQL("ALTER TABLE artists ADD COLUMN youtubeChannelSubscriberCount INTEGER")
    }
}

/**
 * v15 → v16: tabla nueva `library_roots` (ver
 * [com.untar.ultimusic.data.db.entities.LibraryRootEntity]), las carpetas raíz adicionales que el
 * usuario añade desde ajustes para que la fonoteca no se limite a `UltiMusic`. Igual que
 * [MIGRATION_14_15], se escribe a mano en vez de dejar `fallbackToDestructiveMigration`: es una tabla
 * nueva y vacía, no toca ninguna existente, así que no hay ningún motivo para tirar la biblioteca.
 *
 * De paso se siembra con `Download` y `Music` (ver [seedDefaultLibraryRoots]): quien actualiza desde
 * v15 pasa a tener también esas dos carpetas vigiladas, sin tener que añadirlas a mano.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS library_roots (path TEXT NOT NULL PRIMARY KEY)")
        seedDefaultLibraryRoots(db)
    }
}

/**
 * Carpetas raíz adicionales con las que arranca la fonoteca de fábrica, sin que el usuario tenga que
 * añadirlas a mano desde ajustes: las públicas de descargas y música del sistema (`Download` y
 * `Music`, junto a `UltiMusic`), los sitios más típicos donde ya cae música sola (el navegador, un
 * gestor de descargas, otra app que exporte a la carpeta de música estándar…).
 *
 * Se llama tanto desde [MIGRATION_15_16] (quien actualiza desde v15) como desde el `Callback` de
 * [UltiMusicDatabase] (instalación nueva, que crea la tabla ya en v16 y nunca pasa por la migración).
 * `INSERT OR IGNORE` la hace segura de repetir: si el usuario ya quitó una de las dos a mano, no
 * vuelve a aparecer sola en ninguna apertura futura, porque esto solo se ejecuta una vez por
 * instalación (al crear la BD o al migrar, nunca en una apertura normal).
 */
internal fun seedDefaultLibraryRoots(db: SupportSQLiteDatabase) {
    val downloads = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).absolutePath
    val music = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_MUSIC).absolutePath
    for (path in listOf(downloads, music)) {
        db.execSQL("INSERT OR IGNORE INTO library_roots (path) VALUES (?)", arrayOf<Any>(path))
    }
}

/**
 * v16 → v17: `artists.youtubeChannelViewCount` pasa a llamarse `youtubeChannelSubscriberCount`: en
 * realidad guarda los SUSCRIPTORES del canal (ver [com.untar.ultimusic.data.db.entities.ArtistEntity]
 * y [com.untar.ultimusic.data.remote.YouTubeStatsApi.channelStats]), no sus visitas — un nombre que
 * arrastraba desde [MIGRATION_14_15] sin que nada obligara a corregirlo hasta ahora.
 *
 * SQLite no tiene `ALTER TABLE ... RENAME COLUMN` hasta la 3.25 (2018), y minSdk 24 todavía puede
 * llevar una más vieja, así que en vez de un simple ALTER se recrea la tabla entera con el nombre de
 * columna correcto y se copian los datos: es el patrón que la propia documentación de Room recomienda
 * para cualquier cambio de columna que no sea añadir una nueva al final.
 *
 * `DROP TABLE` no dispara los `ON DELETE CASCADE` que `song_artist`/`album_artist` declaran hacia
 * `artists` (eso solo pasa con `DELETE`/`UPDATE`, nunca con `DROP`), así que esas filas quedan
 * intactas y vuelven a apuntar a datos válidos en cuanto la tabla nueva ocupa el mismo nombre con los
 * mismos `id`.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE artists_new (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                tagName TEXT NOT NULL,
                imageName TEXT,
                youtubeChannelId TEXT,
                youtubeChannelSubscriberCount INTEGER
            )
            """
        )
        db.execSQL(
            """
            INSERT INTO artists_new (id, name, tagName, imageName, youtubeChannelId, youtubeChannelSubscriberCount)
            SELECT id, name, tagName, imageName, youtubeChannelId, youtubeChannelViewCount FROM artists
            """
        )
        db.execSQL("DROP TABLE artists")
        db.execSQL("ALTER TABLE artists_new RENAME TO artists")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artists_tagName ON artists(tagName)")
    }
}

/**
 * v17 → v18: sistema de Etiquetas (ver [com.untar.ultimusic.data.db.entities.TagEntity]/
 * [com.untar.ultimusic.data.db.entities.SongTagCrossRef]) más `songs.dateAdded`, la fecha de
 * creación del archivo en disco que necesita la etiqueta predefinida "Descargadas recientemente".
 *
 * A diferencia de [MIGRATION_12_13]...[MIGRATION_16_17] (`val` de nivel de fichero, construidos sin
 * `Context`), esta se declara como FUNCIÓN que recibe el `Context`: sembrar las 4 etiquetas
 * predefinidas necesita leer sus nombres y colores de `strings.xml`/`colors.xml` (ver
 * [seedDefaultTags]), y `Migration.migrate(db: SupportSQLiteDatabase)` no recibe ningún `Context`
 * propio. `UltiMusicDatabase.get(context)` sí lo tiene, así que se cierra sobre él al construir la
 * migración en vez de leerlo desde dentro de `migrate`.
 *
 * `dateAdded` se rellena para las filas YA existentes con `File(filePath).lastModified()` (ver
 * [backfillDateAdded]): mismo dato que se le pone a partir de ahora a cualquier canción nueva desde
 * `ScannedSong.toEntity` en `LibraryDao.kt`, así que las canciones de antes de esta versión también
 * entran en igualdad de condiciones en "Descargadas recientemente" desde el primer arranque.
 */
fun migration17To18(context: Context): Migration = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                colorArgb INTEGER NOT NULL,
                systemKey TEXT,
                sortOrder INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_systemKey ON tags(systemKey)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_tag (
                songId INTEGER NOT NULL,
                tagId INTEGER NOT NULL,
                PRIMARY KEY(songId, tagId),
                FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE,
                FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_song_tag_tagId ON song_tag(tagId)")

        backfillDateAdded(db)
        seedDefaultTags(db, context)
    }
}

/**
 * Rellena `dateAdded` para las canciones que ya existían antes de esta versión, fila a fila, con la
 * fecha de modificación del archivo en disco (`stat()`, sin abrir el audio). Para una fonoteca
 * personal (cientos o pocos miles de canciones) es trivial; si algún archivo ya no es legible
 * (`runCatching`), se queda a `0` en vez de romper la migración entera por una canción suelta.
 */
private fun backfillDateAdded(db: SupportSQLiteDatabase) {
    db.query("SELECT id, filePath FROM songs").use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow("id")
        val pathIndex = cursor.getColumnIndexOrThrow("filePath")
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            val lastModified = runCatching { File(cursor.getString(pathIndex)).lastModified() }.getOrDefault(0L)
            db.execSQL("UPDATE songs SET dateAdded = ? WHERE id = ?", arrayOf<Any>(lastModified, id))
        }
    }
}

/**
 * Siembra las etiquetas predefinidas, en el mismo orden del enunciado del feature (Favoritos,
 * Descargadas recientemente, En ninguna lista, Sin etiquetas personalizadas, Vídeo sincronizado). Se
 * llama tanto desde [migration17To18] (quien actualiza desde v17) como desde el `Callback` de
 * [UltiMusicDatabase] (instalación nueva, que crea la tabla `tags` ya en v18 y nunca pasa por ninguna
 * migración) — mismo patrón que [seedDefaultLibraryRoots]. `INSERT OR IGNORE` sobre `systemKey` la
 * hace segura de repetir sin duplicar nada.
 *
 * Hubo una fila más, "Debug" (ver [SystemTagKey]), sembrada por [migration18To19] y retirada por
 * [migration19To20] en cuanto dejó de hacer falta: por eso ya no aparece aquí, aunque instalaciones
 * viejas pasaran por sembrarla. "Vídeo sincronizado" es la más nueva (ver [migration20To21]): a
 * diferencia de las 3 calculadas, SÍ hace falta sembrarla aquí para una instalación nueva -usa
 * membresía real, como Favoritos- pero no hace falta backfill ninguno, porque una instalación nueva
 * todavía no tiene ninguna canción con desplazamiento de vídeo guardado.
 *
 * Nombres y colores salen de `strings.xml`/`colors.xml` (`R.string.tag_*_name`/`R.color.um_tag_*`),
 * NO de literales sueltos aquí: son datos que el desarrollador edita en el sitio de siempre. El
 * único motivo de resolverlos con `Context` en vez de leer el recurso directamente desde `db.execSQL`
 * es que SQL no sabe de recursos de Android.
 */
internal fun seedDefaultTags(db: SupportSQLiteDatabase, context: Context) {
    val rows = listOf(
        Triple(SystemTagKey.FAVORITES, R.string.tag_favorites_name, R.color.um_tag_favorites),
        Triple(SystemTagKey.RECENTLY_ADDED, R.string.tag_recently_added_name, R.color.um_tag_recently_added),
        Triple(SystemTagKey.NOT_IN_PLAYLIST, R.string.tag_not_in_playlist_name, R.color.um_tag_not_in_playlist),
        Triple(SystemTagKey.NO_CUSTOM_TAGS, R.string.tag_no_custom_tags_name, R.color.um_tag_no_custom_tags),
        Triple(SystemTagKey.SYNCED_VIDEO, R.string.tag_synced_video_name, R.color.um_tag_synced_video)
    )
    rows.forEachIndexed { sortOrder, (systemKey, nameRes, colorRes) ->
        db.execSQL(
            "INSERT OR IGNORE INTO tags (name, colorArgb, systemKey, sortOrder) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(
                context.getString(nameRes),
                ContextCompat.getColor(context, colorRes),
                systemKey.name,
                sortOrder
            )
        )
    }
}

/**
 * v18 → v19: sembraba la etiqueta predefinida "Debug" (`SystemTagKey.DEBUG`, ya retirado del enum),
 * pensada para poder probar el flujo completo de añadir/quitar etiquetas de una canción antes de que
 * existieran etiquetas personalizadas de verdad. Se deja tal cual (no se borra ni se reescribe: una
 * migración ya aplicada no cambia) porque [migration19To20] limpia su resultado para quien pasara por
 * aquí; sin cambio de esquema.
 */
fun migration18To19(context: Context): Migration = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        seedDefaultTags(db, context)
    }
}

/**
 * v19 → v20: retira la etiqueta predefinida "Debug" (ver [SystemTagKey]), que ya cumplió su propósito
 * de probar el flujo de añadir/quitar etiquetas antes de que existieran las personalizadas de verdad
 * (ver [TagEditorDialogFragment][com.untar.ultimusic.ui.library.TagEditorDialogFragment]) y ya no
 * hace falta. Sin cambio de esquema: solo borra la fila `systemKey = 'DEBUG'` de `tags` -si existe,
 * de una instalación que pasara por [migration18To19] o naciera ya en v19- y, en cascada, su
 * membresía en `song_tag` (`ON DELETE CASCADE`, ver [com.untar.ultimusic.data.db.entities.SongTagCrossRef]).
 * Una instalación nueva a partir de aquí nunca llega a tener esa fila: [seedDefaultTags] ya no la
 * siembra. No necesita `Context` (no lee ningún recurso), así que es un `val` de nivel de fichero
 * como [MIGRATION_16_17], no una función.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM tags WHERE systemKey = 'DEBUG'")
    }
}

/**
 * v20 → v21: sin cambio de esquema — siembra la 5ª etiqueta predefinida "Vídeo sincronizado" (ver
 * [SystemTagKey.SYNCED_VIDEO]), que a partir de ahora [com.untar.ultimusic.data.LibraryRepository]
 * mantiene sola en `song_tag` según [com.untar.ultimusic.model.Song.videoOffsetMs] (ver
 * `LibraryRepository.syncSyncedVideoTag`).
 *
 * A diferencia de Favoritos -que nace siempre vacía, nadie tiene canciones favoritas antes de que
 * exista el botón para marcarlas-, aquí YA puede haber canciones con un desplazamiento de vídeo
 * distinto de 0 guardado desde antes de esta versión (el editor de metadatos y el iPod llevan tiempo
 * dejando ajustarlo). Sin este backfill, esas canciones se quedarían sin la etiqueta hasta que
 * alguien les tocara el desplazamiento a mano una vez más — así que, tras sembrar la fila, se rellena
 * `song_tag` para todas las que ya cumplan la condición.
 */
fun migration20To21(context: Context): Migration = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT OR IGNORE INTO tags (name, colorArgb, systemKey, sortOrder) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(
                context.getString(R.string.tag_synced_video_name),
                ContextCompat.getColor(context, R.color.um_tag_synced_video),
                SystemTagKey.SYNCED_VIDEO.name,
                4
            )
        )
        db.query("SELECT id FROM tags WHERE systemKey = ?", arrayOf(SystemTagKey.SYNCED_VIDEO.name)).use { cursor ->
            if (cursor.moveToFirst()) {
                val tagId = cursor.getLong(0)
                db.execSQL(
                    "INSERT OR IGNORE INTO song_tag (songId, tagId) SELECT id, ? FROM songs WHERE videoOffsetMs != 0",
                    arrayOf<Any>(tagId)
                )
            }
        }
    }
}
