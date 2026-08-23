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
 * Barra vertical del color picker (ver [ColorPickerView]): el tono (0-360°), de arriba a abajo,
 * como espectro completo (rojo→amarillo→verde→cian→azul→magenta→rojo). Elegir un punto aquí es lo
 * que reconstruye el degradado de [SaturationValueView], que pinta el resto del color para el tono
 * elegido.
 */
class HueSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Se avisa con cada arrastre a lo largo de la barra (0f..360f). */
    var onHueChanged: ((hue: Float) -> Unit)? = null

    private var hue = 0f

    private val fillPaint = Paint()
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Mueve el indicador a [newHue] SIN disparar [onHueChanged]: para cuando quien manda es el
     *  estado (los campos R/G/B), no el propio arrastre de esta barra. */
    fun setHue(newHue: Float) {
        hue = newHue.coerceIn(0f, 360f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 7 paradas (0°, 60°... 360°) bastan: entre dos primarios/secundarios consecutivos el
        // degradado lineal ya interpola el espectro real, no hace falta una parada por grado.
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val y = (hue / 360f) * height
        canvas.drawLine(0f, y, width.toFloat(), y, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (height == 0) return true
                hue = (event.y / height * 360f).coerceIn(0f, 360f)
                invalidate()
                onHueChanged?.invoke(hue)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
