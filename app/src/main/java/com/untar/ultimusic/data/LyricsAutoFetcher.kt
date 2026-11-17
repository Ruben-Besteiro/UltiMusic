package com.untar.ultimusic.data

import android.content.Context
import com.untar.ultimusic.data.remote.LrcLibApi
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.util.LrcParser
import com.untar.ultimusic.util.LyricsMatchScorer
import com.untar.ultimusic.util.unknownArtist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Búsqueda AUTOMÁTICA de letra en lrclib.net para canciones sin letra, al estilo de namida: se lanza
 * al reproducir la canción ([com.untar.ultimusic.playback.PlaybackService]) o al abrir su editor de
 * metadatos ([com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment]), y se queda con el mejor
 * candidato. Si se equivoca, el lápiz del recuadro de letra del iPod y la lupa del editor abren el
 * buscador manual de siempre ([com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]).
 *
 * Sustituye al antiguo "Añadir todas las letras" de ajustes.
 *
 * Diferencias deliberadas con namida (ver `_resolveLyrics`/`_fetchLRCLIB` en
 * `lib/controller/lyrics_controller.dart`):
 * - "No encontrada" se guarda en la BD con fecha ([Song.lyricsSearchedAt]) y caduca a los
 *   [RETRY_AFTER_MS], en vez de una pausa de 6 h en memoria que se pierde al cerrar la app.
 * - El scorer penaliza las versiones distintas (live, remix…), ver [LyricsMatchScorer].
 * - Entre candidatos con buen parecido y la duración dentro del margen, va primero la sincronizada.
 * - Remixes: si no hay nada con el título/artista propios, se busca la canción ORIGINAL
 *   ([Song.ogTitle]/[Song.ogArtist]) y se guarda su letra SIN sincronizar, porque la sincronizada del
 *   original iría desfasada con el remix.
 * - Variantes de consulta sin duplicados y solo lrclib.net (sin KuGou).
 *
 * Lo que se copia tal cual: instrumentales detectadas sin red (por el título) o porque el mejor
 * candidato de lrclib.net lo es con [LyricsMatchScorer.SURE_THRESHOLD]; un fallo de red no cuenta
 * como "no encontrada".
 *
 * Si la canción ya tiene letra no se busca nada: no hay nada que rellenar.
 */
object LyricsAutoFetcher {

    /** Valor de [Song.lyricsSearchedAt] que significa "no volver a buscar automáticamente". */
    const val SEARCH_DISABLED = Long.MAX_VALUE

    private val RETRY_AFTER_MS = TimeUnit.DAYS.toMillis(7)

    /** Margen de duración para dar por buena la grabación. Más laxo que el del buscador manual
     *  (1 s): aquí solo ordena, no se le enseña a nadie como "misma grabación". */
    private val DURATION_TOLERANCE_MS = TimeUnit.SECONDS.toMillis(2)

    sealed interface Outcome {
        data class Found(val lyrics: String) : Outcome
        data object NotFound : Outcome
        data object Instrumental : Outcome

        /** Sin red, límite de peticiones…: no se marca nada y se reintenta la próxima vez. */
        data object Failed : Outcome
    }

    /** Ámbito propio: la búsqueda sigue aunque se cierre el editor o se cambie de canción, y su
     *  resultado se aprovecha igual (el "no encontrada" se guarda, y la letra la guarda quien la
     *  pidió por [fetchAndSave]). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Una sola búsqueda en vuelo por canción, compartida entre reproductor y editor. */
    private val inFlight = HashMap<Long, Deferred<Outcome>>()

    private val _searching = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids de las canciones que se están buscando ahora mismo (para el spinner del iPod). */
    val searching: StateFlow<Set<Long>> = _searching.asStateFlow()

    /**
     * Si [lyrics] es una letra de verdad. El texto "null" no lo es: es lo que guardaban las
     * búsquedas antiguas cuando lrclib.net devolvía `null` (ver `optText` en [LrcLibApi]).
     */
    fun hasLyrics(lyrics: String?): Boolean =
        !lyrics.isNullOrBlank() && !lyrics.trim().equals("null", ignoreCase = true)

    fun needsSearch(song: Song, now: Long = System.currentTimeMillis()): Boolean {
        if (song.title.isBlank()) return false
        if (hasLyrics(song.lyrics)) return false
        // Un "null" guardado se busca SIEMPRE, sin esperar a que caduque: esa búsqueda dejó fecha
        // en lyricsSearchedAt como si hubiera encontrado letra.
        if (!song.lyrics.isNullOrBlank()) return true
        val searchedAt = song.lyricsSearchedAt ?: return true
        if (searchedAt == SEARCH_DISABLED) return false
        return now - searchedAt >= RETRY_AFTER_MS
    }

    /**
     * Lanza (o se engancha a) la búsqueda de [song]. NO guarda la letra encontrada —eso lo decide
     * quien llama: el reproductor la guarda al momento ([fetchAndSave]) y el editor solo rellena el
     * campo hasta que se pulse "Guardar"—, pero SÍ guarda "no encontrada"/"instrumental", que no
     * dependen de ningún formulario.
     */
    fun search(context: Context, song: Song): Deferred<Outcome> = synchronized(inFlight) {
        inFlight[song.id]?.let { return it }
        val appContext = context.applicationContext
        _searching.update { it + song.id }
        val job = scope.async {
            try {
                val outcome = runCatching { find(appContext, song) }.getOrElse { Outcome.Failed }
                val repository = LibraryRepository.get(appContext)
                when (outcome) {
                    Outcome.NotFound -> repository.setLyricsSearchedAt(song.id, System.currentTimeMillis())
                    Outcome.Instrumental -> repository.setLyricsSearchedAt(song.id, SEARCH_DISABLED)
                    is Outcome.Found, Outcome.Failed -> Unit
                }
                outcome
            } finally {
                synchronized(inFlight) { inFlight.remove(song.id) }
                _searching.update { it - song.id }
            }
        }
        inFlight[song.id] = job
        job
    }

    /**
     * Búsqueda + guardado, para el reproductor. Devuelve la canción recién releída de Room con su
     * letra nueva (para que quien llame la refresque en la cola), o null si no se ha guardado nada.
     * Si mientras tanto alguien le ha puesto letra a mano, se respeta esa y no se pisa.
     */
    suspend fun fetchAndSave(context: Context, song: Song): Song? {
        val outcome = search(context, song).await() as? Outcome.Found ?: return null
        val repository = LibraryRepository.get(context.applicationContext)
        val fresh = repository.songsByIds(listOf(song.id)).firstOrNull() ?: return null
        if (hasLyrics(fresh.lyrics)) return null
        repository.setLyrics(fresh, outcome.lyrics)
        repository.setLyricsSearchedAt(song.id, System.currentTimeMillis())
        return repository.songsByIds(listOf(song.id)).firstOrNull()
    }

    private suspend fun find(context: Context, song: Song): Outcome {
        if (LyricsMatchScorer.isInstrumentalTitle(song.title)) return Outcome.Instrumental

        val unknown = unknownArtist(context)
        val artists = song.artists.map { it.name }.filter { it.isNotBlank() && it != unknown }
        val artist = artists.firstOrNull().orEmpty()
        val album = song.album?.title?.takeIf { it.isNotBlank() }
        val durationModified = LyricsMatchScorer.isDurationModified(
            song.title, artist, *song.genres.toTypedArray()
        )

        // Variantes como las de namida (con y sin álbum), quitando las repetidas.
        val queries = listOfNotNull(artist to null, album?.let { artist to it }).distinct()
        val scorer = LyricsMatchScorer(song.title, artist)
        for ((queryArtist, queryAlbum) in queries) {
            val candidates = LrcLibApi.searchCandidates(song.title, queryArtist, queryAlbum)
            when (val outcome = pick(candidates, scorer, song.duration.takeUnless { durationModified } ?: 0L)) {
                Outcome.NotFound -> continue
                else -> return outcome
            }
        }

        // Remix: la letra de la canción original, sin sincronizar.
        val ogTitle = song.ogTitle?.takeIf { it.isNotBlank() }
        if (ogTitle != null) {
            val ogArtist = song.ogArtist?.takeIf { it.isNotBlank() }.orEmpty()
            val ogScorer = LyricsMatchScorer(ogTitle, ogArtist)
            val candidates = LrcLibApi.searchCandidates(ogTitle, ogArtist, null)
            val best = rank(candidates, ogScorer, targetDurationMs = 0L)
                .firstOrNull { !it.instrumental && (it.plainLyrics != null || it.syncedLyrics != null) }
            if (best != null) {
                val plain = best.plainLyrics
                    ?: LrcParser.parse(best.syncedLyrics!!).joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
                if (plain != null) return Outcome.Found(plain)
            }
        }

        return Outcome.NotFound
    }

    /**
     * Mejor candidato de una consulta. Como `_isBestLRCLIBMatchInstrumental` de namida: si el mejor
     * candidato que no es una "versión instrumental" aparte está marcado como instrumental y casa
     * de verdad con la canción, es que la canción no tiene letra.
     */
    private fun pick(candidates: List<LrcLibApi.Candidate>, scorer: LyricsMatchScorer, targetDurationMs: Long): Outcome {
        val ranked = rank(candidates, scorer, targetDurationMs)
        val best = ranked.firstOrNull { !LyricsMatchScorer.isInstrumentalTitle(it.trackName) }
        if (best != null && best.instrumental &&
            scorer.score(best.trackName, best.artistName) >= LyricsMatchScorer.SURE_THRESHOLD
        ) {
            return Outcome.Instrumental
        }
        val withLyrics = ranked.firstOrNull { !it.instrumental && (it.syncedLyrics != null || it.plainLyrics != null) }
            ?: return Outcome.NotFound
        return Outcome.Found(withLyrics.syncedLyrics ?: withLyrics.plainLyrics!!)
    }

    /**
     * Descarta lo que no se parece lo bastante y ordena: parecido, duración dentro del margen,
     * sincronizada antes que plana y, por último, la duración más cercana. `sortedWith` es estable,
     * así que a igualdad manda el orden de relevancia de lrclib.net.
     */
    private fun rank(
        candidates: List<LrcLibApi.Candidate>,
        scorer: LyricsMatchScorer,
        targetDurationMs: Long
    ): List<LrcLibApi.Candidate> {
        fun diff(candidate: LrcLibApi.Candidate): Long {
            val duration = candidate.durationMs
            return if (targetDurationMs <= 0L || duration == null || duration <= 0L) Long.MAX_VALUE
            else abs(targetDurationMs - duration)
        }
        return candidates
            .map { it to scorer.score(it.trackName, it.artistName) }
            .filter { (_, score) -> score >= LyricsMatchScorer.ACCEPT_THRESHOLD }
            .sortedWith(
                compareByDescending<Pair<LrcLibApi.Candidate, Double>> { it.second }
                    .thenBy { if (diff(it.first) <= DURATION_TOLERANCE_MS) 0 else 1 }
                    .thenBy { if (it.first.syncedLyrics != null) 0 else 1 }
                    .thenBy { diff(it.first) }
            )
            .map { it.first }
    }
}
