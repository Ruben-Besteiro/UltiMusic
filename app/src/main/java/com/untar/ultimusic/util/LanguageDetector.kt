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
 * Ver [com.untar.ultimusic.ui.editor.MetadataEditorDialogFragment], único sitio donde se usa: el
 * campo "Idioma" del editor de metadatos no se escribe a mano, se rellena solo a partir de la
 * letra.
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
        identifier.identifyLanguage(text)
            .addOnSuccessListener { code -> onResult(displayName(code)) }
            .addOnFailureListener { onResult(null) }
    }

    /** [code] llega como una etiqueta BCP-47 ("es", "en", "ja"...) o
     * [LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG] ("und") si ML Kit no ha podido decidirse. */
    private fun displayName(code: String): String? {
        if (code == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(SPANISH)
        return name.replaceFirstChar { it.uppercase(SPANISH) }
    }

    private val SPANISH = Locale("es")
}
