package com.untarlamanteca.ultimusic.ui.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Un [FrameLayout] que siempre es cuadrado: impone que su alto sea igual a su ancho.
 *
 * Se usa para el recuadro de la carátula del iPod. En un LinearLayout normal no se puede pedir
 * "alto = ancho" solo con XML, así que lo resolvemos midiendo la vista aquí: durante [onMeasure]
 * el sistema nos pasa cómo debe medirse el ancho y el alto por separado; nosotros reutilizamos la
 * medida del ancho también para el alto, de modo que ambos lados acaban valiendo lo mismo.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
