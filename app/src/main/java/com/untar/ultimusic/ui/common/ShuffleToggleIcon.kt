package com.untar.ultimusic.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.untar.ultimusic.R

/**
 * Icono del interruptor de aleatorio de las fichas (`R.id.action_shuffle` en la barra de
 * `DetailDialogFragment` y `CollectionDetailDialogFragment`, ver
 * [com.untar.ultimusic.util.CollectionShuffleStore]): el mismo `ic_shuffle` de siempre, con dos estados.
 *
 * - **Desactivado**: el icono tal cual, teñido de [onBackground], igual que el resto de iconos de
 *   esa barra.
 * - **Activado**: un círculo blanco relleno con el icono recortado en [background], el color de la
 *   cabecera de la ficha, como si el icono fuera un hueco en el círculo.
 *
 * Los dos estados miden exactamente lo mismo ([SIZE_DP], lo que mide cualquier icono de la barra):
 * si no, la barra recolocaría sus botones al cambiar de uno a otro. Por eso el icono va centrado con
 * `setLayerSize`/`setLayerGravity` y no con márgenes interiores (`setLayerInset`), que se SUMAN al
 * tamaño de la capa y hacían crecer el botón al activarlo.
 */
object ShuffleToggleIcon {

    private const val SIZE_DP = 24
    private const val ACTIVE_GLYPH_DP = 16

    fun build(context: Context, active: Boolean, @ColorInt background: Int, @ColorInt onBackground: Int): Drawable {
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_shuffle)!!.mutate()
        val density = context.resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.WHITE else Color.TRANSPARENT)
            setSize(sizePx, sizePx)
        }
        val glyphPx = ((if (active) ACTIVE_GLYPH_DP else SIZE_DP) * density).toInt()
        glyph.setTint(if (active) background else onBackground)
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerSize(1, glyphPx, glyphPx)
            setLayerGravity(1, Gravity.CENTER)
        }
    }
}
