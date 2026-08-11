package com.untar.ultimusic.util

/**
 * Comparación laxa de títulos y nombres de artista, compartida por los dos clientes de API que
 * tienen que reconocer "la misma canción" escrita de formas distintas:
 * [com.untar.ultimusic.data.remote.ItunesApi] (para reordenar sus resultados por coincidencia real)
 * y [com.untar.ultimusic.data.remote.GeniusApi] (para no enriquecer con los datos de otra canción).
 *
 * Vive aquí y no dentro de uno de los dos porque son la MISMA regla: si un día se afina (quitar
 * "feat. …", tratar los números romanos…), tiene que afinarse para los dos a la vez o el
 * enriquecimiento empezaría a aceptar candidatos que la búsqueda había descartado, o al revés.
 */
object TextMatch {

    /**
     * Quita mayúsculas, espacios y signos de puntuación: así "PinocchioP" y "Pinocchio-P" (o
     * "pinocchio p") se ven iguales. `isLetterOrDigit` funciona con cualquier alfabeto (también
     * japonés), así que sirve igual para nombres occidentales que para acreditados en kanji/kana.
     */
    fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    /**
     * true si los dos textos, normalizados, son el mismo o uno contiene al otro. La comprobación va
     * en los dos sentidos a propósito, porque cada fuente añade sus propias coletillas al mismo
     * título: iTunes suele poner "Nombre (Radio Edit)" y Genius "Nombre (feat. Fulanito)", mientras
     * que el archivo del usuario puede tener el nombre pelado — o justo al revés.
     *
     * Un texto vacío nunca coincide con nada: si no, se colaría en cualquier comparación, ya que
     * toda cadena contiene a la vacía.
     */
    fun looselyEqual(a: String, b: String): Boolean {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) return false
        return normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA)
    }
}
