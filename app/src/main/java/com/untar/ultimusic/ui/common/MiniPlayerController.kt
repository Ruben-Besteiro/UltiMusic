package com.untar.ultimusic.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.untar.ultimusic.R
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.player.IPodDialogFragment
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.CoverArt
import com.untar.ultimusic.util.CoverLoader
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Fracción de la altura de pantalla que hay que arrastrar el mini-reproductor hacia arriba para
 * que, al soltar **despacio** (por debajo de [MIN_FLING_VELOCITY_DP]), el iPod termine de abrirse
 * en vez de volver a esconderse (ver [MiniPlayerController.setupDrag]).
 */
private const val OPEN_COMMIT_FRACTION = 0.5f

/**
 * Umbral de velocidad, en dp/segundo, que separa un arrastre "lento" (se decide por
 * [OPEN_COMMIT_FRACTION]) de uno "rápido" o *fling* (se decide solo por la dirección, venga de
 * donde venga el dedo). Es el mismo valor que usa por defecto la librería AndroidSlidingUpPanel de
 * la que Phonograph se sirve para este mismo gesto (ver [MiniPlayerController.setupDrag]).
 */
private const val MIN_FLING_VELOCITY_DP = 400f

/**
 * Tag de [FragmentManager] bajo el que vive el iPod, tanto si se abrió con un toque como
 * arrastrando (ver [MiniPlayerController.bind] y [MiniPlayerController.setupDrag]). Es el mismo tag
 * que usan [com.untar.ultimusic.ui.library.GenresFragment] y
 * [com.untar.ultimusic.ui.playlists.PlaylistsFragment] al abrirlo en modo navegación, así que sirve
 * igual para evitar que dos vías distintas lo abran a la vez por duplicado.
 */
private const val IPOD_TAG = "ipod"

/**
 * Engancha un `@layout/view_mini_player` (más su barra de progreso, un [SeekBar] aparte que cada
 * pantalla declara justo encima) a lo que suena en [PlayerViewModel]: título, botón de play/pausa,
 * arrastre para abrir el iPod en vivo y el color dinámico de la canción.
 *
 * Antes esto vivía solo en [com.untar.ultimusic.ui.MainActivity], que es la única pantalla que
 * siempre lo tuvo. Ahora también lo usa [com.untar.ultimusic.ui.library.DetailDialogFragment] (la
 * ficha de álbum/artista/productor), la única ventana a pantalla completa que también lleva
 * mini-reproductor; el resto (buscador, editores, ajustes, iPod) se abren sin él.
 *
 * [fragmentManager] es el que se usa para mostrar el iPod al tocar el mini-reproductor o al
 * arrastrarlo hacia arriba: en la actividad es `supportFragmentManager`, y en un [DialogFragment]
 * a pantalla completa que cuelga directamente de ella (como la ficha de detalle) es
 * `parentFragmentManager`, que es ese mismo `supportFragmentManager`.
 */
class MiniPlayerController(
    private val miniPlayer: View,
    private val progress: SeekBar,
    private val playerViewModel: PlayerViewModel,
    private val fragmentManager: FragmentManager,
    private val lifecycleOwner: LifecycleOwner
) {
    private val context: Context get() = miniPlayer.context

    fun bind() {
        val title = miniPlayer.findViewById<TextView>(R.id.miniTitle)
        val playPause = miniPlayer.findViewById<ImageButton>(R.id.btnPlayPause)
        val expand = miniPlayer.findViewById<ImageView>(R.id.btnExpand)
        val loader = CoverLoader.get(context)

        // Sin nada en reproducción (arranque en limpio, "Toca donde quieras...") el botón lleva el
        // icono de aleatorio (ver el collect de currentSong+isPlaying más abajo) y, en vez de
        // pausa/play, arranca una canción al azar de la biblioteca que no esté en la lista gris;
        // a partir de ahí currentSong deja de ser null y el botón vuelve a comportarse normal.
        playPause.setOnClickListener {
            if (playerViewModel.currentSong.value == null) {
                playerViewModel.playRandomFromLibrary()
            } else {
                playerViewModel.togglePlayPause()
            }
        }
        // Igual que en Phonograph, todo el mini-reproductor es una única zona de toque que abre el
        // iPod a pantalla completa (animación de ventana normal, ver [IPodDialogFragment.onStart]);
        // el botón de play/pausa, al tener su propio listener, no propaga el toque hacia aquí.
        // El guard de findFragmentByTag evita que pinchar varias veces muy deprisa (antes de que el
        // primer show() llegue a registrar el fragmento) abra varias copias apiladas del iPod; es el
        // mismo guard que ya usan GenresFragment y PlaylistsFragment al abrirlo en modo navegación.
        val openIPod = View.OnClickListener {
            if (fragmentManager.findFragmentByTag(IPOD_TAG) == null) {
                IPodDialogFragment().show(fragmentManager, IPOD_TAG)
            }
        }
        miniPlayer.setOnClickListener(openIPod)
        expand.setOnClickListener(openIPod)
        // También se puede abrir arrastrando el mini-reproductor hacia arriba, siguiendo el dedo en
        // vivo (ver [setupDrag]).
        setupDrag()

        // Arrastrar/tocar la barra mueve la reproducción. Mientras el usuario la toca, no dejamos
        // que la actualización periódica pise su posición.
        var userSeeking = false
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                playerViewModel.seekToFraction(sb.progress / 1000f)
            }
        })

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Se combina con coverRevision (ver su comentario en PlaybackService) para que
                    // editar la carátula de la canción que suena la recargue aquí también, aunque el
                    // nombre de archivo se reutilice y currentSong no cambie "de verdad".
                    combine(playerViewModel.currentSong, playerViewModel.coverRevision) { song, _ -> song }
                        .collect { song ->
                        title.text = song?.title ?: context.getString(R.string.nothing_playing)
                        // Sin nada en reproducción no hay nada que arrastrar: se deshabilita la barra.
                        progress.isEnabled = song != null
                        // Sin nada en reproducción no hay reproductor que expandir: ocultamos la carátula.
                        expand.isVisible = song != null
                        if (song != null) {
                            expand.load(CoverArt.cover(context, song), loader) {
                                error(R.drawable.cover_placeholder)
                            }
                        }
                    }
                }
                launch {
                    // Sin canción, el botón muestra el icono de aleatorio en vez de play (ver su
                    // listener más arriba); con canción, el play/pausa de siempre.
                    combine(playerViewModel.currentSong, playerViewModel.isPlaying) { song, isPlaying ->
                        song to isPlaying
                    }.collect { (song, isPlaying) ->
                        playPause.setImageResource(
                            when {
                                song == null -> R.drawable.ic_shuffle
                                isPlaying -> R.drawable.ic_pause
                                else -> R.drawable.ic_play
                            }
                        )
                    }
                }
                launch {
                    playerViewModel.progress.collect { p ->
                        if (!userSeeking) {
                            progress.progress =
                                if (p.durationMs > 0) ((p.positionMs * 1000) / p.durationMs).toInt()
                                else 0
                        }
                    }
                }
                // Tiñe con el color de la canción que suena la barra de progreso y el botón de
                // play/pausa del mini-reproductor, igual que en el resto de la pantalla que lo aloje.
                // btnExpand ya no es un icono: ahora es la carátula, así que no se tiñe.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        progress.progressTintList = ColorStateList.valueOf(accent)
                        AccentTint.icons(miniPlayer, accent, R.id.btnPlayPause)
                    }
                }
            }
        }
    }

    /**
     * Arrastrar el mini-reproductor hacia arriba abre el iPod en vivo, siguiendo el dedo: la
     * ventana se crea ya al primer movimiento (escondida bajo la pantalla) y
     * [IPodDialogFragment.followOpenDrag] la va destapando según se arrastra, así que a mitad de
     * arrastre se ve la mitad del iPod, igual que al cerrarlo arrastrando hacia abajo (ver
     * [IPodDialogFragment.animateClose]).
     *
     * Al soltar, [IPodDialogFragment.finishOpenDrag] decide si se completa la apertura o si se
     * vuelve a esconder. Igual que en Phonograph (la librería AndroidSlidingUpPanel que usa para
     * este mismo gesto), la decisión depende de la velocidad con la que se suelta, medida con un
     * [VelocityTracker]: si se supera [MIN_FLING_VELOCITY_DP] (un *fling*), gana la dirección del
     * gesto sin importar hasta dónde se había arrastrado; si se suelta despacio, se decide por si
     * se ha superado [OPEN_COMMIT_FRACTION] de la altura de la pantalla.
     *
     * Arrastrar horizontalmente, en cambio, no abre nada: hace lo mismo que los botones de
     * anterior/siguiente del iPod ([PlayerViewModel.skipToPrevious]/[PlayerViewModel.skipToNext]).
     * En el primer movimiento que supera el touch slop se decide, por el eje dominante (el que se
     * haya movido más), si el gesto es vertical (abrir) u horizontal (saltar de canción); una vez
     * decidido ya no cambia durante ese toque. El salto horizontal no se dispara hasta que el
     * usuario suelta el dedo (ACTION_UP): mientras arrastra solo se marca que el gesto es
     * horizontal, sin decidir todavía la dirección, así que arrastrar de vuelta antes de soltar
     * puede cambiar a qué lado se salta.
     *
     * No se reutiliza [attachVerticalDrag] porque esa utilidad traslada la propia vista que se
     * engancha; aquí, en cambio, hay que trasladar una ventana distinta (la del iPod, que ni
     * siquiera existe hasta que se empieza a arrastrar), así que hace falta su propio manejador de
     * toques.
     */
    private fun setupDrag() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val minFlingVelocityPx = MIN_FLING_VELOCITY_DP * context.resources.displayMetrics.density
        var startX = 0f
        var startY = 0f
        var dragging = false
        var horizontalHandled = false
        var dragFragment: IPodDialogFragment? = null
        var velocityTracker: VelocityTracker? = null

        miniPlayer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    dragging = false
                    horizontalHandled = false
                    dragFragment = null
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && !horizontalHandled && abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                        horizontalHandled = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (!dragging && !horizontalHandled && dy < 0 && abs(dy) > touchSlop && abs(dy) > abs(dx) &&
                        fragmentManager.findFragmentByTag(IPOD_TAG) == null
                    ) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Se crea ya en el primer movimiento, escondida bajo la pantalla (fracción
                        // 0), para poder seguir el dedo desde el principio (ver
                        // [IPodDialogFragment.forDrag]).
                        dragFragment = IPodDialogFragment.forDrag().also {
                            it.show(fragmentManager, IPOD_TAG)
                            fragmentManager.executePendingTransactions()
                            it.followOpenDrag(0f)
                        }
                    }
                    if (dragging) {
                        val fraction = (-dy / context.resources.displayMetrics.heightPixels).coerceIn(0f, 1f)
                        dragFragment?.followOpenDrag(fraction)
                        true
                    } else {
                        horizontalHandled
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    val handled = if (dragging) {
                        dragging = false
                        val dy = event.rawY - startY
                        val fraction = (-dy / context.resources.displayMetrics.heightPixels).coerceIn(0f, 1f)
                        // computeCurrentVelocity(1000) da la velocidad en px/segundo; se invierte
                        // igual que el "direction" de AndroidSlidingUpPanel para que un dedo
                        // moviéndose hacia arriba (abrir) dé un valor positivo.
                        velocityTracker?.computeCurrentVelocity(1000)
                        val flingVelocity = -(velocityTracker?.yVelocity ?: 0f)
                        val committed = when {
                            flingVelocity > minFlingVelocityPx -> true
                            flingVelocity < -minFlingVelocityPx -> false
                            else -> fraction >= OPEN_COMMIT_FRACTION
                        }
                        dragFragment?.finishOpenDrag(committed)
                        dragFragment = null
                        true
                    } else if (horizontalHandled) {
                        // El salto de canción se dispara aquí, al soltar, no durante el arrastre
                        // (ver comentario de [setupDrag]). Se decide por la posición final del
                        // dedo, así que arrastrar de vuelta antes de soltar puede cambiar la
                        // dirección respecto a la que tenía al cruzar el touch slop.
                        horizontalHandled = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val dx = event.rawX - startX
                            if (dx > 0) playerViewModel.skipToPrevious() else playerViewModel.skipToNext()
                        }
                        true
                    } else {
                        // No hubo arrastre: fue un toque normal. Al haber un OnTouchListener en esta
                        // vista, su mecanismo interno de detectar toques (el que dispara
                        // setOnClickListener desde dentro de onTouchEvent) nunca llega a ejecutarse,
                        // así que hay que disparar el click a mano para que abra el iPod.
                        if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        false
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    handled
                }
                else -> false
            }
        }
    }
}
