package com.untarlamanteca.ultimusic.ui.common

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible

/**
 * Secciones plegables tipo "acordeón" para el editor de metadatos: una cabecera con una flecha que
 * gira y, debajo, un bloque de campos que se despliega o se recoge al pulsarla.
 *
 * Es el mismo patrón que usa Phonograph para sus campos secundarios. No hay ningún widget de Android
 * que haga esto, así que se anima a mano:
 *
 * - La flecha gira 0° → 90° con [View.animate], la API corta para animaciones simples de una vista.
 * - El contenido no se puede animar "de 0 a wrap_content" porque WRAP_CONTENT no es un número: hay
 *   que MEDIR primero cuánto ocuparía. Eso es lo que hace [View.measure] con `UNSPECIFIED`, que
 *   significa "mídete como si tuvieras todo el alto del mundo". Con esa altura real ya se puede
 *   animar un número con un [ValueAnimator], que no es más que un contador que va de A a B y en cada
 *   paso nos avisa para que apliquemos el valor (aquí, el alto del LinearLayout).
 * - Al terminar de abrir se devuelve la altura a WRAP_CONTENT para que la sección pueda crecer sola
 *   si el texto de un campo ocupa dos líneas.
 */
object CollapsibleSection {

    private const val ARROW_DURATION_MS = 200L
    private const val CONTENT_DURATION_MS = 250L

    /**
     * Enlaza una [header] clicable con su [content]. Se asume que el primer hijo de la cabecera es la
     * flecha (así está montado el layout), pero si no lo fuera simplemente no se anima nada.
     */
    fun setup(header: View, content: View) {
        val arrow = (header as? ViewGroup)?.getChildAt(0)
        header.setOnClickListener {
            if (content.isVisible) {
                collapse(content)
                arrow?.animate()?.rotation(0f)?.setDuration(ARROW_DURATION_MS)?.start()
            } else {
                expand(content)
                arrow?.animate()?.rotation(90f)?.setDuration(ARROW_DURATION_MS)?.start()
            }
        }
    }

    private fun expand(content: View) {
        val parentWidth = (content.parent as View).width
        content.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = content.measuredHeight

        content.layoutParams.height = 1
        content.isVisible = true

        ValueAnimator.ofInt(1, targetHeight).apply {
            duration = CONTENT_DURATION_MS
            addUpdateListener { animator ->
                content.layoutParams.height = animator.animatedValue as Int
                content.requestLayout()
            }
            doOnEnd {
                content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                content.requestLayout()
            }
        }.start()
    }

    private fun collapse(content: View) {
        val initialHeight = content.measuredHeight
        ValueAnimator.ofInt(initialHeight, 0).apply {
            duration = CONTENT_DURATION_MS
            addUpdateListener { animator ->
                content.layoutParams.height = animator.animatedValue as Int
                content.requestLayout()
            }
            doOnEnd {
                content.isVisible = false
                content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }.start()
    }

    /** Atajo para "haz esto cuando la animación acabe" sin escribir un AnimatorListener entero. */
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }
}
