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
        // aspectRatio describe la proporción que debe tener el CONTENIDO (lo que se ve dentro del
        // padding), no la del propio View: si se calculara sobre measuredWidth a secas, restar el
        // mismo padding a un lado ancho (el ancho) y a uno estrecho (el alto) desvía la proporción
        // resultante. Con la carátula (aspectRatio 1, cuadrado) no se notaba porque restar lo mismo
        // a ambos lados iguales los deja iguales; con el vídeo (9/16) sí, y eso era lo que hacía que
        // YouTube pilarboxeara el vídeo en vez de llenar el recuadro.
        val contentWidth = measuredWidth - paddingLeft - paddingRight
        val contentHeight = (contentWidth * aspectRatio).toInt()
        val height = View.MeasureSpec.makeMeasureSpec(
            contentHeight + paddingTop + paddingBottom,
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
