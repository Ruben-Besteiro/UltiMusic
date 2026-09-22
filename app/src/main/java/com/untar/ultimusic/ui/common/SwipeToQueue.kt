package com.untar.ultimusic.ui.common

import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import kotlin.math.min

/**
 * Arrastrar una fila de canción hacia la derecha la añade a la cola de reproducción actual, como en
 * Spotify: al arrastrar se revela detrás de la fila el icono [R.drawable.ic_queue_add] (3 rayas
 * horizontales con un "+" abajo a la izquierda), teñido con el acento dinámico vigente. Es el mismo
 * efecto que elegir "Añadir a cola" en el menú de 3 puntos de la fila, NO un borrado: la fila nunca
 * desaparece de la lista, solo vuelve sola a su sitio al soltar el dedo.
 *
 * Encapsula solo el DIBUJADO y el disparo de la acción, no el [ItemTouchHelper.Callback] entero:
 * así se puede combinar con un arrastre UP/DOWN ya existente en la misma lista (ver
 * [com.untar.ultimusic.ui.collection.CollectionDetailDialogFragment], que reordena una lista Y ahora
 * también encola) sin pelearse por cuál de los dos [ItemTouchHelper] se queda enganchado al
 * RecyclerView -solo puede haber uno-. Para una lista SIN ningún otro gesto propio, usar directamente
 * [attachSwipeToQueue].
 *
 * El callback anfitrión debe:
 * - Declarar `ItemTouchHelper.RIGHT` entre sus direcciones de swipe.
 * - Devolver [swipeThreshold] en su `getSwipeThreshold`: mayor que 1, para que el gesto nunca se dé
 *   por "confirmado" al soltar (la fila SIEMPRE debe volver sola a su sitio, nunca comportarse como
 *   un swipe-to-delete de verdad).
 * - Dejar `onSwiped` vacío (nunca llega a dispararse con ese umbral, pero el override es obligatorio).
 * - En su `onChildDraw`, mientras `actionState == ACTION_STATE_SWIPE && dX > 0`, llamar a
 *   [onChildDraw] en vez de a `super.onChildDraw`; para cualquier otro caso (incluida la animación de
 *   vuelta al soltar), dejar que `super.onChildDraw` haga lo suyo como siempre.
 * - Llamar a [reset] desde su `clearView`, para poder disparar la acción de nuevo en el siguiente
 *   gesto.
 */
class SwipeToQueueGesture(context: Context, private val accentColor: () -> Int) {

    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_queue_add)?.mutate()

    /** True si ya se disparó la acción durante el arrastre en curso (ver [onChildDraw]/[reset]). */
    private var fired = false

    /** Ver la documentación de la clase: mayor que 1 = "nunca confirmado" para el ItemTouchHelper
     *  anfitrión. */
    val swipeThreshold: Float get() = TRIGGER_UNREACHABLE

    /** Llamar desde `clearView` del callback anfitrión, al soltar el dedo. */
    fun reset() {
        fired = false
    }

    /**
     * Dibuja el icono revelado (creciendo en opacidad con el arrastre) y dispara [onAddToQueue] en
     * cuanto [dX] supera [TRIGGER_FRACTION] del ancho de la fila -una sola vez por gesto, ver
     * [fired]-. Mueve la fila a mano con `translationX`, capada en [MAX_DRAG_FRACTION] del ancho para
     * que no se pueda tirar fuera de pantalla (efecto "goma" al llegar al tope).
     */
    fun onChildDraw(c: Canvas, viewHolder: RecyclerView.ViewHolder, dX: Float, onAddToQueue: (position: Int) -> Unit) {
        val itemView = viewHolder.itemView
        val maxDrag = itemView.width * MAX_DRAG_FRACTION
        val triggerDistance = itemView.width * TRIGGER_FRACTION
        val cappedDx = min(dX, maxDrag)

        if (!fired && dX >= triggerDistance) {
            fired = true
            onAddToQueue(viewHolder.bindingAdapterPosition)
        }

        icon?.let {
            DrawableCompat.setTint(it, accentColor())
            val size = min(itemView.height / 2, it.intrinsicHeight)
            val top = itemView.top + (itemView.height - size) / 2
            val left = itemView.left + (itemView.height - size) / 2
            it.setBounds(left, top, left + size, top + size)
            it.alpha = (255f * min(cappedDx / triggerDistance, 1f)).toInt()
            it.draw(c)
        }

        itemView.translationX = cappedDx
    }

    private companion object {
        /** Fracción del ancho de la fila a partir de la que se dispara la acción. */
        const val TRIGGER_FRACTION = 0.22f

        /** Tope visual del arrastre (efecto "goma" más allá de este punto). */
        const val MAX_DRAG_FRACTION = 0.3f

        /** Mayor que 1: ver [swipeThreshold]. */
        const val TRIGGER_UNREACHABLE = 2f
    }
}

/**
 * Engancha [SwipeToQueueGesture] a un RecyclerView que no necesita ningún otro gesto propio (la
 * mayoría de listas de canciones: Canciones, Buscador, ficha de álbum/artista...): arrastrar una
 * fila hacia la derecha la añade a la cola actual (ver [SwipeToQueueGesture]).
 *
 * [canSwipe] deja fuera las filas que no son canciones (p. ej. la cabecera de resumen de
 * [com.untar.ultimusic.ui.songs.SongsAdapter], en la posición 0).
 */
fun attachSwipeToQueue(
    recyclerView: RecyclerView,
    accentColor: () -> Int,
    canSwipe: (position: Int) -> Boolean = { true },
    onAddToQueue: (position: Int) -> Unit
): ItemTouchHelper {
    val gesture = SwipeToQueueGesture(recyclerView.context, accentColor)
    val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
        override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (canSwipe(vh.bindingAdapterPosition)) ItemTouchHelper.RIGHT else 0

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder): Float = gesture.swipeThreshold
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            gesture.reset()
        }

        override fun onChildDraw(
            c: Canvas,
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
                gesture.onChildDraw(c, vh, dX, onAddToQueue)
            } else {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
    }
    return ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
}
