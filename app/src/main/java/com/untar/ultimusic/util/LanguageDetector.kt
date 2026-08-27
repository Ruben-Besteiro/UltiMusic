package com.untar.ultimusic.util

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale

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
     * Como [detect], pero devuelve la etiqueta BCP-47 en crudo ("es", "en", "ja"...) en vez de su
     * nombre en español; `null` en los mismos casos. NO se calcula a partir de
     * [Song.language][com.untar.ultimusic.model.Song.language] (que solo guarda el nombre en
     * español, ver [detect], y solo si se ha editado la letra a mano en el editor de metadatos —
     * puede estar vacío aunque la canción sí tenga letra): lo usa
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

    /** [code] llega como una etiqueta BCP-47 ("es", "en", "ja"...), ya sin
     * [LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG] (lo filtra [detectTag]). */
    private fun displayName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(SPANISH)
        return name.replaceFirstChar { it.uppercase(SPANISH) }
    }

    private val SPANISH = Locale("es")
}
