package com.untar.ultimusic.ui.library.colorpicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Cuadrado grande del color picker (ver [ColorPickerView]): saturación en el eje X, valor en el eje
 * Y, para un [hue] fijo (ese lo elige [HueSliderView] al lado). Es la pieza clásica de cualquier
 * selector HSV, dibujada a mano porque no hay ninguna librería de color picker en el proyecto.
 *
 * El degradado se consigue superponiendo dos capas (Android no tiene un shader 2D nativo
 * saturación×valor):
 * - Una horizontal, de blanco al color puro del [hue] actual — el eje de saturación.
 * - Una vertical, de transparente a negro — el eje de valor, dibujada ENCIMA con mezcla normal
 *   (alpha blend), que es lo que oscurece progresivamente hacia abajo sin tapar el tono de la capa
 *   de debajo.
 */
class SaturationValueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Tono fijo para el que se pinta el cuadrado (0-360). Cambiarlo reconstruye el degradado de
     *  saturación, que es el único de los dos que depende del tono. */
    var hue: Float = 0f
        set(newHue) {
            field = newHue.coerceIn(0f, 360f)
            rebuildShaders()
            invalidate()
        }

    /** Se avisa con cada arrastre dentro del cuadrado (saturación y valor, ambos 0f..1f). */
    var onSaturationValueChanged: ((saturation: Float, value: Float) -> Unit)? = null

    private var saturation = 1f
    private var value = 1f

    private val fillPaint = Paint()
    private val overlayPaint = Paint()
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Mueve el selector a un punto concreto SIN disparar [onSaturationValueChanged]: para cuando
     *  quien manda es el estado (el hue-slider o los campos R/G/B), no el propio arrastre de esta
     *  vista. */
    fun setSaturationValue(newSaturation: Float, newValue: Float) {
        saturation = newSaturation.coerceIn(0f, 1f)
        value = newValue.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val pureHue = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, Color.WHITE, pureHue, Shader.TileMode.CLAMP
        )
        overlayPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (fillPaint.shader == null) rebuildShaders()
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Círculo de contorno claro/oscuro según legibilidad, igual de criterio que
        // DynamicColor.onColor: sobre un punto oscuro (value bajo) se ve mejor en blanco.
        val x = saturation * w
        val y = (1 - value) * h
        selectorPaint.color = if (value > 0.5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(x, y, dp(8f), selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (width == 0 || height == 0) return true
                saturation = (event.x / width).coerceIn(0f, 1f)
                value = (1 - event.y / height).coerceIn(0f, 1f)
                invalidate()
                onSaturationValueChanged?.invoke(saturation, value)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
