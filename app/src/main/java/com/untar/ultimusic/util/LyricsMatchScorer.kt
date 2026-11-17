package com.untar.ultimusic.util

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cuánto se parece un resultado de lrclib.net (título + artista) a la canción que se busca, de 0 a
 * 1. Lo usa la búsqueda automática de letras ([com.untar.ultimusic.data.LyricsAutoFetcher]) para no
 * guardar la letra de otra canción: en el buscador manual da igual que el primer resultado sea otro
 * tema porque elige el usuario, pero en automático se guarda sin que nadie lo mire.
 *
 * Adaptado de `_LyricsMatchScorer` de namida (`lib/controller/lyrics_controller.dart`):
 * - Título y artista se trocean en palabras normalizadas (minúsculas, sin tildes ni signos), y cada
 *   palabra admite alguna errata según su longitud ([Token.matches]).
 * - Puntuación = 70 % título + 30 % artista. El artista se compara en las dos direcciones, porque
 *   cualquiera de los dos lados puede listar colaboradores de más ("A & B" contra "B").
 * - "Nightcall" contra "Night Call": se prueba también el título con las palabras pegadas.
 *
 * Lo que se añade respecto a namida: [VERSION_MARKERS]. El scorer de namida solo mira que las
 * palabras de NUESTRO título aparezcan en el candidato, así que "Song (Live)" o "Song (Remix)"
 * puntuaban 1.0 contra "Song". Aquí cada marcador de versión que traiga el candidato y no nuestro
 * título resta [VERSION_PENALTY], para que la versión normal quede siempre por delante.
 */
class LyricsMatchScorer(title: String, artist: String) {

    private val titleTokens = tokenize(title, ignore = TITLE_NOISE)
    private val artistTokens = tokenize(stripArtistSuffix(artist))
    private val allTokens = titleTokens + artistTokens
    private val joinedTitle = Token(titleTokens.joinToString("") { it.text })
    private val ownTitleWords = split(title).toSet()

    fun score(candidateTitle: String?, candidateArtist: String?): Double {
        val titleParts = split(candidateTitle)
        val artistParts = split(candidateArtist)
        val allParts = if (artistParts.isEmpty()) titleParts else titleParts + artistParts

        var titleScore = 1.0
        if (titleTokens.isNotEmpty()) {
            titleScore = coverage(titleTokens, allParts)
            if (titleScore < 1.0 && (titleParts.size > 1 || titleTokens.size > 1) &&
                joinedTitle.matches(titleParts.joinToString(""))
            ) {
                titleScore = 1.0
            }
        }

        val extraMarkers = titleParts.count { it in VERSION_MARKERS && it !in ownTitleWords }
        val penalty = extraMarkers * VERSION_PENALTY

        if (artistTokens.isEmpty() || artistParts.isEmpty()) return titleScore - penalty

        val forward = coverage(artistTokens, allParts)
        val reverse = reverseCoverage(allTokens, artistParts)
        return titleScore * 0.7 + max(forward, reverse) * 0.3 - penalty
    }

    private class Token(val text: String) {
        private val maxDistance = when {
            text.length >= 7 -> 2
            text.length >= 4 -> 1
            else -> 0
        }

        /** Las palabras cortas tienen que coincidir exactas; las largas admiten 1-2 erratas. */
        fun matches(other: String): Boolean {
            if (other == text) return true
            if (maxDistance == 0) return false
            if (abs(other.length - text.length) > maxDistance) return false
            return levenshtein(text, other, maxDistance) <= maxDistance
        }
    }

    companion object {
        /** Por debajo, el candidato se descarta: no es la misma canción. */
        const val ACCEPT_THRESHOLD = 0.7

        /** Título y artista coinciden de verdad: un tema con el mismo título de otro artista queda
         *  por debajo. Es lo que se exige antes de fiarse de que lrclib.net diga "instrumental". */
        const val SURE_THRESHOLD = 0.9

        private const val VERSION_PENALTY = 0.2

        /** Relleno típico de títulos de vídeo que nunca aparece en una base de datos de letras. */
        private val TITLE_NOISE = setOf(
            "official", "video", "audio", "lyrics", "lyric", "music", "hd", "hq", "4k", "visualizer", "mv",
            "feat", "ft", "featuring"
        )

        /** Palabras que indican una VERSIÓN distinta de la canción (letra igual o casi, pero otra
         *  grabación, otra duración y una sincronización que no casa). "remaster" no está a
         *  propósito: es la misma grabación. */
        private val VERSION_MARKERS = setOf(
            "live", "directo", "vivo", "acoustic", "acustico", "acustica", "unplugged", "remix", "mix",
            "rmx", "edit", "cover", "karaoke", "instrumental", "inst", "demo", "nightcore", "sped",
            "slowed", "reverb", "extended", "orchestral"
        )

        private val INSTRUMENTAL_TITLE = Regex(
            """[(\[【「][^)\]】」]*\b(?:instrumental|off[ -]?vocal|inst)\b|(?:^|\s)[-–—~～]\s*(?:instrumental|off[ -]?vocal|inst\.?)(?:\s+ver(?:sion|\.)?)?\s*(?:[-–—~～]|$)""",
            RegexOption.IGNORE_CASE
        )

        /** "Song (Instrumental)", "Song - Off Vocal"…: la canción no tiene letra que buscar. Mismo
         *  regex que `isInstrumentalTitle` de namida. */
        fun isInstrumentalTitle(title: String): Boolean = INSTRUMENTAL_TITLE.containsMatchIn(title)

        private val DURATION_MODIFIED = Regex("nightcore|sped up|slowed", RegexOption.IGNORE_CASE)

        /** Versiones aceleradas/ralentizadas: su duración no se parece a la de ninguna en lrclib.net,
         *  así que no sirve para ordenar candidatos (idea de namida). */
        fun isDurationModified(vararg fields: String?): Boolean =
            fields.any { it != null && DURATION_MODIFIED.containsMatchIn(it) }

        private val DIACRITICS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private fun split(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
            return normalized.split(NON_WORD).filter { it.isNotEmpty() }
        }

        /** Las palabras de [ignore] se quitan, salvo que sean TODO el texto. */
        private fun tokenize(text: String, ignore: Set<String> = emptySet()): List<Token> {
            val (ignored, kept) = split(text).partition { it in ignore }
            return (kept.ifEmpty { ignored }).map(::Token)
        }

        /** "Artista (feat. X)" → "Artista", como `_substringArtist` de namida. */
        private fun stripArtistSuffix(artist: String): String {
            var index = artist.indexOf('(')
            if (index <= 0) index = artist.indexOf('[')
            return if (index <= 0) artist else artist.substring(0, index)
        }

        /** Fracción de [tokens] que aparece en [parts]. */
        private fun coverage(tokens: List<Token>, parts: List<String>): Double {
            if (parts.isEmpty()) return 0.0
            return tokens.count { token -> parts.any(token::matches) }.toDouble() / tokens.size
        }

        /** Fracción de [parts] que casa con alguno de [tokens]. */
        private fun reverseCoverage(tokens: List<Token>, parts: List<String>): Double =
            parts.count { part -> tokens.any { it.matches(part) } }.toDouble() / parts.size

        /** Distancia de edición con corte: en cuanto una fila entera supera [limit] se abandona. */
        private fun levenshtein(a: String, b: String, limit: Int): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
                    rowMin = min(rowMin, current[j])
                }
                if (rowMin > limit) return limit + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
