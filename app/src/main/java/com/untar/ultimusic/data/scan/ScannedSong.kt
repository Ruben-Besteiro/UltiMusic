package com.untar.ultimusic.data.scan

/**
 * Estos son los metadatos que el [MusicScanner] lee y entrega a la capa de persistencia para reconciliar.
 * Los campos de artista/álbum son anulables (pueden faltar en las etiquetas); si faltan, la capa de
 * datos no crea entidad ni enlace para ellos (igual que ya hacía con el productor).
 *
 * Esto NO ES MEDIASTORE. Esto pertenece al archivo, mientras que MediaStore es una "copia" que pertenece al SO
 */

data class ScannedSong(
    val filePath: String,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genres: List<String>,
    val year: Int?,
    val duration: Long,
    val producer: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    /** Fecha de modificación del documento (epoch ms), para [com.untar.ultimusic.data.db.entities.SongEntity.dateAdded].
     *  Viene ya resuelta por [com.untar.ultimusic.data.scan.MusicScanner] (que la lee del proveedor
     *  SAF al listar la carpeta, ver `SafStorage.SafEntry.lastModified`) en vez de calcularse aquí:
     *  desde la migración a Storage Access Framework, `filePath` ya no es una ruta de archivo real
     *  sobre la que se pueda llamar `File.lastModified()`. `0` para una canción "suelta" leída fuera
     *  del escaneo (ver [MusicScanner.readTagsFromUri]), que nunca se persiste. */
    val dateAdded: Long = 0L
)
