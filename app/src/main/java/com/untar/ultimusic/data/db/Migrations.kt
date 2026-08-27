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
 * Descargadas recientemente, En ninguna lista, Sin etiquetas personalizadas, Vídeo sincronizado,
 * Remix / Cover). Se llama tanto desde [migration17To18] (quien actualiza desde v17) como desde el
 * `Callback` de [UltiMusicDatabase] (instalación nueva, que crea la tabla `tags` ya en v18 y nunca
 * pasa por ninguna migración) — mismo patrón que [seedDefaultLibraryRoots]. `INSERT OR IGNORE` sobre
 * `systemKey` la hace segura de repetir sin duplicar nada.
 *
 * Hubo una fila más, "Debug" (ver [SystemTagKey]), sembrada por [migration18To19] y retirada por
 * [migration19To20] en cuanto dejó de hacer falta: por eso ya no aparece aquí, aunque instalaciones
 * viejas pasaran por sembrarla. "Vídeo sincronizado" (ver [migration20To21]) y "Remix / Cover" (ver
 * [migration25To26]) no son de las calculadas -SÍ hace falta sembrarlas aquí para una instalación
 * nueva, usan membresía real como Favoritos- pero no hace falta backfill ninguno para ninguna de las
 * dos, porque una instalación nueva todavía no tiene ninguna canción con desplazamiento de vídeo ni
 * título original guardados.
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
        Triple(SystemTagKey.SYNCED_VIDEO, R.string.tag_synced_video_name, R.color.um_tag_synced_video),
        Triple(SystemTagKey.REMIX_COVER, R.string.tag_remix_cover_name, R.color.um_tag_remix_cover)
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

/**
 * v21 → v22: retira `songs.ogAlbum` (ver [com.untar.ultimusic.data.db.entities.SongEntity]): el
 * editor de metadatos ya no ofrece "Álbum original", ni a mano ni por autorrelleno de Genius (se
 * equivocaba con demasiada frecuencia y el dato por sí solo no compensaba pedirlo). `ogTitle`,
 * `ogArtist` y `ogYear` se quedan tal cual, solo se va la columna del álbum.
 *
 * SQLite no tiene `ALTER TABLE ... DROP COLUMN` en las versiones que puede llevar un dispositivo con
 * minSdk 24, así que se recrea la tabla entera sin esa columna y se copian los datos —mismo patrón
 * que [MIGRATION_16_17] para `artists`—, en vez de dejar `fallbackToDestructiveMigration`: perder
 * ediciones, carátulas y enlaces de vídeo de toda la fonoteca por una sola columna que ya nadie usa
 * sería un coste absurdo para el usuario.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE songs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                filePath TEXT NOT NULL,
                title TEXT NOT NULL,
                duration INTEGER NOT NULL,
                year INTEGER,
                genres TEXT NOT NULL,
                lyrics TEXT,
                language TEXT,
                imageName TEXT,
                comment TEXT,
                videoUrl TEXT,
                videoThumbnailName TEXT,
                videoOffsetMs INTEGER NOT NULL DEFAULT 0,
                lyricsOffsetMs INTEGER NOT NULL DEFAULT 0,
                hiddenByGreylist INTEGER NOT NULL DEFAULT 0,
                youtubeViewCount INTEGER,
                youtubeChannelId TEXT,
                albumId INTEGER REFERENCES albums(id) ON DELETE SET NULL,
                trackNumber INTEGER,
                discNumber INTEGER,
                ogTitle TEXT,
                ogArtist TEXT,
                ogYear INTEGER,
                dateAdded INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL(
            """
            INSERT INTO songs_new (
                id, filePath, title, duration, year, genres, lyrics, language, imageName, comment,
                videoUrl, videoThumbnailName, videoOffsetMs, lyricsOffsetMs, hiddenByGreylist,
                youtubeViewCount, youtubeChannelId, albumId, trackNumber, discNumber, ogTitle,
                ogArtist, ogYear, dateAdded
            )
            SELECT
                id, filePath, title, duration, year, genres, lyrics, language, imageName, comment,
                videoUrl, videoThumbnailName, videoOffsetMs, lyricsOffsetMs, hiddenByGreylist,
                youtubeViewCount, youtubeChannelId, albumId, trackNumber, discNumber, ogTitle,
                ogArtist, ogYear, dateAdded
            FROM songs
            """
        )
        db.execSQL("DROP TABLE songs")
        db.execSQL("ALTER TABLE songs_new RENAME TO songs")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_songs_filePath ON songs(filePath)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_albumId ON songs(albumId)")
    }
}

/**
 * v22 → v23: añade [com.untar.ultimusic.data.db.entities.TagEntity.isAutoAssigned] (`INTEGER NOT NULL
 * DEFAULT 0`) para las etiquetas de IDIOMA (ver
 * [com.untar.ultimusic.data.LibraryRepository.syncLanguageTag]): se crean solas con el nombre del
 * idioma que detecta la letra y tienen las mismas restricciones que una predefinida -no se pueden
 * renombrar/recolorear/borrar ni asignar/quitar de una canción a mano, ver `LibraryDao.updateTag`/
 * `deleteTag`- aunque su `systemKey` sea `null` (no cuelgan de un valor fijo de [SystemTagKey], hay una
 * distinta por cada idioma que aparezca).
 *
 * Backfill: igual que [migration20To21] con "Vídeo sincronizado", ya puede haber canciones con
 * `language` relleno desde antes de esta versión (el editor de metadatos lleva tiempo autorellenando
 * ese campo desde la letra, ver
 * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.updateLanguageFromLyrics]). Sin este
 * backfill se quedarían sin su etiqueta de idioma hasta la próxima vez que alguien volviera a guardar
 * esa canción en concreto. Por cada valor de `language` distinto (recortado de espacios, los vacíos
 * fuera) se reutiliza una etiqueta existente con ese nombre exacto si ya la hay -por ejemplo una
 * personalizada que el usuario ya se hubiera creado antes, tal cual esté, sin tocarle el color ni
 * marcarla [isAutoAssigned]- o se crea una nueva blanca ([R.color.um_tag_language]) sí marcada
 * [isAutoAssigned], y se enlazan en `song_tag` todas las canciones que comparten ese idioma.
 *
 * Se resuelve fila a fila con cursores en vez de una única sentencia SQL con funciones de ventana
 * (`ROW_NUMBER`, etc.) porque la versión de SQLite embebida varía según el dispositivo (minSdk 24) y no
 * se puede dar por hecho que las soporte; mismo estilo que [migration20To21].
 */
fun migration22To23(context: Context): Migration = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tags ADD COLUMN isAutoAssigned INTEGER NOT NULL DEFAULT 0")

        var nextSortOrder = 0
        db.query("SELECT COALESCE(MAX(sortOrder), -1) FROM tags").use { cursor ->
            if (cursor.moveToFirst()) nextSortOrder = cursor.getInt(0) + 1
        }
        val languageColor = ContextCompat.getColor(context, R.color.um_tag_language)

        val languages = mutableListOf<String>()
        db.query(
            "SELECT DISTINCT TRIM(language) FROM songs WHERE language IS NOT NULL AND TRIM(language) != ''"
        ).use { cursor ->
            while (cursor.moveToNext()) languages.add(cursor.getString(0))
        }

        languages.forEach { language ->
            val tagId = db.query("SELECT id FROM tags WHERE name = ? LIMIT 1", arrayOf<Any>(language))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: run {
                    db.execSQL(
                        "INSERT INTO tags (name, colorArgb, systemKey, sortOrder, isAutoAssigned) VALUES (?, ?, NULL, ?, 1)",
                        arrayOf<Any>(language, languageColor, nextSortOrder)
                    )
                    nextSortOrder++
                    db.query("SELECT id FROM tags WHERE name = ? LIMIT 1", arrayOf<Any>(language)).use { cursor ->
                        cursor.moveToFirst()
                        cursor.getLong(0)
                    }
                }
            db.execSQL(
                "INSERT OR IGNORE INTO song_tag (songId, tagId) SELECT id, ? FROM songs WHERE TRIM(language) = ?",
                arrayOf<Any>(tagId, language)
            )
        }
    }
}

/**
 * v24 → v25: sin cambio de esquema — vuelve a sembrar `library_roots` con `Download`/`Music` (ver
 * [seedDefaultLibraryRoots]) si la tabla está completamente vacía.
 *
 * [UltiMusicDatabase.restoreFromBackupIfNeeded] restaura la copia de `~/UltiMusic/databases/` en
 * cuanto la BD interna no existe -el caso típico de una instalación limpia, ya que esa carpeta
 * externa sobrevive a desinstalar la app o borrar sus datos-, y esa restauración pasa por alto tanto
 * el `Callback.onCreate` (el fichero ya existe) como [MIGRATION_15_16] (la copia ya puede estar muy
 * por delante de la v15). Si la copia restaurada tenía `library_roots` vacía -por ejemplo, una hecha
 * antes de que existiera el sembrado de fábrica, o justo después de que el usuario quitara las dos
 * carpetas a mano y la app exportara esa foto al pasar a segundo plano-, una instalación "limpia"
 * nunca llegaba a tener Download/Music por defecto.
 *
 * Solo se resiembra si la tabla está DEL TODO vacía (`COUNT(*) = 0`), no con el mismo `INSERT OR
 * IGNORE` incondicional de [seedDefaultLibraryRoots]: si el usuario ya tiene aunque sea una carpeta
 * raíz guardada (una de las dos por defecto, o una suya propia), esta migración no toca nada, para no
 * resucitar una carpeta que haya quitado a mano a propósito.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val isEmpty = db.query("SELECT COUNT(*) FROM library_roots").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        }
        if (isEmpty) seedDefaultLibraryRoots(db)
    }
}

/**
 * v23 → v24: sin cambio de esquema — corrige el `name` ya sembrado de la etiqueta predefinida
 * "Descargada recientemente" (`SystemTagKey.RECENTLY_ADDED`). [seedDefaultTags] usa `INSERT OR
 * IGNORE`, así que quien ya tuviera esa fila desde [migration17To18] se quedó con el texto que hubiera
 * entonces en `R.string.tag_recently_added_name` aunque el recurso cambiara después: hace falta un
 * `UPDATE` explícito para que las instalaciones ya migradas se pongan al día con el texto actual del
 * recurso, igual que verían una instalación nueva.
 */
fun migration23To24(context: Context): Migration = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE tags SET name = ? WHERE systemKey = ?",
            arrayOf<Any>(
                context.getString(R.string.tag_recently_added_name),
                SystemTagKey.RECENTLY_ADDED.name
            )
        )
    }
}

/**
 * v26 → v27: la relación canción↔álbum vuelve a ser N:M (tabla de cruce `song_album`, ver
 * [com.untar.ultimusic.data.db.entities.SongAlbumCrossRef]) — justo la migración inversa de
 * [MIGRATION_12_13], que en su día la había simplificado a N:1 (columnas `albumId`/`trackNumber`/
 * `discNumber` directas en `songs`). Ahora vuelve a hacer falta poder catalogar una canción en más
 * de un álbum a la vez (un recopilatorio y el álbum original, por ejemplo) desde el editor de
 * metadatos ("+ Añadir otro álbum", ver
 * [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment.addAlbumGroup]).
 *
 * Se escribe a mano, como todas las de arriba: destruir la fonoteca de quien ya tenga la app
 * instalada por un cambio de modelado interno tiraría ediciones, carátulas y enlaces de vídeo que no
 * se pueden recuperar.
 *
 * Orden de los pasos:
 *  1. Se crea `song_album` (mismo patrón que [MIGRATION_12_13] al revés).
 *  2. Se copia a `song_album` el álbum/pista/disco que ya tuviera cada canción en las columnas
 *     viejas de `songs`, como su álbum PRINCIPAL (`position = 0`, ver
 *     [com.untar.ultimusic.model.Song.album]) — así ninguna canción ya catalogada pierde su álbum al
 *     actualizar.
 *  3. Se recrea `songs` sin `albumId`/`trackNumber`/`discNumber` (SQLite no tiene `ALTER TABLE ...
 *     DROP COLUMN` en las versiones que puede llevar un dispositivo con minSdk 24, mismo motivo que
 *     [MIGRATION_21_22]/[MIGRATION_16_17]), copiando el resto de columnas tal cual.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_album (
                songId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                trackNumber INTEGER,
                discNumber INTEGER,
                position INTEGER NOT NULL,
                PRIMARY KEY(songId, albumId),
                FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE,
                FOREIGN KEY(albumId) REFERENCES albums(id) ON DELETE CASCADE
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_song_album_albumId ON song_album(albumId)")

        db.execSQL(
            """
            INSERT INTO song_album (songId, albumId, trackNumber, discNumber, position)
            SELECT id, albumId, trackNumber, discNumber, 0 FROM songs WHERE albumId IS NOT NULL
            """
        )

        db.execSQL(
            """
            CREATE TABLE songs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                filePath TEXT NOT NULL,
                title TEXT NOT NULL,
                duration INTEGER NOT NULL,
                year INTEGER,
                genres TEXT NOT NULL,
                lyrics TEXT,
                language TEXT,
                imageName TEXT,
                comment TEXT,
                videoUrl TEXT,
                videoThumbnailName TEXT,
                videoOffsetMs INTEGER NOT NULL DEFAULT 0,
                lyricsOffsetMs INTEGER NOT NULL DEFAULT 0,
                hiddenByGreylist INTEGER NOT NULL DEFAULT 0,
                youtubeViewCount INTEGER,
                youtubeChannelId TEXT,
                ogTitle TEXT,
                ogArtist TEXT,
                ogYear INTEGER,
                dateAdded INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL(
            """
            INSERT INTO songs_new (
                id, filePath, title, duration, year, genres, lyrics, language, imageName, comment,
                videoUrl, videoThumbnailName, videoOffsetMs, lyricsOffsetMs, hiddenByGreylist,
                youtubeViewCount, youtubeChannelId, ogTitle, ogArtist, ogYear, dateAdded
            )
            SELECT
                id, filePath, title, duration, year, genres, lyrics, language, imageName, comment,
                videoUrl, videoThumbnailName, videoOffsetMs, lyricsOffsetMs, hiddenByGreylist,
                youtubeViewCount, youtubeChannelId, ogTitle, ogArtist, ogYear, dateAdded
            FROM songs
            """
        )
        db.execSQL("DROP TABLE songs")
        db.execSQL("ALTER TABLE songs_new RENAME TO songs")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_songs_filePath ON songs(filePath)")
    }
}

/**
 * v25 → v26: sin cambio de esquema — recolorea 4 de las etiquetas predefinidas y siembra la 6ª,
 * "Remix / Cover" (ver [SystemTagKey.REMIX_COVER]):
 *
 * - "En ninguna lista" y "Sin etiquetas personalizadas" pasan a compartir el mismo gris casi negro
 *   ([R.color.um_tag_no_custom_tags], más oscuro que el gris medio que tenían antes).
 * - "Descargada recientemente" y "Vídeo sincronizado" pasan a blanco.
 *
 * [seedDefaultTags] usa `INSERT OR IGNORE`, así que quien ya tuviera esas 4 filas desde una migración
 * anterior se quedó con el color de entonces aunque el recurso cambiara después -mismo problema que
 * [migration23To24] con el nombre de "Descargada recientemente"-, de ahí los `UPDATE` explícitos en
 * vez de confiar en el reseed.
 *
 * "Remix / Cover" es nueva de aquí, con membresía real ([R.color.um_tag_remix_cover] blanco) igual que
 * "Vídeo sincronizado" en [migration20To21]: a partir de ahora
 * [com.untar.ultimusic.data.LibraryRepository.syncRemixCoverTag] la mantiene sola en `song_tag` según
 * `Song.ogTitle` ("Título original" del editor de metadatos). Como con "Vídeo sincronizado", ya puede
 * haber canciones con ese campo relleno desde antes de esta versión (el editor lleva tiempo dejando
 * editarlo), así que tras sembrar la fila se hace el mismo backfill: se enlazan en `song_tag` todas las
 * que ya cumplan la condición, para que no se queden sin la etiqueta hasta que alguien vuelva a tocar
 * el campo a mano.
 */
fun migration25To26(context: Context): Migration = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val recoloredTags = listOf(
            SystemTagKey.NOT_IN_PLAYLIST to R.color.um_tag_not_in_playlist,
            SystemTagKey.NO_CUSTOM_TAGS to R.color.um_tag_no_custom_tags,
            SystemTagKey.RECENTLY_ADDED to R.color.um_tag_recently_added,
            SystemTagKey.SYNCED_VIDEO to R.color.um_tag_synced_video
        )
        recoloredTags.forEach { (systemKey, colorRes) ->
            db.execSQL(
                "UPDATE tags SET colorArgb = ? WHERE systemKey = ?",
                arrayOf<Any>(ContextCompat.getColor(context, colorRes), systemKey.name)
            )
        }

        db.execSQL(
            "INSERT OR IGNORE INTO tags (name, colorArgb, systemKey, sortOrder) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(
                context.getString(R.string.tag_remix_cover_name),
                ContextCompat.getColor(context, R.color.um_tag_remix_cover),
                SystemTagKey.REMIX_COVER.name,
                5
            )
        )
        db.query("SELECT id FROM tags WHERE systemKey = ?", arrayOf(SystemTagKey.REMIX_COVER.name)).use { cursor ->
            if (cursor.moveToFirst()) {
                val tagId = cursor.getLong(0)
                db.execSQL(
                    "INSERT OR IGNORE INTO song_tag (songId, tagId) SELECT id, ? FROM songs WHERE ogTitle IS NOT NULL AND TRIM(ogTitle) != ''",
                    arrayOf<Any>(tagId)
                )
            }
        }
    }
}
