package com.untar.ultimusic.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Fondo con relleno liso y contorno en degradado, para el iPod.
 *
 * Un `<stroke>` de un `shape` XML (o `GradientDrawable.setStroke`) solo admite un color liso, así
 * que el contorno degradado hay que pintarlo a mano. Replica lo que hace `GradientDrawable`: el
 * contorno va DENTRO de los límites (su centro queda a medio grosor del borde) y el relleno llega
 * hasta ese centro, de modo que la geometría es idéntica a la de los shapes que sustituye.
 *
 * El degradado va en vertical, de arriba ([strokeStart]) a abajo ([strokeEnd]).
 * Los tres colores se pueden cambiar en cualquier momento (el iPod los recalcula en cada paso del
 * fundido de acento), por eso son propiedades y no se crea un drawable nuevo cada vez.
 *
 * @param oval `true` para un círculo/elipse (botones redondos); `false` para un rectángulo
 *  redondeado con [cornerRadiusPx]. El radio se recorta solo a la mitad del lado corto, así un
 *  radio enorme da una píldora completa.
 */
class GradientStrokeDrawable(
    private val oval: Boolean,
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float,
    fillColor: Int,
    strokeStart: Int,
    strokeEnd: Int
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }
    private val rect = RectF()

    var fillColor: Int = fillColor
        set(value) {
            field = value
            fillPaint.color = value
            invalidateSelf()
        }

    var strokeStart: Int = strokeStart
        private set
    var strokeEnd: Int = strokeEnd
        private set

    init {
        fillPaint.color = fillColor
    }

    fun setStrokeColors(start: Int, end: Int) {
        strokeStart = start
        strokeEnd = end
        updateShader()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateShader()
    }

    private fun updateShader() {
        val b = bounds
        if (b.isEmpty) return
        strokePaint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(),
            strokeStart, strokeEnd, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val half = strokeWidthPx / 2f
        rect.set(bounds)
        rect.inset(half, half)
        if (rect.isEmpty) return
        if (oval) {
            canvas.drawOval(rect, fillPaint)
            canvas.drawOval(rect, strokePaint)
        } else {
            val radius = minOf(cornerRadiusPx - half, rect.width() / 2f, rect.height() / 2f)
                .coerceAtLeast(0f)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
