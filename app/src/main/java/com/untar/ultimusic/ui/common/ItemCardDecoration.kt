package com.untar.ultimusic.ui.common

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.model.Song
import com.untar.ultimusic.ui.CollectionKind
import com.untar.ultimusic.ui.PlayerViewModel
import kotlinx.coroutines.flow.combine
import java.util.WeakHashMap
import kotlinx.coroutines.launch
import com.untar.ultimusic.util.DynamicColor

/**
 * Pinta detrás de cada elemento de un RecyclerView un rectángulo de esquinas redondeadas con el
 * color dinámico en su versión oscura ([DynamicColor.dark]), y deja un pequeño hueco entre elementos
 * para que se lean como tarjetas.
 *
 * Es una decoración y no un `android:background` en cada item_*.xml para que el color siga al
 * acento sin tocar ningún adaptador: quien cambie el acento llama a [setAccentColor] (lo hace
 * [ScrollbarController.setAccentColor], que ya se invoca en todas las pantallas de lista).
 *
 * Los elementos que no deben tener tarjeta (cabeceras, filas de información) llevan
 * `android:tag="nocard"` en su XML.
 *
 * Los hijos se recortan al mismo contorno redondeado para que el ripple de pulsación no se salga
 * por las esquinas.
 */
internal class ItemCardDecoration(density: Float) : RecyclerView.ItemDecoration() {

    private val radius = RADIUS_DP * density
    private val marginH = MARGIN_H_DP * density
    private val marginV = MARGIN_V_DP * density
    private val edge = (EDGE_DP * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DynamicColor.dark(DynamicColor.DEFAULT) }
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var accent = DynamicColor.DEFAULT

    /** Dice si la tarjeta de la posición dada es la de lo que suena (ver [highlightNowPlaying]). */
    var highlight: ((Int) -> Boolean)? = null

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /** Cambia el acento. El fundido lo hace quien emite el color (ver PlayerViewModel.accentColor). */
    fun setAccentColor(accent: Int, recycler: RecyclerView) {
        if (this.accent == accent) return
        this.accent = accent
        recycler.invalidate()
    }

    private fun hasCard(view: View) = view.tag != NO_CARD

    // La imagen borrosa se coloca según la posición del RecyclerView en pantalla, pero esa posición
    // también cambia sin que el RecyclerView se redibuje (al deslizar entre pestañas se mueve el
    // ViewPager2, no la lista) y entonces se reutilizaría el dibujo anterior, con la imagen
    // congelada. Antes de cada fotograma se comprueba si se ha movido y, si sí, se invalida.
    private var usesBlur = false
    private val drawnAt = IntArray(2)
    private val nowAt = IntArray(2)
    private var watched: RecyclerView? = null
    private val moveWatcher = android.view.ViewTreeObserver.OnPreDrawListener {
        val view = watched
        if (view != null && usesBlur) {
            view.getLocationOnScreen(nowAt)
            if (nowAt[0] != drawnAt[0] || nowAt[1] != drawnAt[1]) view.invalidate()
        }
        true
    }

    private fun watchPosition(parent: RecyclerView) {
        if (watched === parent) return
        watched = parent
        parent.viewTreeObserver.addOnPreDrawListener(moveWatcher)
        parent.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // El ViewTreeObserver cambia al reenganchar la vista: se vuelve a registrar.
                if (watched === v) v.viewTreeObserver.addOnPreDrawListener(moveWatcher)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(moveWatcher)
            }
        })
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        watchAdapter(parent)
        val lm = parent.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val position = parent.getChildAdapterPosition(view)
        val count = state.itemCount
        if (!hasCard(view)) return
        var top = marginV.toInt()
        var bottom = marginV.toInt()
        // Hueco extra antes de la primera fila y después de la última, en todas las listas por
        // igual (antes cada layout lo ponía a mano con paddingTop, y solo algunos). Las cabeceras
        // sin tarjeta (resumen de Canciones, secciones de la búsqueda) llevan su propio hueco en
        // el XML, así que aquí no se tocan.
        if (lm != null && lm.orientation == RecyclerView.VERTICAL && position != RecyclerView.NO_POSITION && count > 0) {
            val grid = lm as? androidx.recyclerview.widget.GridLayoutManager
            val row = grid?.spanSizeLookup?.getSpanGroupIndex(position, grid.spanCount) ?: position
            val lastRow = grid?.spanSizeLookup?.getSpanGroupIndex(count - 1, grid.spanCount) ?: (count - 1)
            if (row == 0) top += edge
            if (row == lastRow) bottom += edge
        }
        outRect.set(marginH.toInt(), top, marginH.toInt(), bottom)
    }

    // El hueco del último depende de cuántos elementos haya: al añadir o quitar filas, la que
    // antes era la última no se vuelve a medir sola y se quedaría con el hueco (o sin él).
    private var watchedAdapter: RecyclerView.Adapter<*>? = null
    private var adapterRecycler: RecyclerView? = null
    private val invalidateDecorations = Runnable {
        adapterRecycler?.let { if (!it.isComputingLayout) it.invalidateItemDecorations() }
    }
    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = schedule()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()
        private fun schedule() {
            val recycler = adapterRecycler ?: return
            recycler.removeCallbacks(invalidateDecorations)
            recycler.post(invalidateDecorations)
        }
    }

    private fun watchAdapter(parent: RecyclerView) {
        val adapter = parent.adapter
        if (adapter === watchedAdapter) return
        watchedAdapter?.unregisterAdapterDataObserver(adapterObserver)
        watchedAdapter = adapter
        adapterRecycler = parent
        adapter?.registerAdapterDataObserver(adapterObserver)
    }

    // Este método es el que dibuja la decoración detrás de cada hijo del RecyclerView.
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // Reloj real (como el ValueAnimator del acento): si hay lag, el fundido no se estira.
        val now = System.nanoTime()
        var animating = false
        // Si esta ventana tiene la imagen de fondo (la actividad principal), las tarjetas llevan
        // dentro su versión borrosa y el color encima semitransparente; si no (diálogos), opacas.
        val background = BlurredBackground.backgroundOf(parent)
        val blurShader = background?.let {
            BlurredBackground.get(parent.context).shaderFor(it, parent)
        }
        blurPaint.shader = blurShader
        usesBlur = blurShader != null
        // El rebote por defecto (estiramiento en Android 12+) deforma lo dibujado por la lista pero no
        // la imagen de fondo de detrás: se desactiva cuando hay imagen.
        if (usesBlur && parent.overScrollMode != View.OVER_SCROLL_NEVER) {
            parent.overScrollMode = View.OVER_SCROLL_NEVER
        }
        parent.getLocationOnScreen(drawnAt)
        watchPosition(parent)
        val colorAlpha = if (blurShader != null) CARD_ALPHA else 1f
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (!hasCard(child)) continue
            if (child.outlineProvider !== outline) {
                child.outlineProvider = outline
                child.clipToOutline = true
            }
            rect.set(
                child.left + child.translationX,
                child.top + child.translationY,
                child.right + child.translationX,
                child.bottom + child.translationY
            )
            // Lo que suena (o está en pausa) lleva el color difuso (hay texto encima, y es el mismo
            // de la cabecera de las fichas) en vez de su versión oscura.
            val position = parent.getChildAdapterPosition(child)
            val playing = position != RecyclerView.NO_POSITION && highlight?.invoke(position) == true
            val amount = stepHighlight(child, position, playing, now)
            if (amount != (if (playing) 1f else 0f)) animating = true
            // Misma curva que el fundido del acento (un ValueAnimator, que por defecto acelera y
            // frena): con una lineal este parecía terminar antes aunque durase lo mismo.
            paint.color = ColorUtils.blendARGB(DynamicColor.dark(accent), DynamicColor.asBackground(accent),easing.getInterpolation(amount))
            if (blurShader != null) {
                blurPaint.alpha = (child.alpha * 255).toInt()
                c.drawRoundRect(rect, radius, radius, blurPaint)
            }
            paint.alpha = (child.alpha * 255 * colorAlpha).toInt()
            c.drawRoundRect(rect, radius, radius, paint)
        }
        // Mientras algún fundido siga en marcha hay que volver a dibujar en el siguiente fotograma.
        if (animating) parent.postInvalidateOnAnimation()
    }

    /**
     * Cuánto de "resaltado" (0 = oscuro, 1 = acento a secas) lleva la tarjeta de [child] ahora,
     * acercándolo a su destino en [step] por fotograma, de modo que el fundido dura [FADE_MS].
     *
     * El estado va por vista y recuerda la posición con la que se calculó: si la vista se ha
     * reciclado para otra fila (scroll) o la fila se ha movido, no se funde, salta directa al
     * destino, para no ver a una tarjeta aclararse o oscurecerse al pasar por la pantalla.
     */
    private fun stepHighlight(child: View, position: Int, playing: Boolean, now: Long): Float {
        val target = if (playing) 1f else 0f
        val state = highlightState.getOrPut(child) { HighlightState(position, target, target, now) }
        if (state.position != position) {
            state.position = position
            state.amount = target
            state.target = target
        } else if (state.target != target) {
            // Nuevo destino: el fundido arranca ahora desde donde estuviera.
            state.target = target
            state.from = state.amount
            state.startNanos = now
        }
        if (state.amount != state.target) {
            val t = ((now - state.startNanos) / 1_000_000f / FADE_MS).coerceIn(0f, 1f)
            state.amount = if (t >= 1f) state.target else state.from + (state.target - state.from) * t
        }
        return state.amount
    }

    private class HighlightState(var position: Int, var amount: Float, var target: Float, var startNanos: Long) {
        var from = amount
    }

    private val highlightState = WeakHashMap<View, HighlightState>()
    private val easing = FastOutSlowInInterpolator()

    private companion object {
        const val NO_CARD = "nocard"
        const val FADE_MS = 300L
        /** Opacidad del color de la tarjeta cuando hay imagen de fondo detrás (1 = opaca). */
        const val CARD_ALPHA = 0.7f
        const val RADIUS_DP = 14f
        const val MARGIN_H_DP = 3f
        const val MARGIN_V_DP = 3f
        /** Hueco extra encima de la primera fila y debajo de la última. */
        const val EDGE_DP = 10f
    }
}

/**
 * Hace que la tarjeta de lo que suena (o está en pausa) se pinte con el acento sin oscurecer.
 *
 * [matches] decide, para la fila de [position], si es lo que suena según la canción actual, el tipo
 * de colección desde la que se reproduce y el nombre de ésta. Se evalúa al dibujar; aquí solo se
 * repinta cuando cualquiera de los tres cambia.
 */
fun ScrollbarController.highlightNowPlaying(
    owner: LifecycleOwner,
    playerViewModel: PlayerViewModel,
    matches: (position: Int, song: Song, kind: CollectionKind?, name: String?) -> Boolean
) {
    setHighlight { position ->
        if (playerViewModel.currentSong.value == null) return@setHighlight false
        // La canción resaltada va con el fundido del acento, no con currentSong (ver highlightSong).
        val song = playerViewModel.highlightSong.value ?: return@setHighlight false
        matches(position, song, playerViewModel.currentCollectionKind.value, playerViewModel.currentPlaylistName.value)
    }
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(
                playerViewModel.currentSong,
                playerViewModel.highlightSong,
                playerViewModel.currentCollectionKind,
                playerViewModel.currentPlaylistName
            ) { _, _, _, _ -> }.collect { refreshHighlight() }
        }
    }
}
