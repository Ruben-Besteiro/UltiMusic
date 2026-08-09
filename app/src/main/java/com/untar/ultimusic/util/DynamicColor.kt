package com.untar.ultimusic.util

import android.content.Context
import android.content.res.ColorStateList
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

    /**
     * El amarillo de siempre: se usa mientras no hay carátula de la que sacar nada.
     *
     * Tiene que valer lo mismo que `@color/um_yellow` (res/values/colors.xml). Está repetido aquí
     * como constante, y no leído del recurso, porque hace falta como valor inicial en sitios que no
     * tienen un `Context` a mano (los campos `accent` de los adaptadores, por ejemplo). Si se cambia
     * el amarillo de la app hay que cambiarlo en los dos sitios.
     */
    const val DEFAULT = 0xFFFFD000.toInt()

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
     * El acento "al N-ésimo de intensidad": cada canal de color dividido entre N, dejando la opacidad
     * intacta. Es lo que sustituye a `@color/um_yellow_dark`, que guarda esa misma relación con
     * `@color/um_yellow`.
     *
     * Se hace en RGB y no en HSL a propósito: dividir los tres canales es literalmente "un N-ésimo de
     * luz", que es la relación que ya había entre los dos amarillos fijos. Cuidado con no confundirla
     * con [asBackground], que es otra cosa (ver ahí).
     */
    fun dim(color: Int): Int = Color.argb(
        Color.alpha(color),
        Color.red(color) / 5,
        Color.green(color) / 5,
        Color.blue(color) / 5
    )

    /**
     * Versión apagada y oscura del acento, para usarla de FONDO (la cabecera de la ficha de un
     * álbum). Un acento a plena intensidad detrás de un texto sería ilegible.
     *
     * No es lo mismo que [dim]: aquí además se **desatura** y se fija una luminosidad concreta, para
     * que el resultado sea un fondo neutro sobre el que se lea el texto, salga de donde salga el
     * acento. [dim] conserva el tono y la viveza; solo baja la luz.
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

    /**
     * `ColorStateList` para la pista de un `MaterialSwitch`: [accent] cuando está encendido, gris
     * (el que traiga [defaultTrackTint] de fábrica, o [Color.GRAY] si no hay ninguno) cuando está
     * apagado.
     *
     * Antes se aplicaba [accent] a los dos estados a la vez, así que el interruptor apagado también
     * se veía teñido del color de la carátula en vez de gris neutro. [defaultTrackTint] hay que
     * capturarlo del propio switch ANTES de tocar su `trackTintList` por primera vez, porque una vez
     * pisado ya no se puede recuperar el gris original del tema.
     *
     * El "pulsa en amarillo al tocarlo" que esto parece que debería arreglar en realidad **no** está
     * aquí: el `trackTint` es el relleno sólido de la pista, y el halo que se ve al tocar el switch
     * es un `android:background` aparte que `MaterialSwitch` hereda de AppCompat, ajeno del todo a
     * `trackTint`/`thumbTint`. Ver `ThemeOverlay.UltiMusic.SwitchRippleWhite` en `styles.xml`, que es
     * donde se corrige de verdad.
     */
    fun switchTrackTint(accent: Int, defaultTrackTint: ColorStateList?): ColorStateList {
        val uncheckedColor = defaultTrackTint
            ?.getColorForState(intArrayOf(-android.R.attr.state_checked), Color.GRAY)
            ?: Color.GRAY
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(accent, uncheckedColor)
        )
    }

    private const val SAMPLE_SIZE = 128
    private const val MIN_SATURATION = 0.35f
    private const val MIN_LIGHTNESS = 0.45f
    private const val MAX_LIGHTNESS = 0.72f
    private const val BACKGROUND_SATURATION_FACTOR = 0.55f
    private const val BACKGROUND_LIGHTNESS = 0.40f
}
