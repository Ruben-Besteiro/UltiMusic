package com.untar.ultimusic.ui.common

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.util.DynamicColor

private fun Int.dpToPx(density: Float): Int = (this * density).toInt()

/** Mismo grosor que la pista del SeekBar de las canciones (`res/drawable/seekbar_line.xml`). */
private const val THUMB_WIDTH_DP = 8
private const val THUMB_HEIGHT_DP = 48
private const val DEFAULT_HITBOX_WIDTH_DP = 24
private const val BUBBLE_MARGIN_DP = 12
private const val BUBBLE_SIZE_DP = 80
/** La mitad del lado: con la vista cuadrada, esto es lo que la convierte en un círculo. */
private const val BUBBLE_CORNER_RADIUS_DP = BUBBLE_SIZE_DP / 2f
private const val BUBBLE_TEXT_SIZE_SP = 32f
private const val AUTO_HIDE_DELAY_MS = 1000L
private const val FADE_DURATION_MS = 200L

/**
 * Deja en `#` lo que no empiece por una letra/número reconocible, igual que hacen los "fast
 * scrollers": mejor un cajón fijo para "sin título" que un cajón vacío.
 */
fun sectionLetter(text: String?): String {
    val first = text?.trim()?.firstOrNull() ?: return "#"
    return first.uppercaseChar().toString()
}

/**
 * Deja cambiar el color de la barra después de pegarla, para seguir el acento dinámico de la app.
 *
 * [bubble] es nulo cuando la barra se pegó sin burbuja de letra (ver [attachScrollbarDrag]).
 */
class ScrollbarController internal constructor(private val thumb: View, private val bubble: TextView?) {
    fun setAccentColor(color: Int) {
        (thumb.background as GradientDrawable).setColor(color)
        bubble?.let {
            (it.background as GradientDrawable).setColor(color)
            it.setTextColor(DynamicColor.onColor(color))
        }
    }
}

/**
 * Barra de scroll propia para [this], calcada de la de ~/Phonograph: a diferencia de la nativa de
 * Android (que solo se deja agarrar si la lista ya estaba en movimiento, y cuyo grosor depende de
 * cuánto ocupe lo visible sobre el total), aquí el "pulgar" es una vista nuestra de tamaño FIJO que:
 *  - Está siempre lista para ser arrastrada, se vea o no en ese instante.
 *  - Se oculta sola al cabo de un segundo sin uso (con `fadeScrollbars` nativo esto no se controla
 *    a mano; aquí sí, porque quien la dibuja somos nosotros).
 *  - Al arrastrarla, muestra al lado una burbuja con la letra de la sección en la que estamos,
 *    calculada con [sectionFor] a partir de la posición a la que se saltaría.
 *
 * Por eso los layouts que la usan llevan `android:scrollbars="none"`: la nativa ya no se dibuja,
 * la sustituye por completo esta.
 *
 * [sectionFor] es **opcional**: si se omite (o se pasa `null`) la burbuja ni siquiera se crea y
 * queda solo el pulgar arrastrable. Eso es lo que quiere el buscador, donde los resultados no están
 * ordenados alfabéticamente sino agrupados por secciones (canciones, álbumes, artistas,
 * productores), así que una letra ahí no significaría nada.
 *
 * [hitboxWidthDp] es la franja de la derecha donde se detecta el toque; a propósito bastante más
 * ancha que el pulgar visible ([THUMB_WIDTH_DP]), para que sea fácil agarrarla sin apuntar con
 * precisión de píxel.
 *
 * Sirve igual para una lista ([LinearLayoutManager]) que para una rejilla
 * ([androidx.recyclerview.widget.GridLayoutManager], que es subclase de aquel): la posición de
 * scroll se calcula por fracción sobre el total de elementos, no por filas.
 */
fun RecyclerView.attachScrollbarDrag(
    hitboxWidthDp: Int = DEFAULT_HITBOX_WIDTH_DP,
    sectionFor: ((Int) -> String)? = null
): ScrollbarController {
    val density = resources.displayMetrics.density
    val hitboxWidthPx = hitboxWidthDp.dpToPx(density)
    val thumbWidthPx = THUMB_WIDTH_DP.dpToPx(density)
    val thumbHeightPx = THUMB_HEIGHT_DP.dpToPx(density)
    val bubbleMarginPx = BUBBLE_MARGIN_DP.dpToPx(density)
    val bubbleSizePx = BUBBLE_SIZE_DP.dpToPx(density)

    val parentView = parent as ViewGroup

    val thumb = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = thumbWidthPx / 2f
            setColor(DynamicColor.DEFAULT)
        }
        alpha = 0f
    }
    parentView.addView(
        thumb,
        FrameLayout.LayoutParams(thumbWidthPx, thumbHeightPx, Gravity.TOP or Gravity.END).apply {
            // Centrado dentro de la franja del hitbox, no pegado al borde.
            marginEnd = (hitboxWidthPx - thumbWidthPx) / 2
        }
    )

    // Sin `sectionFor` no hay nada que escribir dentro, así que la burbuja no se crea siquiera.
    val bubble = if (sectionFor == null) null else TextView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = BUBBLE_CORNER_RADIUS_DP * density
            setColor(DynamicColor.DEFAULT)
        }
        setTextColor(DynamicColor.onColor(DynamicColor.DEFAULT))
        textSize = BUBBLE_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        elevation = 6f * density
        alpha = 0f
    }
    if (bubble != null) {
        parentView.addView(
            bubble,
            // Tamaño FIJO y cuadrado (no `WRAP_CONTENT`): así el radio de esquina de media anchura da
            // un círculo perfecto siempre, y la altura no baila según qué letra toque mostrar — que es
            // lo que permite alinearla con el pulgar sin sorpresas.
            FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx, Gravity.TOP or Gravity.END)
                .apply { marginEnd = hitboxWidthPx + bubbleMarginPx }
        )
    }

    val hideHandler = Handler(Looper.getMainLooper())
    val hideThumbRunnable = Runnable { thumb.animate().alpha(0f).setDuration(FADE_DURATION_MS).start() }

    /**
     * Media altura del más alto de los dos (pulgar y burbuja), que es el margen que hay que dejar
     * arriba y abajo para que **ninguno** se salga de la lista. Sin burbuja manda el pulgar y el
     * margen se queda en la mitad, así que la barra recorre más pantalla.
     */
    val halfTallestPx = maxOf(thumbHeightPx, bubble?.let { bubbleSizePx } ?: 0) / 2f

    /**
     * Recorrido del **centro** de la barra: en vez de mover cada vista por su borde superior (que
     * es lo que descuadraba la burbuja en los extremos, porque miden distinto), las dos cuelgan de
     * un único centro compartido. Así el círculo está *siempre* centrado con el pulgar, arriba,
     * abajo y en medio.
     */
    fun trackHeightPx() = (height - 2 * halfTallestPx).coerceAtLeast(0f)

    fun showThumb() {
        hideHandler.removeCallbacks(hideThumbRunnable)
        thumb.animate().cancel()
        thumb.alpha = 1f
    }

    fun scheduleHide() {
        hideHandler.removeCallbacks(hideThumbRunnable)
        hideHandler.postDelayed(hideThumbRunnable, AUTO_HIDE_DELAY_MS)
    }

    /** Coloca pulgar y burbuja compartiendo centro, a [fraction] (0 = arriba del todo, 1 = abajo). */
    fun moveThumbTo(fraction: Float) {
        val center = halfTallestPx + trackHeightPx() * fraction.coerceIn(0f, 1f)
        thumb.translationY = center - thumbHeightPx / 2f
        bubble?.translationY = center - bubbleSizePx / 2f
    }

    fun isScrollable(): Boolean = computeVerticalScrollRange() > computeVerticalScrollExtent()

    // `computeVerticalScrollOffset()/Range()` de LinearLayoutManager son sólo una ESTIMACIÓN basada
    // en la altura media de las filas actualmente visibles, no de la lista entera. Cuando nosotros
    // mismos disparamos el scroll (arrastrando la barra, vía `scrollToPositionWithOffset` en
    // `dragTo`), ya hemos colocado el pulgar con la fracción EXACTA del dedo; si encima dejamos que
    // este listener lo vuelva a colocar con esa estimación, puede no coincidir (p. ej. si las filas
    // donde soltaste tienen una altura media distinta a la del resto: títulos de 1 vs 2 líneas,
    // cabeceras de sección...) y el pulgar "salta" solo justo después de soltar — que es tardío
    // porque `scrollToPositionWithOffset` no aplica el scroll al instante, sino en el siguiente
    // layout pass, así que este onScrolled puede llegar ya con el dedo levantado. Por eso ese caso
    // se ignora aquí con [ignoreNextScrollSync]; el resto de scrolls (arrastre normal de la lista,
    // saltos desde otro sitio) sí deben seguir sincronizando el pulgar.
    var ignoreNextScrollSync = false
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (ignoreNextScrollSync) {
                ignoreNextScrollSync = false
                return
            }
            if (!isScrollable()) return
            val range = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(1)
            moveThumbTo(computeVerticalScrollOffset().toFloat() / range)
            showThumb()
            scheduleHide()
        }
    })

    var isDraggingScrollbar = false

    /** Salta al punto de la lista que corresponde a la altura [touchY] del dedo. */
    fun dragTo(touchY: Float) {
        val layoutManager = layoutManager as? LinearLayoutManager ?: return
        val itemCount = adapter?.itemCount ?: 0
        val fraction = (touchY / height).coerceIn(0f, 1f)
        val targetPosition = (itemCount * fraction).toInt().coerceIn(0, (itemCount - 1).coerceAtLeast(0))

        ignoreNextScrollSync = true
        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
        // Mueve las dos vistas a la vez: la burbuja ya no se coloca a la altura del dedo por su
        // cuenta, va pegada al pulgar.
        moveThumbTo(fraction)
        if (bubble != null && sectionFor != null) bubble.text = sectionFor(targetPosition)
    }

    fun endDrag() {
        if (!isDraggingScrollbar) return
        isDraggingScrollbar = false
        bubble?.animate()?.alpha(0f)?.setDuration(FADE_DURATION_MS)?.start()
        scheduleHide()
    }

    // OJO: esto NO puede ser un `setOnTouchListener`. Un RecyclerView es un ViewGroup, y un
    // ViewGroup reparte el toque a sus hijos ANTES de mirar su propio `OnTouchListener`; como las
    // filas son clicables (tocar una canción la reproduce), la fila se quedaba el ACTION_DOWN y la
    // barra no se enteraba nunca de que empezaba un arrastre. Sólo funcionaba si la lista ya venía
    // moviéndose, porque entonces el RecyclerView intercepta el toque de entrada (para frenar el
    // scroll) y ahí sí llegaba al listener: exactamente el fallo que se veía.
    //
    // Un `OnItemTouchListener` es la vía que ofrece el propio RecyclerView para mirar el toque
    // ANTES que los hijos: se consulta dentro de su `onInterceptTouchEvent`. Si devolvemos `true`
    // en el DOWN, el RecyclerView nos "adjudica" el gesto (manda un CANCEL a la fila que lo
    // estuviera recibiendo) y a partir de ahí todo el gesto nos llega por `onTouchEvent`.
    addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val isOnScrollbar = event.x >= width - hitboxWidthPx
                if (isOnScrollbar && isScrollable()) {
                    isDraggingScrollbar = true
                    showThumb()
                    bubble?.animate()?.cancel()
                    bubble?.alpha = 1f
                    // El fragmento vive dentro de un ViewPager2: sin esto, un temblor horizontal
                    // del dedo mientras arrastramos le haría cambiar de pestaña.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // No se salta ya en el DOWN a propósito: un toque suelto en la franja no debe
                    // teletransportar la lista, sólo el arrastre.
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (isDraggingScrollbar) dragTo(event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    })

    return ScrollbarController(thumb, bubble)
}
