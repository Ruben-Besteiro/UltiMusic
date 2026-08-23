package com.untar.ultimusic.ui.library.colorpicker

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.widget.doAfterTextChanged
import com.untar.ultimusic.R
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.DynamicColor

/**
 * Color picker completo del editor de etiquetas (ver `TagEditorDialogFragment`): cuadrado de
 * saturación/valor ([SaturationValueView]) + barra de matiz ([HueSliderView]) + vista previa +
 * 3 [EditText] de R/G/B, todo convergiendo a un único [Int] ARGB. Es la única clase de este
 * paquete que el resto de la app necesita conocer.
 *
 * Nunca sigue el acento dinámico de reproducción (`PlayerViewModel.accentColor`): el color que
 * elige aquí el usuario es el de SU etiqueta, libre por diseño (ver el comentario de
 * `TagsAdapter` sobre `tag.colorArgb`, la excepción documentada a la regla de "amarillo dinámico"
 * de la app). Lo único que sí puede seguir el acento es la chrome alrededor (el subrayado de los
 * 3 campos R/G/B, ver [setAccentTint]), no el color resultante.
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Se avisa con cada cambio de color, venga de donde venga (cuadrado, barra o campos R/G/B). */
    var onColorChanged: ((Int) -> Unit)? = null

    private val svPicker: SaturationValueView
    private val hueSlider: HueSliderView
    private val inputR: EditText
    private val inputG: EditText
    private val inputB: EditText

    /** Único estado de verdad del color elegido, en HSV: hace falta guardar el hue aparte del RGB
     *  resultante porque un RGB con saturación o valor 0 no puede "recordar" de qué tono venía (todo
     *  gris/negro tiene el mismo RGB sea cual sea su hue), y perderlo haría saltar el hue-slider a 0
     *  en cuanto se arrastrase el cuadrado hacia un extremo. */
    private val hsv = floatArrayOf(0f, 1f, 1f)

    /** Freno anti-bucle: al reescribir un EditText con `setText()` se dispara su propio
     *  `doAfterTextChanged`, que sin este freno volvería a intentar leerlo y recalcular el color. */
    private var updatingProgrammatically = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_color_picker, this, true)
        svPicker = findViewById(R.id.svPicker)
        hueSlider = findViewById(R.id.hueSlider)
        inputR = findViewById(R.id.inputR)
        inputG = findViewById(R.id.inputG)
        inputB = findViewById(R.id.inputB)

        svPicker.onSaturationValueChanged = { saturation, value ->
            hsv[1] = saturation
            hsv[2] = value
            publishColor(updateSelectors = false)
        }
        hueSlider.onHueChanged = { hue ->
            hsv[0] = hue
            svPicker.hue = hue
            publishColor(updateSelectors = false)
        }
        inputR.doAfterTextChanged { if (!updatingProgrammatically) onRgbFieldEdited() }
        inputG.doAfterTextChanged { if (!updatingProgrammatically) onRgbFieldEdited() }
        inputB.doAfterTextChanged { if (!updatingProgrammatically) onRgbFieldEdited() }

        setColor(DynamicColor.DEFAULT)
    }

    private fun onRgbFieldEdited() {
        val r = inputR.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: return
        val g = inputG.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: return
        val b = inputB.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: return
        Color.RGBToHSV(r, g, b, hsv)
        publishColor(updateSelectors = true)
    }

    /** Recalcula el ARGB actual a partir de [hsv] y lo reparte por el resto de controles.
     *  [updateSelectors] es false cuando la llamada viene del propio cuadrado/barra (ya están en la
     *  posición correcta, movidos por el usuario) y true cuando viene de los campos R/G/B (hay que
     *  reposicionar el selector y el indicador de matiz para que reflejen lo tecleado). Los campos
     *  R/G/B, en cambio, NUNCA se reescriben aquí si el cambio vino de ellos mismos, para no pisar
     *  lo que el usuario está tecleando a mitad de escribir. */
    private fun publishColor(updateSelectors: Boolean) {
        val color = Color.HSVToColor(hsv)
        updatingProgrammatically = true
        AccentTint.fill(this, R.id.colorPreview, color)
        if (updateSelectors) {
            svPicker.hue = hsv[0]
            svPicker.setSaturationValue(hsv[1], hsv[2])
            hueSlider.setHue(hsv[0])
        } else {
            inputR.setText(Color.red(color).toString())
            inputG.setText(Color.green(color).toString())
            inputB.setText(Color.blue(color).toString())
        }
        updatingProgrammatically = false
        onColorChanged?.invoke(color)
    }

    /** Tiñe con [accent] el subrayado de los 3 campos R/G/B (chrome del diálogo, sigue el acento
     *  dinámico de reproducción; ver `AccentTint.underline`) — a diferencia del color en sí, que no
     *  lo sigue nunca (ver KDoc de la clase). */
    fun setAccentTint(@ColorInt accent: Int) {
        val underline = AccentTint.underline(context, accent)
        inputR.backgroundTintList = underline
        inputG.backgroundTintList = underline
        inputB.backgroundTintList = underline
    }

    /** Pinta todo el picker (cuadrado, barra, campos R/G/B y vista previa) a partir de [color], SIN
     *  disparar [onColorChanged]: para precargar el color actual de una etiqueta al abrir el editor
     *  en modo edición (o el color por defecto al abrirlo en modo creación). */
    fun setColor(@ColorInt color: Int) {
        Color.colorToHSV(color, hsv)
        updatingProgrammatically = true
        svPicker.hue = hsv[0]
        svPicker.setSaturationValue(hsv[1], hsv[2])
        hueSlider.setHue(hsv[0])
        AccentTint.fill(this, R.id.colorPreview, color)
        inputR.setText(Color.red(color).toString())
        inputG.setText(Color.green(color).toString())
        inputB.setText(Color.blue(color).toString())
        updatingProgrammatically = false
    }

    @ColorInt
    fun getColor(): Int = Color.HSVToColor(hsv)
}
