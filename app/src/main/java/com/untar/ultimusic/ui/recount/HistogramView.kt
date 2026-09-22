package com.untar.ultimusic.ui.recount

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.untar.ultimusic.model.HistogramData
import com.untar.ultimusic.util.DynamicColor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El histograma de la fonoteca de UltiMusic Recount: una barra por año de publicación, tan alta como
 * canciones haya de ese año.
 *
 * Es una `View` dibujada a mano, y no una librería de gráficos, por la misma razón que
 * [com.untar.ultimusic.ui.common.ValueRuler]: el proyecto no tiene ninguna, y meter una entera
 * (MPAndroidChart vive en JitPack, que ni siquiera está declarado como repositorio) para pintar
 * rectángulos y texto sería mucho equipaje para muy poco viaje.
 *
 * Se pinta a lo ancho de lo que [HistogramData] traiga, sin ninguna lógica propia sobre qué años
 * enseñar: los huecos largos ya vienen resumidos de [com.untar.ultimusic.data.RecountRepository], y
 * lo único que hace esta vista con ellos es dibujar la raya vertical de puntos entre las dos
 * columnas marcadas como borde.
 *
 * Pide el ancho que necesite en [onMeasure] (una anchura mínima por columna) contando con vivir
 * dentro de un `HorizontalScrollView`; si le sobra sitio, reparte el que haya entre las columnas que
 * tenga en vez de amontonarlas a la izquierda.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** El color de acento de la canción que suena (ver [DynamicColor]); las barras van de este color. */
    var accentColor: Int = DynamicColor.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var data: HistogramData? = null
        set(value) {
            field = value
            // Cambiar de datos cambia cuántas columnas hay y, con ellas, el ancho que pide la vista:
            // no basta con repintar.
            requestLayout()
            invalidate()
        }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED_COLOR
        strokeWidth = 1f
    }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED_COLOR
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED_COLOR
        textAlign = Paint.Align.CENTER
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED_COLOR
        textAlign = Paint.Align.CENTER
    }
    private val barRect = RectF()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    init {
        labelPaint.textSize = dp(10f)
        countPaint.textSize = dp(9f)
        gapPaint.strokeWidth = dp(1.5f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bars = data?.bars.orEmpty().size
        val desiredWidth = (bars * dp(MIN_SLOT_DP)).roundToInt() + paddingLeft + paddingRight
        setMeasuredDimension(
            // `max` con el mínimo sugerido para que una fonoteca de dos años no deje la vista
            // encogida en una esquina: el ancho real lo acaba de decidir el ScrollView de fuera.
            resolveSize(max(desiredWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(dp(DEFAULT_HEIGHT_DP).roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bars = data?.bars.orEmpty()
        if (bars.isEmpty()) return

        val maxCount = data?.maxCount ?: 0
        if (maxCount <= 0) return

        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        // De abajo a arriba: etiqueta del año, línea del eje, y encima el área de las barras. El
        // hueco de arriba deja sitio al número que corona la barra más alta.
        val baseline = height - paddingBottom - dp(LABEL_BAND_DP)
        val plotTop = paddingTop + dp(COUNT_BAND_DP)
        val plotHeight = baseline - plotTop
        if (plotHeight <= 0f) return

        val slot = (right - left) / bars.size
        val barWidth = (slot * BAR_FILL_RATIO).coerceAtMost(dp(MAX_BAR_DP))

        canvas.drawLine(left, baseline, right, baseline, axisPaint)

        barPaint.color = accentColor
        for ((index, bar) in bars.withIndex()) {
            val centerX = left + slot * (index + 0.5f)

            if (bar.songCount > 0) {
                // Un mínimo de altura para que un año con una sola canción no salga como una raya
                // invisible cuando el máximo del eje son 300.
                val barHeight = max(
                    plotHeight * bar.songCount / maxCount,
                    dp(MIN_BAR_HEIGHT_DP)
                )
                barRect.set(
                    centerX - barWidth / 2f,
                    baseline - barHeight,
                    centerX + barWidth / 2f,
                    baseline
                )
                val radius = barWidth / 4f
                canvas.drawRoundRect(barRect, radius, radius, barPaint)
                canvas.drawText(
                    bar.songCount.toString(),
                    centerX,
                    baseline - barHeight - dp(4f),
                    countPaint
                )
            }

            canvas.drawText(bar.year.toString(), centerX, height - paddingBottom - dp(2f), labelPaint)
        }

        drawGaps(canvas, bars.size, left, slot, plotTop, baseline)
    }

    /**
     * La raya vertical de los huecos largos. Los bordes vienen marcados de dos en dos desde el
     * repositorio (`isGapEdge`), así que basta con dibujar una raya en mitad de cada pareja
     * consecutiva; se salta de dos en dos para no confundir el cierre de un hueco con la apertura
     * del siguiente cuando hay varios.
     */
    private fun drawGaps(
        canvas: Canvas,
        barCount: Int,
        left: Float,
        slot: Float,
        plotTop: Float,
        baseline: Float
    ) {
        val bars = data?.bars.orEmpty()
        var index = 0
        while (index < barCount - 1) {
            if (bars[index].isGapEdge && bars[index + 1].isGapEdge) {
                val x = left + slot * (index + 1f)
                canvas.drawLine(x, plotTop, x, baseline, gapPaint)
                index += 2
            } else {
                index++
            }
        }
    }

    private companion object {
        /** Gris de ejes y números, el mismo `um_on_surface_muted` que usa [ValueRuler]. */
        const val MUTED_COLOR = 0xFFB0B0B0.toInt()

        const val DEFAULT_HEIGHT_DP = 180f
        const val MIN_SLOT_DP = 34f
        const val MAX_BAR_DP = 28f
        const val BAR_FILL_RATIO = 0.62f
        const val MIN_BAR_HEIGHT_DP = 2f

        /** Franja de abajo reservada a las etiquetas de año, y la de arriba a los recuentos. */
        const val LABEL_BAND_DP = 16f
        const val COUNT_BAND_DP = 14f
    }
}
