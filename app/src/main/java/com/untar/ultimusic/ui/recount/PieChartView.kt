package com.untar.ultimusic.ui.recount

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.untar.ultimusic.model.GenreSlice
import com.untar.ultimusic.util.DynamicColor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La tarta de géneros de UltiMusic Recount. Cada sector es un género y ocupa la fracción de vuelta
 * que le corresponda de las reproducciones del año.
 *
 * Dibujada a mano por el mismo motivo que [HistogramView]. Los colores NO son fijos: salen de
 * [DynamicColor.palette], que gira la rueda de color a partir del acento de la canción que suena, de
 * modo que el sector mayoritario se ve del color de esa canción y el resto son variaciones suyas
 * (regla de color dinámico del proyecto).
 *
 * Qué géneros llegan aquí lo decide [com.untar.ultimusic.data.RecountRepository]: la cola de géneros
 * minúsculos viene ya fundida en un único sector "Otros", porque cuarenta rayas de medio grado no se
 * distinguen unas de otras. La leyenda de la pantalla sí los lista todos, así que no se esconde
 * ningún dato, solo se deja de dibujar por separado lo que no se vería.
 *
 * Es un donut y no un círculo macizo a propósito: el agujero del centro deja sitio para el número de
 * géneros y hace que los sectores finos se lean mejor, porque todo su grosor está en el borde
 * exterior en vez de irse estrechando hasta el punto central.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var accentColor: Int = DynamicColor.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            rebuildColors()
            invalidate()
        }

    var slices: List<GenreSlice> = emptyList()
        set(value) {
            field = value
            rebuildColors()
            invalidate()
        }

    /** Los colores de [slices], en el mismo orden. Es también lo que lee la leyenda de la pantalla
     *  (ver [colorAt]) para que el cuadradito de cada fila case con su sector. */
    private var colors: List<Int> = emptyList()

    // Los sectores se dibujan como ARCO GRUESO (stroke), no como cuña rellena: eso es justo lo
    // que hace el agujero del donut, sin tener que recortar nada por dentro.
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Del color del fondo de la pantalla: las rayitas que separan sectores no son una línea
        // pintada encima, es el fondo que se ve por la rendija.
        color = BACKGROUND_COLOR
        style = Paint.Style.STROKE
    }
    private val bounds = RectF()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    /** El color del sector [index], para pintar el cuadradito de esa fila en la leyenda. */
    fun colorAt(index: Int): Int = colors.getOrElse(index) { accentColor }

    private fun rebuildColors() {
        colors = DynamicColor.palette(accentColor, slices.size)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(dp(DEFAULT_SIZE_DP).roundToInt(), widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.isEmpty()) return

        val available = min(
            width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom
        ).toFloat()
        if (available <= 0f) return

        val thickness = available * DONUT_THICKNESS_RATIO
        val inset = thickness / 2f
        val centerX = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = available / 2f - inset
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        slicePaint.strokeWidth = thickness

        // Se arranca arriba (-90°) y se va en el sentido de las agujas del reloj, que es como se lee
        // una tarta. Los sectores llegan ya ordenados de mayor a menor.
        var startAngle = START_ANGLE
        for ((index, slice) in slices.withIndex()) {
            // El último sector se estira hasta cerrar la vuelta (la que empezó en -90° termina en
            // 270°) en vez de usar su fracción exacta: así los redondeos de todos los anteriores no
            // dejan una cuña de fondo sin pintar al final.
            val sweep = if (index == slices.lastIndex) {
                (END_ANGLE - startAngle).coerceAtLeast(0f)
            } else {
                slice.fraction * 360f
            }
            slicePaint.color = colorAt(index)
            canvas.drawArc(bounds, startAngle, sweep, false, slicePaint)
            startAngle += sweep
        }

        // Las rendijas van al final, encima de todos los sectores: dibujadas sobre la marcha, el
        // sector siguiente taparía la del anterior.
        if (slices.size > 1) {
            separatorPaint.strokeWidth = thickness
            var angle = START_ANGLE
            for (slice in slices) {
                canvas.drawArc(
                    bounds,
                    angle - SEPARATOR_DEGREES / 2f,
                    SEPARATOR_DEGREES,
                    false,
                    separatorPaint
                )
                angle += slice.fraction * 360f
            }
        }
    }

    private companion object {
        const val DEFAULT_SIZE_DP = 200f

        /** Se arranca arriba y se cierra la vuelta 360° después, que es como se lee una tarta. */
        const val START_ANGLE = -90f
        const val END_ANGLE = 270f

        /** Grosor del anillo como fracción del diámetro. */
        const val DONUT_THICKNESS_RATIO = 0.30f

        /** Ancho de la rendija entre sectores, en grados. */
        const val SEPARATOR_DEGREES = 1.2f

        /** Equivale a `@color/um_background`: es el fondo que se ve por las rendijas. */
        const val BACKGROUND_COLOR = 0xFF202020.toInt()
    }
}
