package com.untar.ultimusic.util

/** Una línea de letra sincronizada: el instante (en ms, desde el principio de la canción) en que
 *  empieza a cantarse y su texto. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Interpreta el formato LRC (`[mm:ss.xx]texto`, el que devuelve lrclib.net como `syncedLyrics` — ver
 * [com.untar.ultimusic.model.LyricsSuggestion] y
 * [com.untar.ultimusic.ui.editor.LyricsSuggestionsDialogFragment]) que puede haber quedado guardado
 * tal cual en `Song.lyrics`.
 *
 * No hay ningún campo en la base de datos que diga "esta letra está sincronizada": al elegir una
 * sugerencia se guarda el texto sincronizado si lo hay, y si no el plano, en el mismo `lyrics` de
 * siempre (igual que si el usuario la hubiera escrito a mano). [parse] es lo que distingue un caso
 * del otro: si no encuentra ni una sola marca de tiempo válida, devuelve una lista **vacía** — la
 * señal, para quien la llame (el iPod), de que es letra sin sincronizar y no hay nada que resaltar.
 */
object LrcParser {

    /** `[mm:ss]`, `[mm:ss.xx]` o `[mm:ss:xx]`; puede haber varias seguidas antes del texto (un
     *  estribillo que se repite en varios instantes de la canción). */
    private val TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val matches = TIMESTAMP.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                // "xx" son centésimas si son 2 cifras (lo habitual) pero décimas si es 1 sola cifra
                // y milisegundos si son 3; se normalizan todas a milisegundos.
                val fraction = m.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    else -> fraction.toLong()
                }
                lines.add(LyricLine(minutes * 60_000L + seconds * 1000L + millis, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Índice de la línea que toca cantar en [positionMs]: la última cuyo [LyricLine.timeMs] no se
     * ha pasado todavía de largo. `-1` si la canción no ha llegado ni a la primera marca de tiempo
     * (nada que resaltar aún). [lines] debe venir ordenada por tiempo, tal cual la devuelve [parse].
     *
     * Búsqueda binaria porque el iPod la llama en cada tick de progreso (~500 ms, ver
     * [com.untar.ultimusic.ui.player.IPodDialogFragment]): con letras de varios minutos y muchas
     * líneas, no tiene sentido recorrerlas todas cada vez.
     */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
