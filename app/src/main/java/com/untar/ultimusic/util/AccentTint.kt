package com.untar.ultimusic.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.untar.ultimusic.R

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
     * Tiñe el icono de un botón cuyo FONDO ya se pintó con [accent] (un FAB, el círculo de la
     * portada...), con blanco o negro según haga falta para que se lea encima — igual que hace la
     * burbuja de letra de la barra de scroll con [DynamicColor.onColor]
     * (ver [com.untar.ultimusic.ui.common.ScrollbarDrag]).
     *
     * Antes cada botón de estos llevaba su icono a un color FIJO en el XML (blanco el de la
     * portada, negro el de la varita y el de crear lista): se leían bien mientras el acento por
     * defecto (el amarillo) fuera claro, pero no con un acento oscuro. Con esto los tres siguen la
     * misma regla que la burbuja.
     */
    fun contentOnAccent(icon: ImageView, @ColorInt accent: Int) {
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(DynamicColor.onColor(accent)))
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

    /**
     * Tiñe con [accent] los botones ("Aceptar", "Cancelar"...) de un [AlertDialog] ya creado o
     * mostrado.
     *
     * Los diálogos de una sola pregunta (confirmaciones de borrado, avisos) no pasan por ningún
     * editor con tinte propio, así que sin esto sus botones se quedan en el amarillo fijo del tema
     * (`colorPrimary`) en vez de seguir el acento de la carátula que suena. `getButton` devuelve
     * `null` para el botón que el diálogo no tenga (por ejemplo uno sin botón negativo), así que se
     * ignora sin más.
     *
     * De paso se fija el ripple (el color con el que "pulsan" al tocarlos) en blanco: por defecto
     * Material lo pinta con `colorPrimary`, el amarillo fijo del tema, así que sin esto los botones
     * de TODOS los diálogos de la app pulsarían en amarillo aunque su texto ya siga el acento. Es
     * un blanco fijo a propósito, no [accent]: así pulsan igual que el resto de controles de la app
     * (ver [SettingsDialogFragment][com.untar.ultimusic.ui.settings.SettingsDialogFragment]).
     * `getButton` siempre devuelve un `MaterialButton` en esta app porque el tema es Material3 (ver
     * `MaterialComponentsViewInflater`), así que el cast es seguro.
     *
     * El blanco lleva un 20% de opacidad (`@color/um_ripple_white`), no blanco a secas: un ripple
     * opaco pulsa mucho más fuerte que el resto de la app, que sigue el `android:colorControlHighlight`
     * de fábrica (`ripple_material_dark`, ese mismo 20%).
     */
    fun buttons(dialog: AlertDialog, @ColorInt accent: Int) {
        val tint = ColorStateList.valueOf(accent)
        val whiteRipple = ColorStateList.valueOf(ContextCompat.getColor(dialog.context, R.color.um_ripple_white))
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            button.setTextColor(tint)
            (button as? MaterialButton)?.rippleColor = whiteRipple
        }
    }

    /**
     * `ColorStateList` para el `background` (la rayita de debajo) de un [android.widget.EditText]
     * suelto, sin `TextInputLayout` alrededor: [accent] al enfocarlo, el gris de siempre sin foco.
     *
     * Ese gris no es un color inventado nuestro: se lee `colorControlNormal` del tema, el mismo
     * atributo que usa Android de fábrica para pintar esa rayita cuando nadie la toca. Es literalmente
     * el mismo gris que ya se ve, sin que nadie lo toque, en campos de solo lectura como el del
     * vídeo del editor de metadatos (`inputVideoUrl` en dialog_metadata_editor.xml): nunca se
     * enfocan, así que se quedan siempre en ese estado por defecto.
     */
    fun underline(context: Context, @ColorInt accent: Int): ColorStateList {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorControlNormal, typedValue, true)
        val default = if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            // Nunca debería pasar (colorControlNormal siempre es un color), pero por si el tema
            // cambiase algún día y dejase de serlo, mejor un gris fijo que un cuelgue.
            0xFF808080.toInt()
        }
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(accent, default)
        )
    }
}
