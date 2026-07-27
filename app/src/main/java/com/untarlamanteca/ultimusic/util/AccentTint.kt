package com.untarlamanteca.ultimusic.util

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.IdRes
import androidx.core.widget.ImageViewCompat

/**
 * Los ladrillos con los que cada pantalla repinta con el color de acento lo que en el XML estaba
 * pintado de amarillo.
 *
 * La regla de la app es esta, y vale para todo:
 *
 * - Lo que en el XML sea **`@color/um_yellow`** se repinta con el **acento** de la canción que suena.
 * - Lo que sea **`@color/um_yellow_dark`** se repinta con ese mismo acento **a un N-ésimo**, es decir
 *   con [DynamicColor.dim]. (Los dos amarillos fijos cumplen esa misma relación, así que mientras no
 *   suena nada la app se ve igual que siempre.)
 *
 * Aquí no hay ninguna magia que adivine qué vistas son amarillas: **cada pantalla dice cuáles son**.
 * Se hace así aposta, porque Android deja escribir colores pero casi no deja leerlos — en concreto,
 * el color de un `<stroke>` de un shape no se puede consultar desde código a ningún nivel de API, y
 * los contornos del iPod son justo eso. Detectarlos automáticamente obligaría a re-parsear los XML
 * de los drawables; enumerarlos es más tonto pero se entiende de un vistazo.
 *
 * Quien añada una vista amarilla nueva tiene que acordarse de darla de alta en el `collect` de
 * `accentColor` de su pantalla.
 */
object AccentTint {

    /**
     * Tiñe iconos ([ImageView] o [android.widget.ImageButton], que es un ImageView) buscándolos por
     * su id dentro de [root].
     *
     * Usa `ImageViewCompat` en lugar de `setColorFilter` porque un tinte respeta el estado de la
     * vista (pulsada, deshabilitada) y un filtro de color no.
     */
    fun icons(root: View, @ColorInt accent: Int, @IdRes vararg ids: Int) {
        val tint = ColorStateList.valueOf(accent)
        for (id in ids) {
            // Se ignoran los ids que no estén en este árbol: [root] puede ser la raíz entera de una
            // pantalla, donde no todas las vistas tienen por qué existir en todo momento.
            val icon = root.findViewById<ImageView>(id) ?: continue
            ImageViewCompat.setImageTintList(icon, tint)
        }
    }

    /**
     * Cambia el **relleno** del shape que hace de fondo de una vista (el `<solid>` de su XML).
     *
     * El `mutate()` es imprescindible: Android cachea los drawables por recurso, así que dos vistas
     * que usen el mismo `@drawable/...` comparten el objeto. Sin mutar, repintar una repintaría
     * también la otra. `mutate()` le da a esta vista una copia propia.
     */
    fun fill(root: View, @IdRes id: Int, @ColorInt color: Int) {
        val shape = root.findViewById<View>(id)?.background?.mutate() as? GradientDrawable
        shape?.setColor(color)
    }

    /**
     * Cambia el **contorno** del shape que hace de fondo de una vista (el `<stroke>` de su XML).
     *
     * Hay que volver a pasarle el ancho porque `setStroke` lo reescribe entero y no existe forma de
     * preguntarle a un [GradientDrawable] cuál tenía. Por eso los anchos viven en `res/values/
     * dimens.xml` (`ipod_stroke_*`) y los comparten el XML del drawable y esta llamada: así no se
     * desincronizan.
     */
    fun stroke(root: View, @IdRes id: Int, @ColorInt color: Int, @DimenRes widthDimen: Int) {
        val widthPx = root.resources.getDimensionPixelSize(widthDimen)
        val shape = root.findViewById<View>(id)?.background?.mutate() as? GradientDrawable
        shape?.setStroke(widthPx, color)
    }
}
