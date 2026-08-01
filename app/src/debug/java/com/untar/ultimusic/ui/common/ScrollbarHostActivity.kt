package com.untar.ultimusic.ui.common

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Activity vacía que sólo existe en la compilación `debug`, para los tests instrumentados: da una
 * ventana de verdad donde meter el [FrameLayout] + RecyclerView que prueba la barra de scroll.
 *
 * Hace falta una ventana real (y no una vista suelta en memoria) porque el RecyclerView necesita
 * estar "attached" para que corran los frames de animación: sin eso ni `smoothScrollBy` avanza ni
 * se disparan los layouts que provoca el arrastre.
 */
class ScrollbarHostActivity : Activity() {

    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}
