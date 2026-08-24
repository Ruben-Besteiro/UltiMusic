package com.untar.ultimusic.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/**
 * `ViewGroup` que coloca a sus hijos uno detrás de otro y salta de fila en cuanto el siguiente no
 * cabe en el ancho disponible, como el texto de un párrafo pero con vistas en vez de palabras.
 *
 * La usa la fila de una canción para las "salchichas" de sus etiquetas (item_tag_chip_mini.xml, ver
 * [com.untar.ultimusic.ui.songs.SongsAdapter]): antes vivían dentro de un `HorizontalScrollView` -una
 * sola línea, había que arrastrar el dedo para ver las que no cupieran, es decir se recortaban- y con
 * esto se ven TODAS de golpe, a costa de que la fila crezca hacia abajo en vez de esconder el resto
 * hacia el lado.
 *
 * Respeta los márgenes de cada hijo (de sus [MarginLayoutParams]) tanto para el hueco horizontal
 * entre "salchichas" de la misma fila como para el vertical entre una fila y la siguiente: no hace
 * falta ningún atributo de espaciado propio, basta con poner `layout_marginEnd`/`layout_marginBottom`
 * en el hijo (ver item_tag_chip_mini.xml).
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    /**
     * Primera pasada: mide cada hijo y va acumulando el ancho de la fila actual; en cuanto el
     * siguiente no cabe, "cierra" esa fila (suma su alto al total) y empieza una nueva. El resultado
     * es el alto total -lo único que de verdad hace falta para `wrap_content`, que es lo único que
     * usa esta vista- y el ancho de la fila más larga, por si el padre también fuera `wrap_content`.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }

        var rowWidth = 0
        var rowHeight = 0
        var maxRowWidth = 0
        var totalHeight = paddingTop + paddingBottom

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (rowWidth > 0 && rowWidth + childWidth > availableWidth) {
                totalHeight += rowHeight
                maxRowWidth = max(maxRowWidth, rowWidth)
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += childWidth
            rowHeight = max(rowHeight, childHeight)
        }
        maxRowWidth = max(maxRowWidth, rowWidth)
        totalHeight += rowHeight

        val width = if (widthMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            maxRowWidth + paddingLeft + paddingRight
        }
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec))
    }

    /** Segunda pasada: mismo recorrido de [onMeasure], pero colocando de verdad cada hijo en vez de
     *  solo sumar tamaños. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = width - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin

            if (x > paddingLeft && x - paddingLeft + childWidth > availableWidth) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }

            val childLeft = x + lp.leftMargin
            val childTop = y + lp.topMargin
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)

            x += childWidth
            rowHeight = max(rowHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
    }
}
