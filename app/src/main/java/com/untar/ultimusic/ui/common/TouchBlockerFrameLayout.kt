package com.untar.ultimusic.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Contenedor que **se traga todos los toques** para que no lleguen a lo que hay dentro. Se usa para
 * envolver el reproductor de YouTube en la ventana del iPod.
 *
 * ¿Por qué hace falta? Porque tocar el vídeo lo pausa, y YouTube muestra siempre su capa de marca
 * (avatar del canal, título, compartir, logo) cuando la reproducción está pausada. Esa capa no se
 * puede ocultar con ningún parámetro —`modestbranding` está obsoleto y no hace nada—, así que la
 * única forma de que no aparezca al tocar es que el toque nunca llegue al reproductor. Los mandos
 * del iPod siguen funcionando: están fuera de esta vista.
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
 *   acabe activando por accidente algo que haya detrás.
 */
class TouchBlockerFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
