package com.untar.ultimusic.ui.common

import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Bloquea toda la interacción mientras se está abriendo un diálogo. Sin esto, pulsar muy rápido un
 * botón que abre un diálogo (iPod, añadir letra/vídeo en el editor...) lo abría varias veces, porque
 * la ventana del diálogo tarda unos frames en aparecer y hasta entonces los toques siguen llegando
 * al botón.
 *
 * El bloqueo se arma cuando un [DialogFragment] se engancha al [FragmentManager] y se suelta cuando
 * llega a RESUMED más un margen ([EXTRA_MS]) para que acabe su animación de entrada. Mientras está
 * armado, la Activity y la ventana de cada diálogo se tragan los toques (ver [wrap]).
 *
 * Se excluye el iPod abierto arrastrando el mini-reproductor: se crea a mitad de un gesto y
 * bloquearlo lo rompería.
 */
object DialogOpenLock {
    private const val EXTRA_MS = 250L
    private const val MAX_MS = 1500L // red de seguridad por si un diálogo nunca llega a RESUMED

    private var pendingUntil = 0L
    private val opening = HashSet<Fragment>()
    private var releaseAt = 0L

    val locked: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            if (opening.isNotEmpty() && now > pendingUntil) opening.clear()
            return opening.isNotEmpty() || now < releaseAt
        }

    fun install(fm: FragmentManager) {
        fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentAttached(
                fm: FragmentManager, f: Fragment, context: android.content.Context,
            ) {
                if (f !is DialogFragment || f.arguments?.getBoolean("suppress_enter_anim") == true) return
                opening.add(f)
                pendingUntil = SystemClock.uptimeMillis() + MAX_MS
            }

            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment) f.dialog?.window?.let { wrap(it) }
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (opening.remove(f)) releaseAt = SystemClock.uptimeMillis() + EXTRA_MS
            }

            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                if (opening.remove(f)) releaseAt = SystemClock.uptimeMillis() + EXTRA_MS
            }
        }, true)
    }

    /** Envuelve el callback de la ventana para tragarse los toques mientras [locked]. */
    fun wrap(window: Window) {
        val base = window.callback ?: return
        if (base is LockCallback) return
        window.callback = LockCallback(base)
    }

    private class LockCallback(private val base: Window.Callback) : Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean =
            if (locked) true else base.dispatchTouchEvent(event)
    }
}
