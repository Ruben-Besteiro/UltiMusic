package com.untar.ultimusic.ui.common

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Engancha a [this] un arrastre vertical de un solo sentido: la vista sigue al dedo con
 * `translationY` y, al soltar, si se ha superado [triggerDistancePx] llama a [onTrigger] SIN tocar
 * la `translationY` actual (queda donde la soltó el dedo); si no, vuelve a su sitio con una
 * animación. Que [onTrigger] no se llame con la vista ya "devuelta" a 0 importa: quien lo reciba
 * puede querer seguir animando desde ahí mismo para que el gesto se vea continuo, en vez de dar un
 * salto (ver [IPodNanoDialogFragment.animateClose], que es quien lo usa para cerrar el iPod
 * arrastrando hacia abajo).
 *
 * [upward] fija el sentido permitido: true solo deja arrastrar hacia arriba (`translationY`
 * negativo), false solo hacia abajo (positivo). El sentido contrario no mueve la vista, para no
 * robarle el gesto a un hijo que sí sepa qué hacer con él (por ejemplo, un scroll hacia abajo
 * dentro de la cola del iPod).
 *
 * Como se registra con [View.setOnTouchListener], solo entran aquí los toques que ningún hijo
 * "clicable" (botones, la barra de progreso, la lista) se queda por el camino: eso es justo lo que
 * interesa, para no romper esos controles.
 */
fun View.attachVerticalDrag(upward: Boolean, triggerDistancePx: Float, onTrigger: () -> Unit) {
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var startY = 0f
    var dragging = false

    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                dragging = false
                // Hay que devolver true: si no, Android no considera reclamado el gesto y dejará de
                // avisarnos de los MOVE/UP siguientes, cayendo el toque al hermano de debajo en el
                // z-order (p.ej. la lista de canciones detrás del mini-reproductor).
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - startY
                val goingRightWay = if (upward) dy < 0 else dy > 0
                if (!dragging && goingRightWay && abs(dy) > touchSlop) {
                    dragging = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    v.translationY = if (upward) dy.coerceAtMost(0f) else dy.coerceAtLeast(0f)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val moved = abs(v.translationY)
                    if (moved > triggerDistancePx) {
                        // No se toca translationY: se deja donde la soltó el dedo para que
                        // [onTrigger] pueda seguir animando desde ahí (ver comentario de la función).
                        onTrigger()
                    } else {
                        v.animate().translationY(0f).setDuration(150).start()
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
}
