package com.untarlamanteca.ultimusic.data

import android.content.Context
import android.os.Environment
import com.untarlamanteca.ultimusic.data.db.LibraryDao
import com.untarlamanteca.ultimusic.data.db.UltiMusicDatabase
import com.untarlamanteca.ultimusic.data.db.entities.AlbumEntity
import com.untarlamanteca.ultimusic.data.db.entities.ArtistEntity
import com.untarlamanteca.ultimusic.data.db.entities.SongEntity
import com.untarlamanteca.ultimusic.data.db.toDomain
import com.untarlamanteca.ultimusic.data.scan.MusicScanner
import com.untarlamanteca.ultimusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fachada de la biblioteca: la ÚNICA fuente de verdad de los modelos. Orquesta el escaneo del
 * filesystem ([MusicScanner]) y la base de datos ([UltiMusicDatabase]), expone flujos de dominio
 * que observa la UI y ofrece las operaciones de edición (que se reflejan al instante vía Room).
 */
class LibraryRepository private constructor(
    private val dao: LibraryDao,
    private val appContext: Context
) {

    /** Canciones persistidas, reactivas: cualquier edición reemite la lista. */
    val songs: Flow<List<Song>> =
        dao.observeSongs().map { list -> list.map { it.toDomain() } }

    /**
     * Reconcilia lo que hay en disco con lo guardado: escanea (lento, fuera de transacción) y
     * delega en el DAO la inserción de novedades y el borrado de lo que ya no existe. Las
     * ediciones del usuario nunca se pisan.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val scanned = MusicScanner.scan()
        dao.reconcile(scanned)
    }

    // --- Ediciones (se reflejan al instante en el Flow [songs]) ---

    suspend fun updateSong(song: SongEntity) = dao.updateSong(song)
    suspend fun updateArtist(artist: ArtistEntity) = dao.updateArtist(artist)
    suspend fun updateAlbum(album: AlbumEntity) = dao.updateAlbum(album)

    /**
     * Copia (unidireccional, solo para inspección) la base de datos interna a
     * `~/UltiMusic/databases/`, porque en algunos móviles no se puede entrar en /data/data.
     * Best-effort: cualquier fallo se ignora. Antes hace checkpoint del WAL para que la copia
     * sea consistente.
     */
    suspend fun exportDatabaseCopy() = withContext(Dispatchers.IO) {
        runCatching {
            val db = UltiMusicDatabase.get(appContext)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            val dbFile = appContext.getDatabasePath(UltiMusicDatabase.DB_NAME)
            val destDir = File(Environment.getExternalStorageDirectory(), "UltiMusic/databases")
            destDir.mkdirs()

            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(dbFile.path + suffix)
                if (src.exists()) {
                    src.copyTo(File(destDir, dbFile.name + suffix), overwrite = true)
                }
            }
        }
        Unit
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(
                    dao = UltiMusicDatabase.get(context).libraryDao(),
                    appContext = context.applicationContext
                ).also { instance = it }
            }
    }
}
