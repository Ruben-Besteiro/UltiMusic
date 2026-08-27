package com.untar.ultimusic.util

import com.untar.ultimusic.data.remote.LingvaTranslateApi

/**
 * Traduce la letra de una canción al idioma del SISTEMA con Lingva Translate (ver
 * [LingvaTranslateApi]) — proxy de código abierto sobre el motor real de Google Translate, sin
 * clave ni cuenta.
 *
 * Único sitio donde se usa: el botón "あ" del contenedor de la letra en
 * [com.untar.ultimusic.ui.player.IPodDialogFragment].
 */
object LyricsTranslator {

    /**
     * Traduce [lines] (una entrada por fila de [com.untar.ultimusic.ui.player.IPodLyricsAdapter], en
     * el mismo orden, para no romper el sincronismo con la letra LRC si la hay) al idioma actual del
     * sistema en el menor número de llamadas a [LingvaTranslateApi.translateLines] posible —las
     * líneas en blanco (huecos entre estrofas) se dejan fuera de esa llamada y se reinsertan tal cual
     * al recomponer el resultado, para no gastar peticiones traduciendo cadenas vacías—.
     *
     * Devuelve la lista traducida, del mismo tamaño y en el mismo orden que [lines], o `null` si no
     * hay ninguna línea con contenido que traducir o si la llamada falla entera (todas las instancias
     * de Lingva caídas, algún trozo desalineado...).
     */
    suspend fun translate(lines: List<String>): List<String>? {
        if (lines.isEmpty()) return emptyList()
        val nonBlankIndices = lines.indices.filter { lines[it].isNotBlank() }
        if (nonBlankIndices.isEmpty()) return null

        val translated = LingvaTranslateApi.translateLines(nonBlankIndices.map { lines[it] }) ?: return null

        val result = lines.toMutableList()
        nonBlankIndices.forEachIndexed { i, lineIndex -> result[lineIndex] = translated[i] }
        return result
    }
}
