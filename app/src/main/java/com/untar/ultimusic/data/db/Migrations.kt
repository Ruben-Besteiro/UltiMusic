package com.untar.ultimusic.data.db

import android.os.Environment
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
