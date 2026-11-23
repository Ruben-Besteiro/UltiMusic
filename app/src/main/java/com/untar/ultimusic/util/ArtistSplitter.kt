package com.untar.ultimusic.util

import android.content.Context
import com.google.android.material.textfield.TextInputLayout
import com.untar.ultimusic.R
import com.untar.ultimusic.data.ArtistSeparatorPreferences

/**
 * Parte y une los campos multivalor de los editores de metadatos (artistas, productores, géneros)
 * con los separadores que haya elegido el usuario en Ajustes (ver [ArtistSeparatorPreferences]).
 *
 * Solo para lo que ESCRIBE el usuario. Los textos internos de la app (el `GROUP_CONCAT(name, ', ')`
 * de Room, lo que devuelven las APIs) siguen yendo por comas, porque su formato no depende de este
 * ajuste.
 */
object ArtistSplitter {

    private val separators: List<String> get() = ArtistSeparatorPreferences.separators.value

    /** Parte por cualquiera de los separadores, quitando espacios y trozos vacíos. */
    fun split(text: String): List<String> = splitBy(text, separators)

    /** Une con el primer separador más un espacio (", " con el valor por defecto). */
    fun join(values: List<String>): String = values.joinToString(joiner())

    fun joiner(): String = "${separators.first()} "

    /** Lo que va antes del último separador (el valor que se está escribiendo va detrás), o "". */
    fun prefixBeforeLast(text: String): String {
        val cut = separators.maxOf { sep -> text.lastIndexOf(sep) }
        return if (cut < 0) "" else text.substring(0, cut)
    }

    /** "[,] [;]": cómo se enseñan los separadores en la ayuda del editor. */
    fun display(): String = separators.joinToString(" ") { "[$it]" }

    /**
     * Rellena la ayuda "Separa varios valores con [,] [;]" de los campos multivalor. En el XML
     * llevan `app:helperText="@string/multi_value_hint"` sin formatear (con el `%1$s` a la vista),
     * así que se reconocen comparando con el texto crudo del recurso y se sustituyen aquí.
     */
    fun fillHelperTexts(context: Context, layouts: List<TextInputLayout>) {
        val raw = context.getString(R.string.multi_value_hint)
        val formatted = context.getString(R.string.multi_value_hint, display())
        layouts.filter { it.helperText?.toString() == raw }.forEach { it.helperText = formatted }
    }

    private fun splitBy(text: String, seps: List<String>): List<String> {
        var parts = listOf(text)
        for (sep in seps) parts = parts.flatMap { it.split(sep) }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
