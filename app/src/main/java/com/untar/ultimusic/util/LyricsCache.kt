package com.untar.ultimusic.util

import android.content.Context
import android.util.Log
import com.untar.ultimusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Caché en disco de las letras ya traducidas/romanizadas: cada resultado se guarda como un `.lrc`
 * en una carpeta VISIBLE dentro de `UltiMusic` (mismo patrón que `images`, ver
 * [CoverArt.imagesDocPath]), así que no hay que volver a pedírselo a Google (y gastar peticiones, ver
 * `RateLimitGuard`) cada vez que se pulsa el botón, y sobrevive a reinstalar la app.
 *
 *  - `UltiMusic/conversions/Ado - Vivarium latin.lrc` (sufijo: `latin`, `katakana` o `bopomofo`,
 *    el sistema de escritura de destino, ver [LyricsRomanizer.Target]; la carpeta se llama
 *    "conversions" y no "romanizations" porque no todos los destinos son el alfabeto latino);
 *  - `UltiMusic/translations/Ado - Vivarium es.lrc` (sufijo: código del idioma de destino).
 *
 * El nombre base es el de las carátulas ([CoverArt.imageBaseName]). Es un LRC de verdad: si la letra
 * original está sincronizada, cada línea lleva su `[mm:ss.xx]` y cualquier reproductor puede abrirlo;
 * si no, es el texto plano. La primera línea es una etiqueta `[ultimusic:<hash>]` (las etiquetas
 * desconocidas se ignoran en LRC) con la huella de la letra ORIGINAL: si el usuario la edita después,
 * la huella ya no cuadra y el archivo se descarta y se rehace, en vez de enseñar una traducción de
 * una letra que ya no existe. Renombrar una canción cambia el nombre del archivo, así que empieza una
 * caché nueva (la vieja queda en la carpeta, inofensiva).
 */
object LyricsCache {

    private const val TAG = "LyricsCache"

    enum class Kind(val folder: String) {
        ROMANIZATION("conversions"),
        TRANSLATION("translations")
    }

    /** Sufijo del archivo de una romanización: el sistema de escritura de destino. */
    fun suffixFor(target: LyricsRomanizer.Target): String = target.name.lowercase()

    private val TAG_REGEX = Regex("""^\[ultimusic:([0-9a-f]+)]""")
    private val LEADING_STAMPS = Regex("""^(\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?])+""")

    /**
     * Devuelve el resultado guardado si lo hay y sigue siendo válido; si no, llama a [compute] y, si
     * acierta, lo guarda para la próxima vez. [onMiss] se llama justo antes de [compute] (para el aviso
     * de "Traduciendo letra…": con caché no hace falta, es instantáneo). Un fallo de la caché
     * (carpeta sin conceder, sin espacio, archivo ilegible) nunca impide el resultado: se calcula y ya.
     *
     * @param originalLines las filas de la letra original, en el orden en que se pintan.
     * @param timesMs el instante de cada fila si la letra está sincronizada (mismo tamaño que
     * [originalLines]), o `null` si es texto plano (una sola fila con todo el texto).
     */
    suspend fun getOrCompute(
        context: Context,
        song: Song,
        kind: Kind,
        suffix: String,
        originalLines: List<String>,
        timesMs: List<Long>?,
        onMiss: () -> Unit,
        compute: suspend () -> Result<List<String>>
    ): Result<List<String>> {
        val appContext = context.applicationContext
        val fileName = "${CoverArt.imageBaseName(song.artists.map { it.name }, song.title)} $suffix.lrc"
        val hash = fingerprint(originalLines)
        withContext(Dispatchers.IO) {
            runCatching { read(appContext, kind, fileName, hash, originalLines.size, timesMs != null) }
                .onFailure { Log.w(TAG, "No se pudo leer $fileName: ${it.message}") }
                .getOrNull()
        }?.let { return Result.success(it) }

        onMiss()
        val result = compute()
        result.onSuccess { converted ->
            withContext(Dispatchers.IO) {
                runCatching { write(appContext, kind, fileName, hash, converted, timesMs) }
                    .onFailure { Log.w(TAG, "No se pudo guardar $fileName: ${it.message}") }
            }
        }
        return result
    }

    /** Lo que va tras `"<base> "`: un solo sufijo (`latin`, `es`, `zh-TW`...) y `.lrc`. Exigir eso
     *  evita confundir los archivos de "A - B" con los de otra canción llamada "A - B live". */
    private val SUFFIX_PART = Regex("""^[A-Za-z]{2,10}(-[A-Za-z0-9]+)?\.lrc$""")

    /** Archivos de la caché de la canción de nombre base [base] (en ambas carpetas), con su parte
     *  final (`"latin.lrc"`, `"es.lrc"`...). */
    private fun filesOf(context: Context, base: String): List<Pair<SafStorage.SafEntry, String>> {
        val out = mutableListOf<Pair<SafStorage.SafEntry, String>>()
        for (kind in Kind.values()) {
            val (treeUri, dir) = dirPath(context, kind, create = false) ?: continue
            for (entry in SafStorage.listChildren(context, treeUri, dir)) {
                if (entry.isDirectory || !entry.name.startsWith("$base ", ignoreCase = true)) continue
                val rest = entry.name.substring(base.length + 1)
                if (SUFFIX_PART.matches(rest)) out.add(entry to rest)
            }
        }
        return out
    }

    /**
     * La canción ha cambiado de título o de artistas: sus archivos pasan a llamarse como el nombre
     * nuevo, igual que las carátulas ([com.untar.ultimusic.data.LibraryRepository.renameImage]), para
     * no dejar archivos huérfanos. [oldBase]/[newBase] son [CoverArt.imageBaseName]. Best-effort.
     * Si ya existía un archivo con el nombre nuevo (de otra canción que se llamó así), el antiguo
     * se borra: es solo una caché y se rehará.
     */
    suspend fun rename(context: Context, oldBase: String, newBase: String) = withContext(Dispatchers.IO) {
        if (oldBase == newBase) return@withContext
        val appContext = context.applicationContext
        runCatching {
            val taken = Kind.values().flatMap { kind ->
                dirPath(appContext, kind, create = false)?.let { (tree, dir) ->
                    SafStorage.listChildren(appContext, tree, dir).map { it.name.lowercase() }
                } ?: emptyList()
            }.toSet()
            for ((entry, rest) in filesOf(appContext, oldBase)) {
                val newName = "$newBase $rest"
                if (newName.lowercase() in taken) SafStorage.deleteRecursively(appContext, entry.docPath)
                else SafStorage.renameTo(appContext, entry.docPath, newName)
            }
        }.onFailure { Log.w(TAG, "No se pudieron renombrar las letras de $oldBase: ${it.message}") }
        Unit
    }

    /** La canción se ha borrado: se borran también sus letras traducidas/romanizadas. */
    suspend fun delete(context: Context, base: String) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        runCatching {
            filesOf(appContext, base).forEach { (entry, _) -> SafStorage.deleteRecursively(appContext, entry.docPath) }
        }.onFailure { Log.w(TAG, "No se pudieron borrar las letras de $base: ${it.message}") }
        Unit
    }

    private fun fingerprint(lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray())
        return digest.take(6).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    private fun dirPath(context: Context, kind: Kind, create: Boolean): Pair<android.net.Uri, String>? {
        SafStorage.ensureUltiMusicRegistered(context)
        val treeUri = SafStorage.ultiMusicTreeUri(context) ?: return null
        val root = SafStorage.ultiMusicDocPath(context) ?: return null
        val dir = if (create) SafStorage.getOrCreateSubfolder(context, treeUri, root, kind.folder)
        else SafStorage.findSubfolder(context, treeUri, root, kind.folder)
        return dir?.let { treeUri to it }
    }

    private fun findFile(context: Context, treeUri: android.net.Uri, dir: String, name: String): String? =
        SafStorage.listChildren(context, treeUri, dir)
            .firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.docPath

    private fun read(
        context: Context, kind: Kind, fileName: String, hash: String, rowCount: Int, synced: Boolean
    ): List<String>? {
        val (treeUri, dir) = dirPath(context, kind, create = false) ?: return null
        val docPath = findFile(context, treeUri, dir, fileName) ?: return null
        val text = SafStorage.openInputStream(context, docPath)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        val lines = text.lines()
        val savedHash = lines.firstOrNull()?.let { TAG_REGEX.find(it)?.groupValues?.get(1) }
        if (savedHash != hash) return null
        val body = lines.drop(1)
        return if (synced) {
            // Se leen por orden de fila, no por marca de tiempo: las marcas del archivo solo están
            // para otros reproductores, y redondeadas a centésimas podrían reordenar filas casi iguales.
            val rows = body.dropLastWhile { it.isEmpty() }
                .map { LEADING_STAMPS.replace(it, "").trim() }
            rows.takeIf { it.size == rowCount }
        } else {
            listOf(body.joinToString("\n").trimEnd('\n')).takeIf { rowCount == 1 }
        }
    }

    private fun write(
        context: Context, kind: Kind, fileName: String, hash: String,
        converted: List<String>, timesMs: List<Long>?
    ) {
        val (treeUri, dir) = dirPath(context, kind, create = true) ?: return
        // Se borra el anterior (si lo hay) y se crea de nuevo: "abrir para escribir" no garantiza
        // truncar en todos los proveedores, y crear sobre un nombre existente duplicaría el archivo.
        findFile(context, treeUri, dir, fileName)?.let { SafStorage.deleteRecursively(context, it) }
        val content = buildString {
            append("[ultimusic:").append(hash).append("]\n")
            if (timesMs != null) {
                converted.forEachIndexed { i, text ->
                    val t = timesMs[i]
                    append(String.format(java.util.Locale.ROOT, "[%02d:%02d.%02d]", t / 60_000, t / 1000 % 60, t % 1000 / 10))
                    append(text.replace('\n', ' ')).append('\n')
                }
            } else {
                append(converted.joinToString("\n")).append('\n')
            }
        }
        val docPath = SafStorage.createFile(context, treeUri, dir, fileName, "application/octet-stream")
            ?: error("No se ha podido crear $fileName")
        SafStorage.openOutputStream(context, docPath)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("No se ha podido escribir $fileName")
    }
}
