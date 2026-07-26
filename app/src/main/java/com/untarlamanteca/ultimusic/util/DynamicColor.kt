package com.untarlamanteca.ultimusic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saca un color de acento de una carátula para teñir con él la interfaz (pestañas, barras de
 * progreso, la rueda del iPod, la cabecera de la ficha de un álbum...). Es lo que sustituye al
 * amarillo fijo de UltiMusic cuando hay algo que suena o una ficha abierta.
 *
 * Usa **Palette**, una librería de AndroidX que analiza un `Bitmap` y devuelve un puñado de colores
 * representativos ("swatches") ya clasificados: vibrante, apagado, oscuro, claro... Nosotros
 * preferimos los vivos, porque un gris sacado de una portada oscura no se distinguiría del fondo.
 *
 * El color elegido se normaliza después con [readable]: se le fuerza un mínimo de saturación y una
 * franja de luminosidad, de forma que siempre destaque sobre el fondo negro de la app y el texto
 * blanco encima siga leyéndose. Sin eso, una portada casi blanca daría un acento invisible y una
 * casi negra, uno indistinguible del fondo.
 */
object DynamicColor {

    /** El amarillo de siempre: se usa mientras no hay carátula de la que sacar nada. */
    const val DEFAULT = 0xFFFFD200.toInt()

    /**
     * Carga la imagen indicada (cualquier dato que entienda Coil: un [java.io.File], un
     * `AudioCover`...) y devuelve su color de acento, o [DEFAULT] si no hay imagen o no se puede
     * analizar.
     *
     * Es `suspend` porque decodificar la imagen y recorrer sus píxeles es trabajo pesado que no
     * puede ir en el hilo principal; se llama desde el `viewModelScope`.
     */
    suspend fun fromCover(context: Context, data: Any): Int = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(context, data) ?: return@withContext DEFAULT
        val palette = runCatching { Palette.from(bitmap).clearFilters().generate() }
            .getOrNull() ?: return@withContext DEFAULT

        // De más a menos "vivo". El dominante es el último recurso: es el color que más superficie
        // ocupa, que en una portada oscura suele ser casi negro (por eso [readable] lo rescata).
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightMutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.dominantSwatch
            ?: return@withContext DEFAULT

        readable(swatch.rgb)
    }

    /**
     * Decodifica la imagen con el mismo [ImageLoader][coil.ImageLoader] que usa toda la app, así que
     * también funciona con las carátulas embebidas en los archivos de audio.
     *
     * `allowHardware(false)` es imprescindible: por defecto Coil devuelve "hardware bitmaps", que
     * viven en la memoria de la GPU y cuyos píxeles NO se pueden leer desde la CPU; Palette
     * necesita leerlos uno a uno, así que hay que pedir un bitmap normal.
     */
    private suspend fun loadBitmap(context: Context, data: Any): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(data)
            .allowHardware(false)
            // Reducirla acelera muchísimo el análisis y no cambia los colores dominantes.
            .size(SAMPLE_SIZE)
            .build()
        val result = runCatching { CoverLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
    }

    /**
     * Ajusta un color para que sirva de acento sobre fondo negro: sube la saturación si es
     * demasiado apagado y mete la luminosidad en una franja media-alta (ni tan oscuro que se pierda
     * en el fondo, ni tan claro que deslumbre).
     *
     * Trabaja en HSL (tono, saturación, luminosidad) en vez de en RGB porque ahí "más vivo" y "más
     * claro" son literalmente subir un número, mientras que en RGB habría que tocar los tres
     * canales a la vez sin cambiar el tono.
     */
    private fun readable(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtLeast(MIN_SATURATION)
        hsl[2] = hsl[2].coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Versión apagada y oscura del acento, para usarla de FONDO (la cabecera de la ficha de un
     * álbum). Un acento a plena intensidad detrás de un texto sería ilegible.
     */
    fun asBackground(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1] * BACKGROUND_SATURATION_FACTOR
        hsl[2] = BACKGROUND_LIGHTNESS
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Color de texto que se lee sobre [background]: blanco sobre fondos oscuros, negro sobre claros.
     * La "luminancia" no es la media de los canales, sino una media ponderada que imita cómo de
     * brillante percibe el ojo cada color (el verde pesa mucho más que el azul).
     */
    fun onColor(background: Int): Int =
        if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK
        else Color.WHITE

    private const val SAMPLE_SIZE = 128
    private const val MIN_SATURATION = 0.35f
    private const val MIN_LIGHTNESS = 0.45f
    private const val MAX_LIGHTNESS = 0.72f
    private const val BACKGROUND_SATURATION_FACTOR = 0.55f
    private const val BACKGROUND_LIGHTNESS = 0.40f
}
