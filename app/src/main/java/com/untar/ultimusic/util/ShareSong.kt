package com.untar.ultimusic.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.untar.ultimusic.R
import com.untar.ultimusic.model.Song
import java.io.File

/** Envía el archivo (o archivos) de audio a cualquier app que los acepte (hoja de compartir del sistema). */
object ShareSong {
    fun share(context: Context, song: Song) = share(context, listOf(song))

    fun share(context: Context, songs: List<Song>) {
        val pairs = songs.mapNotNull { song ->
            SafStorage.resolveUri(song.filePath)?.let { song to it }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(context, R.string.share_song_error, Toast.LENGTH_SHORT).show()
            return
        }
        fun mimeOf(song: Song): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(song.filePath).extension.lowercase())
                ?: "audio/*"
        val uris = ArrayList<Uri>(pairs.map { it.second })
        val send = if (pairs.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(pairs[0].first)
                putExtra(Intent.EXTRA_STREAM, uris[0])
                putExtra(Intent.EXTRA_TITLE, pairs[0].first.title)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // El ClipData es lo que hace que el permiso de lectura llegue a TODOS los URIs.
        send.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.share_song)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, R.string.share_song_error, Toast.LENGTH_SHORT).show() }
    }
}
