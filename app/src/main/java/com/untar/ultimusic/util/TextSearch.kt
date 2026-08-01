package com.untar.ultimusic.util

import java.text.Normalizer

/**
 * Comparación de texto "a lo humano" para el buscador: sin distinguir mayúsculas ni tildes, de modo
 * que escribir `cancion` encuentre «Canción» y `bjork` encuentre «Björk».
 *
 * SQLite no sirve para esto: su `LIKE` solo ignora mayúsculas en el alfabeto ASCII (no sabe que «Á»
 * y «á» son la misma letra) y no tiene ninguna noción de tildes. Por eso el filtrado se hace en
 * Kotlin sobre las listas que ya observa la UI, y no con una consulta.
 *
 * ¿Cómo se quita una tilde? Unicode permite escribir «á» de dos maneras: como un único carácter, o
 * como una «a» seguida de una marca de acento invisible. [Normalizer.Form.NFD] pasa todo a la
 * SEGUNDA forma (descomponer), y entonces las marcas se pueden borrar con una expresión regular,
 * porque Unicode las agrupa en la categoría `Mn` ("Mark, nonspacing": signos que no ocupan sitio
 * propio y se dibujan sobre la letra anterior). Lo que queda es la letra pelada.
 */
object TextSearch {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Pasa el texto a minúsculas y sin tildes, que es la forma en la que se comparan las cosas. */
    fun normalize(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /**
     * ¿Contiene [haystack] la aguja buscada? [normalizedNeedle] tiene que venir YA normalizado (con
     * [normalize]): quien busca lo hace una sola vez y lo compara contra miles de títulos, así que
     * normalizarlo en cada comparación sería tirar trabajo a la basura.
     */
    fun contains(haystack: String?, normalizedNeedle: String): Boolean =
        haystack != null && normalize(haystack).contains(normalizedNeedle)
}
