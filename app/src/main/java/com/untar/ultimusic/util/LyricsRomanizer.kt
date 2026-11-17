package com.untar.ultimusic.util

import android.icu.text.Transliterator
import com.untar.ultimusic.data.remote.GoogleTranslateApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pasa la letra de una canción a la escritura del idioma de la app (botón de arriba a la izquierda
 * del recuadro de la letra en [com.untar.ultimusic.ui.player.IPodDialogFragment]):
 *
 *  - app en un idioma de alfabeto latino -> romanización (Hepburn, pinyin, Romanización Revisada...)
 *    SIN diacríticos (ō -> o, š -> s...);
 *  - (VERSIÓN 3, hoy comentado) app en japonés -> katakana, app en chino -> bopomofo sin tonos,
 *    ambos a partir de esa misma romanización;
 *  - cualquier otro idioma (japonés, chino, cirílico, árabe, hindi...) -> sin botón ([targetFor]
 *    devuelve null). La conversión a escrituras no latinas entrará en la versión 3, no en la 2.
 *
 * La romanización la da Google ([GoogleTranslateApi.transliterateLines]), que lee bien el contexto
 * (kanji con lectura japonesa, は = "wa", coreano con la Romanización Revisada); si falla, se usa el
 * `Transliterator` de ICU en el dispositivo (sin red), con limitaciones: lee los kanji con su
 * lectura CHINA (pinyin), escribe ㄹ como "l" y は como "ha"; el kana sí sale bien (Hepburn). Con
 * ICU solo se convierten los tramos cuya escritura NO es la de destino (lo que ya está en ella se
 * deja tal cual); con Google, la línea entera.
 */
object LyricsRomanizer {

    // V3: añadir KATAKANA y BOPOMOFO (y descomentar el resto de bloques marcados con "V3").
    enum class Target { LATIN /*, KATAKANA, BOPOMOFO*/ }

    private val LATIN_LANGUAGES = setOf("es", "en", "fr", "de", "it", "pt", "tr", "vi", "in", "id", "fil", "tl")

    /** Destino según el idioma de la app, o null si en ese idioma el botón no tiene sentido. */
    fun targetFor(language: String = Locale.getDefault().language): Target? = when (language) {
        // V3:
        // "ja" -> Target.KATAKANA
        // "zh" -> Target.BOPOMOFO
        in LATIN_LANGUAGES -> Target.LATIN
        else -> null
    }

    /** Idiomas cuya escritura propia NO es la latina (los que pueden ser origen de una conversión). */
    private val NON_LATIN_LANGUAGES = setOf(
        "ja", "zh", "ko", "ru", "uk", "bg", "sr", "mk", "be", "kk", "mn", "ar", "fa", "ur", "he", "iw",
        "hi", "mr", "ne", "bn", "pa", "gu", "ta", "te", "kn", "ml", "th", "el", "hy", "ka", "lo", "km",
        "my", "si", "yi"
    )

    /**
     * Idioma de ORIGEN de la letra para fijárselo a Google en vez de `sl=auto`: con `auto`, una
     * línea de solo kanji se puede detectar como chino en una canción japonesa y salir en pinyin.
     * Prioridad: el campo "Idioma" de la canción ([languageField], editable en el editor de
     * metadatos, con su nombre en español) y, si está vacío o no se reconoce, ML Kit sobre la letra
     * entera ([LanguageDetector.detectTagSuspend]). Si el resultado es un idioma de alfabeto latino
     * (p. ej. "inglés" por un estribillo en inglés) no se usa: la letra tiene escritura ajena, así
     * que no puede ser el origen; devuelve `null` y Google autodetecta.
     */
    suspend fun resolveSourceLanguage(languageField: String?, lyrics: String): String? {
        val tag = languageField?.let { LanguageDetector.tagFromName(it) }
            ?: LanguageDetector.detectTagSuspend(lyrics)
        val language = tag?.let { Locale.forLanguageTag(it).language }?.takeIf { it.isNotEmpty() }
        return language?.takeIf { it in NON_LATIN_LANGUAGES }
    }

    /** Glifo del botón: la "A" de cada escritura. */
    fun glyph(target: Target): String = when (target) {
        Target.LATIN -> "A"
        // V3:
        // Target.KATAKANA -> "ア"
        // Target.BOPOMOFO -> "ㄅ"
    }

    /** ¿Hay en [text] alguna letra que [convert] cambiaría? Si no, el botón no tiene nada que hacer. */
    fun needsConversion(text: String, target: Target): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) && isForeign(cp, target)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Convierte [lines] (una entrada por fila, mismo tamaño y orden de salida). Nunca lanza.
     * Primero intenta la romanización de Google ([GoogleTranslateApi.transliterateLines]) para las
     * líneas con escritura ajena, porque lee bien los kanji, は = "wa" y el coreano; si falla (sin
     * red, 429, formato inesperado), cae a ICU, que es offline pero menos fino (ver doc de la
     * clase). Con Google, la línea entera sale de su romanización (no por tramos). Devuelve fallo
     * (con la excepción de Google, para decir el motivo) si el origen es japonés y Google ha
     * fallado (ICU no sirve para kanji).
     */
    suspend fun convert(lines: List<String>, target: Target, sourceLanguage: String? = null): Result<List<String>> {
        val foreignIdx = lines.indices.filter { needsConversion(lines[it], target) }
        if (foreignIdx.isEmpty()) return Result.success(lines)
        val googleResult = GoogleTranslateApi.transliterateLines(foreignIdx.map { lines[it] }, sourceLanguage)
        // ICU lee los kanji como chino (pinyin): para japonés es inservible, así que sin Google es
        // mejor avisar del fallo (con su motivo) que enseñar "chu yinmikuwo".
        if (googleResult.isFailure && sourceLanguage == "ja") return Result.failure(googleResult.exceptionOrNull()!!)
        val google = googleResult.getOrNull()
        return Result.success(withContext(Dispatchers.Default) {
            val result = lines.toMutableList()
            foreignIdx.forEachIndexed { j, i ->
                result[i] = runCatching {
                    if (google != null) {
                        var latin = stripDiacritics(google[j])
                        if (sourceLanguage == "ja") latin = fixJapaneseParticles(lines[i], latin)
                        fromRomanization(latin, target)
                    }
                    else convertLine(lines[i], target)
                }.getOrElse { lines[i] }
            }
            result
        })
    }

    /**
     * Google romanice は/へ/を como "ha"/"he"/"wo" (transliteración de kana, no de pronunciación).
     * Como partículas se leen "wa"/"e"/"o" (Hepburn). Se corrige solo una palabra SUELTA (no la
     * primera de la línea) y solo si el original lleva ese kana, para no tocar 葉/歯 ("ha") ni
     * palabras que contengan la sílaba.
     */
    private fun fixJapaneseParticles(original: String, latin: String): String {
        val fixes = listOf("ha" to ("は" to "wa"), "he" to ("へ" to "e"), "wo" to ("を" to "o"))
            .filter { (_, v) -> original.contains(v.first) }
        if (fixes.isEmpty()) return latin
        val tokens = latin.split(' ')
        return tokens.mapIndexed { idx, tok ->
            val fix = fixes.firstOrNull { it.first == tok.lowercase() }
            if (fix != null && (idx > 0 || fix.first == "wo")) {
                fix.second.second.let { if (tok[0].isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
            } else tok
        }.joinToString(" ")
    }

    private fun fromRomanization(latin: String, target: Target): String = when (target) {
        Target.LATIN -> latin
        // V3:
        // Target.KATAKANA -> latinToKatakana(latin)
        // Target.BOPOMOFO -> latinToBopomofo(latin)
    }

    private val latinAscii: Transliterator by lazy { Transliterator.getInstance("Latin-ASCII") }

    private fun stripDiacritics(text: String): String = latinAscii.transliterate(text)

    // ---- Qué es "ajeno" a cada destino ------------------------------------------------------

    private fun isForeign(cp: Int, target: Target): Boolean {
        val script = Character.UnicodeScript.of(cp)
        return when (script) {
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED,
            Character.UnicodeScript.UNKNOWN, Character.UnicodeScript.LATIN -> false
            // V3: con destino katakana/bopomofo, el kana/hanzi/bopomofo propio del destino no es ajeno:
            // Character.UnicodeScript.HAN -> target == Target.LATIN
            // Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA ->
            //     target != Target.KATAKANA
            // Character.UnicodeScript.BOPOMOFO -> target != Target.BOPOMOFO
            // Mientras solo existe el destino LATIN, todo lo que no es latino es ajeno:
            else -> true
        }
    }

    // ---- Conversión por tramos --------------------------------------------------------------

    private fun convertLine(line: String, target: Target): String {
        val out = StringBuilder()
        val run = StringBuilder()
        var runForeign = false
        var i = 0
        fun flush() {
            if (run.isEmpty()) return
            out.append(if (runForeign) convertRun(run.toString(), target) else run.toString())
            run.setLength(0)
        }
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            // Lo que no es letra (espacios, signos, números) se pega al tramo en curso para no
            // partir "sa-ran ghae" en trozos; solo una letra con distinta condición lo cierra.
            if (Character.isLetter(cp)) {
                val foreign = isForeign(cp, target)
                if (run.isNotEmpty() && foreign != runForeign) flush()
                runForeign = foreign
            }
            run.appendCodePoint(cp)
        }
        flush()
        return out.toString()
    }

    private fun convertRun(text: String, target: Target): String {
        val latin = toAsciiLatin(text)
        return when (target) {
            Target.LATIN -> latin
            // V3:
            // Target.KATAKANA -> latinToKatakana(latin)
            // Target.BOPOMOFO -> latinToBopomofo(latin)
        }
    }

    // ---- Cualquier escritura -> latín sin diacríticos ---------------------------------------

    private val anyToLatin: Transliterator by lazy {
        Transliterator.getInstance("Any-Latin; Latin-ASCII")
    }

    private fun toAsciiLatin(text: String): String =
        anyToLatin.transliterate(cyrillicToEnglishLatin(text))

    /** ICU romaniza el cirílico con la norma ISO 9 (š, č, ž, ŝ...), que tras quitar diacríticos deja
     * "scuka" por "щука". Esta tabla usa la ortografía inglesa de siempre (shch, zh, kh, ts...). */
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g"
    )

    private fun cyrillicToEnglishLatin(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            val mapped = CYRILLIC[ch.lowercaseChar()]
            if (mapped == null) {
                sb.append(ch)
            } else if (ch.isUpperCase() && mapped.isNotEmpty()) {
                sb.append(mapped[0].uppercaseChar()).append(mapped, 1, mapped.length)
            } else {
                sb.append(mapped)
            }
        }
        return sb.toString()
    }

    // V3 (comentado hasta la versión 3): Latín -> katakana y Latín -> bopomofo.
    /*
    // ---- Latín -> katakana ------------------------------------------------------------------

    private val latinKatakana: Transliterator by lazy { Transliterator.getInstance("Latin-Katakana") }

    private fun latinToKatakana(latin: String): String = latinKatakana.transliterate(latin.lowercase())

    // ---- Latín -> bopomofo sin tonos --------------------------------------------------------

    /** Equivalencia sonido a sonido, de izquierda a derecha, probando primero los dígrafos. Es una
     * aproximación fonética de la romanización (no pinyin->bopomofo exacto, que no hace falta: el
     * hanzi ya es la escritura propia del chino y no se convierte). */
    private val BOPOMOFO_MULTI = listOf(
        "zh" to "ㄓ", "ch" to "ㄔ", "sh" to "ㄕ", "ng" to "ㄥ", "kh" to "ㄎ", "ts" to "ㄘ",
        "ai" to "ㄞ", "ei" to "ㄟ", "ao" to "ㄠ", "ou" to "ㄡ", "an" to "ㄢ", "en" to "ㄣ"
    )
    private val BOPOMOFO_SINGLE = mapOf(
        'b' to "ㄅ", 'p' to "ㄆ", 'm' to "ㄇ", 'f' to "ㄈ", 'd' to "ㄉ", 't' to "ㄊ", 'n' to "ㄋ",
        'l' to "ㄌ", 'g' to "ㄍ", 'k' to "ㄎ", 'h' to "ㄏ", 'j' to "ㄐ", 'q' to "ㄑ", 'x' to "ㄒ",
        'r' to "ㄖ", 'z' to "ㄗ", 'c' to "ㄘ", 's' to "ㄙ", 'y' to "ㄧ", 'w' to "ㄨ", 'v' to "ㄈ",
        'a' to "ㄚ", 'o' to "ㄛ", 'e' to "ㄜ", 'i' to "ㄧ", 'u' to "ㄨ"
    )

    private fun latinToBopomofo(latin: String): String {
        val text = latin.lowercase()
        val sb = StringBuilder()
        var i = 0
        outer@ while (i < text.length) {
            for ((roman, bopo) in BOPOMOFO_MULTI) {
                if (text.startsWith(roman, i)) {
                    sb.append(bopo)
                    i += roman.length
                    continue@outer
                }
            }
            sb.append(BOPOMOFO_SINGLE[text[i]] ?: text[i].toString())
            i++
        }
        return sb.toString()
    }
    */
}
