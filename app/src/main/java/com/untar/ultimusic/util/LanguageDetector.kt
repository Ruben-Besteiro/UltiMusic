package com.untar.ultimusic.util

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Deduce el idioma de un texto (la letra de una canción) con el identificador de ML Kit: un
 * modelo pequeño que corre en el propio dispositivo, así que la letra nunca sale de él. La
 * primera vez que se usa, ML Kit lo descarga solo a través de Google Play Services (unos
 * cientos de KB); a partir de ahí funciona sin red.
 *
 * [detect] lo usa [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment] para el campo
 * "Idioma" del editor de metadatos (no se escribe a mano, se rellena solo a partir de la letra);
 * [detectTag] lo usa [com.untar.ultimusic.ui.player.IPodDialogFragment] (ver su doc).
 */
object LanguageDetector {

    /** Instancia única y perezosa: crearla no es gratis (carga el modelo) y aquí basta con una
     * para toda la vida de la app. */
    private val identifier: LanguageIdentifier by lazy { LanguageIdentification.getClient() }

    /**
     * Identifica el idioma de [text] y llama a [onResult] en el hilo principal con su nombre en
     * español ("inglés", "japonés"...), o `null` si el texto es demasiado corto/ambiguo para que
     * ML Kit se decida, o si algo falla (p. ej. sin Google Play Services).
     */
    fun detect(text: String, onResult: (String?) -> Unit) {
        detectTag(text) { tag -> onResult(tag?.let { displayName(it) }) }
    }

    /**
     * Como [detect], pero suspendida en vez de por callback: la usa
     * [com.untar.ultimusic.data.LibraryRepository.setLyrics] (y su equivalente de arranque
     * [com.untar.ultimusic.data.LibraryRepository.backfillMissingLanguageTags]) para poder deducir
     * el idioma justo después de guardar una letra sin depender de que haya un formulario en pantalla
     * con el que sincronizarse, a diferencia de [detect].
     */
    suspend fun detectSuspend(text: String): String? = suspendCancellableCoroutine { cont ->
        detect(text) { cont.resume(it) }
    }

    /**
     * Como [detect], pero devuelve la etiqueta BCP-47 en crudo ("es", "en", "ja"...) en vez de su
     * nombre en español; `null` en los mismos casos. NO se calcula a partir de
     * [Song.language][com.untar.ultimusic.model.Song.language] (que solo guarda el nombre en
     * español, ver [detect]; puede estar vacío para una canción con letra muy reciente hasta que
     * [com.untar.ultimusic.data.LibraryRepository.setLyrics] o su backfill de arranque
     * [com.untar.ultimusic.data.LibraryRepository.backfillMissingLanguageTags] terminen de deducirlo):
     * lo usa
     * [com.untar.ultimusic.ui.player.IPodDialogFragment] para decidir si el idioma de la letra en
     * pantalla difiere del idioma del sistema (botón "あ") y, con el mismo resultado, como idioma de
     * origen al pedirle la traducción a [com.untar.ultimusic.util.LyricsTranslator].
     */
    fun detectTag(text: String, onResult: (String?) -> Unit) {
        identifier.identifyLanguage(text)
            .addOnSuccessListener { code ->
                onResult(code.takeIf { it != LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG })
            }
            .addOnFailureListener { onResult(null) }
    }

    /** [detectTag] suspendida: la usa [LyricsRomanizer.resolveSourceLanguage]. */
    suspend fun detectTagSuspend(text: String): String? = suspendCancellableCoroutine { cont ->
        detectTag(text) { cont.resume(it) }
    }

    /**
     * Operación inversa de [displayName]: el valor de [com.untar.ultimusic.model.Song.language]
     * ("japonés") -> etiqueta ("ja"), o `null` si no corresponde a ningún idioma. El campo es de
     * texto libre: lo rellena [detect] siempre en español, pero el usuario puede escribirlo a mano
     * en el idioma de la app ("Japanese") o en el propio idioma ("日本語"), así que se aceptan
     * los cuatro: español, inglés, idioma actual de la app y nombre nativo.
     */
    fun tagFromName(name: String): String? {
        val key = name.trim().lowercase(SPANISH)
        if (key.isEmpty()) return null
        return namesToTags(Locale.getDefault())[key]
    }

    /** Nombre del idioma [stored] (el guardado en BD, en español) en el idioma actual de la app, para
     *  mostrarlo ("Inglés" -> "English"). Si no corresponde a ningún idioma (texto libre), se devuelve
     *  tal cual. */
    fun localizedName(stored: String?): String? {
        if (stored.isNullOrBlank()) return stored
        val code = tagFromName(stored) ?: return stored
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        if (name.isEmpty() || name == code) return stored
        return name.replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }

    /** Forma guardada (nombre en español, la misma que produce [detect]) de lo que haya escrito el
     *  usuario en el campo Idioma: "English" -> "Inglés". Texto libre desconocido se devuelve tal cual.
     *  Así la etiqueta de idioma es siempre una sola, escriba como escriba el usuario. */
    fun canonicalName(input: String?): String? {
        if (input.isNullOrBlank()) return input
        val code = tagFromName(input) ?: return input
        return displayName(code).ifEmpty { input }
    }

    private var cachedNames: Pair<Locale, Map<String, String>>? = null

    /** Se reconstruye solo si cambia el idioma de la app (unos 190 idiomas x 4 nombres: es barato). */
    private fun namesToTags(appLocale: Locale): Map<String, String> {
        cachedNames?.takeIf { it.first == appLocale }?.let { return it.second }
        val map = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val language = Locale.forLanguageTag(code)
            for (inLocale in listOf(SPANISH, Locale.ENGLISH, appLocale, language)) {
                val display = language.getDisplayLanguage(inLocale).lowercase(SPANISH)
                if (display.isNotEmpty() && display != code) map.putIfAbsent(display, code)
            }
        }
        cachedNames = appLocale to map
        return map
    }

    /** [code] llega como una etiqueta BCP-47 ("es", "en", "ja"...), ya sin
     * [LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG] (lo filtra [detectTag]). */
    private fun displayName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(SPANISH)
        return name.replaceFirstChar { it.uppercase(SPANISH) }
    }

    private val SPANISH = Locale("es")
}
