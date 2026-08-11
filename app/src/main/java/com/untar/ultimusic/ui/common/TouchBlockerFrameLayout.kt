package com.untar.ultimusic.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Contenedor que **se traga todos los toques** para que no lleguen a lo que hay dentro, pero
 * reconoce el toque simple y avisa por [onTap]. Se usa para envolver el reproductor de YouTube en
 * la ventana del iPod.
 *
 * ¿Por qué hace falta tragarse los toques? Porque si llegaran al reproductor de YouTube, tocar el
 * vídeo lo pausaría, y YouTube muestra siempre su capa de marca (avatar del canal, título,
 * compartir, logo) cuando la reproducción está pausada. Esa capa no se puede ocultar con ningún
 * parámetro —`modestbranding` está obsoleto y no hace nada—, así que la única forma de que no
 * aparezca al tocar es que el toque nunca llegue al reproductor. Por eso el vídeo sigue siendo
 * interactuable (ver [onTap]), pero no por la vía de dejar pasar el toque, sino reconociéndolo
 * aquí y actuando por fuera: los mandos del iPod (incluido el propio play/pausa que dispara
 * [onTap]) están fuera de esta vista.
 *
 * **Esto no es una superposición.** Las condiciones de la API de YouTube prohíben poner elementos
 * visuales delante del reproductor, así que tapar el vídeo con una vista transparente no valdría.
 * Aquí no se dibuja nada encima: es el propio contenedor el que decide no repartir los eventos a
 * sus hijos, algo que pasa entre vistas de Android y que el reproductor ni percibe.
 *
 * **Cómo funciona.** En Android un toque baja desde la vista de arriba hacia sus hijos, y cada
 * contenedor puede quedárselo por el camino:
 * - [onInterceptTouchEvent] devolviendo `true` corta ese reparto: el evento no baja al hijo.
 * - [onTouchEvent] devolviendo `true` dice «me lo quedo yo», para que tampoco suba de vuelta y
 *   acabe activando por accidente algo que haya detrás. Antes de devolver `true` se pasa el evento
 *   por [gestureDetector] para distinguir un toque simple de un arrastre y disparar [onTap].
 */
class TouchBlockerFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Se llama con cada toque simple recibido (no con arrastres ni gestos largos). */
    var onTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }
        }
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }
}
