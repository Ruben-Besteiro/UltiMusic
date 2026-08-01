package com.untar.ultimusic.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Un [FrameLayout] cuyo alto se calcula como su ancho multiplicado por [aspectRatio]. Por defecto
 * vale 1, es decir, cuadrado (el uso de siempre: el recuadro de la carátula del iPod y el
 * contenedor de la imagen del editor de metadatos).
 *
 * En un LinearLayout normal no se puede pedir "alto = ancho * fracción" solo con XML, así que lo
 * resolvemos midiendo la vista aquí: durante [onMeasure] medimos primero con el ancho también como
 * alto (para que el sistema resuelva el ancho real a partir de su spec), y luego repetimos la
 * medida imponiendo el alto que toca según [aspectRatio].
 *
 * La pantalla del iPod usa además [animateAspectRatio] para pasar de cuadrada (carátula/cola) a
 * 16:9 (vídeo) con una animación corta en vez de un salto brusco.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var aspectRatio: Float = 1f
        set(value) {
            field = value
            requestLayout()
        }

    private var ratioAnimator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val height = View.MeasureSpec.makeMeasureSpec(
            (measuredWidth * aspectRatio).toInt(),
            View.MeasureSpec.EXACTLY
        )
        super.onMeasure(widthMeasureSpec, height)
    }

    /** Anima [aspectRatio] hasta [target] en [durationMs]. Cancela cualquier animación en curso. */
    fun animateAspectRatio(target: Float, durationMs: Long = 200) {
        ratioAnimator?.cancel()
        ratioAnimator = ValueAnimator.ofFloat(aspectRatio, target).apply {
            duration = durationMs
            addUpdateListener { aspectRatio = it.animatedValue as Float }
            start()
        }
    }
}
