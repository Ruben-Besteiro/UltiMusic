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
 * color dinámico en su versión oscura ([DynamicColor.dim]), y deja un pequeño hueco entre elementos
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DynamicColor.dim(DynamicColor.DEFAULT) }
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

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (!hasCard(view)) return
        outRect.set(marginH.toInt(), marginV.toInt(), marginH.toInt(), marginV.toInt())
    }

    // Este método es el que dibuja la decoración detrás de cada hijo del RecyclerView.
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val now = System.nanoTime()
        // Fracción del fundido que corresponde al tiempo desde el fotograma anterior (con tope por
        // si la pantalla estuvo quieta, para que el primer fotograma no se salte el fundido entero).
        val elapsedMs = if (lastFrameNanos == 0L) 0L else (now - lastFrameNanos) / 1_000_000
        lastFrameNanos = now
        val step = minOf(elapsedMs, MAX_FRAME_MS) / FADE_MS.toFloat()
        var animating = false
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
            // Lo que suena (o está en pausa) lleva el acento a secas en vez de su versión oscura.
            val position = parent.getChildAdapterPosition(child)
            val playing = position != RecyclerView.NO_POSITION && highlight?.invoke(position) == true
            val amount = stepHighlight(child, position, playing, step)
            if (amount != (if (playing) 1f else 0f)) animating = true
            // Misma curva que el fundido del acento (un ValueAnimator, que por defecto acelera y
            // frena): con una lineal este parecía terminar antes aunque durase lo mismo.
            paint.color = ColorUtils.blendARGB(DynamicColor.dim(accent), accent, easing.getInterpolation(amount))
            paint.alpha = (child.alpha * 255).toInt()
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
    private fun stepHighlight(child: View, position: Int, playing: Boolean, step: Float): Float {
        val target = if (playing) 1f else 0f
        val state = highlightState.getOrPut(child) { HighlightState(position, target) }
        if (state.position != position) {
            state.position = position
            state.amount = target
        } else if (state.amount < target) {
            state.amount = minOf(target, state.amount + step)
        } else if (state.amount > target) {
            state.amount = maxOf(target, state.amount - step)
        }
        return state.amount
    }

    private class HighlightState(var position: Int, var amount: Float)

    private val highlightState = WeakHashMap<View, HighlightState>()
    private var lastFrameNanos = 0L
    private val easing = FastOutSlowInInterpolator()

    private companion object {
        const val NO_CARD = "nocard"
        const val FADE_MS = 300L
        const val MAX_FRAME_MS = 50L
        const val RADIUS_DP = 14f
        const val MARGIN_H_DP = 3f
        const val MARGIN_V_DP = 3f
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
        val song = playerViewModel.currentSong.value ?: return@setHighlight false
        matches(position, song, playerViewModel.currentCollectionKind.value, playerViewModel.currentPlaylistName.value)
    }
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(
                playerViewModel.currentSong,
                playerViewModel.currentCollectionKind,
                playerViewModel.currentPlaylistName
            ) { _, _, _ -> }.collect { refreshHighlight() }
        }
    }
}
