package com.untar.ultimusic.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.untar.ultimusic.R

/**
 * Icono del toggle de barajar de una Lista (`R.id.action_shuffle` en la barra de
 * `CollectionDetailDialogFragment`, solo para `CollectionKind.LISTA` — Género/Etiqueta conservan el
 * disparo único de siempre, sin toggle): mismo drawable `ic_shuffle` de siempre, con dos estados en
 * vez de una sola acción.
 *
 * - **Desactivado**: el icono tal cual, teñido de [onBackground] — igual que el resto de iconos de
 *   esa barra (ver `CollectionDetailDialogFragment.applyAccent`).
 * - **Activado**: el icono teñido con el [accent] dinámico, sobre un círculo blanco de fondo. Hace
 *   falta ese círculo porque la cabecera de la ficha YA está pintada con ese mismo acento como fondo,
 *   así que un icono amarillo sin más se perdería contra ella — el círculo blanco es lo que lo hace
 *   visible.
 */
object ShuffleToggleIcon {

    private const val SIZE_DP = 24
    private const val GLYPH_INSET_DP = 3

    fun build(context: Context, active: Boolean, @ColorInt accent: Int, @ColorInt onBackground: Int): Drawable {
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_shuffle)!!.mutate()
        if (!active) {
            glyph.setTint(onBackground)
            return glyph
        }
        glyph.setTint(accent)
        val density = context.resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()
        val insetPx = (GLYPH_INSET_DP * density).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(sizePx, sizePx)
        }
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
        }
    }
}
