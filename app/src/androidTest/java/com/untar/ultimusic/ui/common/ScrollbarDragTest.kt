package com.untar.ultimusic.ui.common

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Comprueba que la barra de scroll que pega [attachScrollbarDrag] se deja arrastrar **con la lista
 * parada**, que es justo el fallo que tenía: sólo respondía si la lista ya venía moviéndose.
 *
 * No lanza la app entera: monta a mano la misma jerarquía que usan los fragmentos (un
 * [FrameLayout] con un [RecyclerView] dentro) en la ventana de [ScrollbarHostActivity] y le manda
 * [MotionEvent]s reales. Así se recorre exactamente el mismo camino de reparto de toques que en la
 * app (`ViewGroup.dispatchTouchEvent` → `onInterceptTouchEvent` → hijos), que es donde está el
 * problema.
 *
 * Los items se hacen `clickable` a propósito: en la app las filas tienen `OnClickListener` (tocar
 * una canción la reproduce), y ese detalle es precisamente lo que se come el toque inicial.
 */
@RunWith(AndroidJUnit4::class)
class ScrollbarDragTest {

    private companion object {
        const val ITEM_COUNT = 500
        const val ITEM_HEIGHT_PX = 120
        /** Margen de items que se acepta entre donde debería caer el arrastre y donde cae. */
        const val TOLERANCE = 10
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var scenario: ActivityScenario<ScrollbarHostActivity>
    private lateinit var recycler: RecyclerView

    /** Última posición que se le pidió a la lambda de secciones (la que alimenta la burbuja). */
    @Volatile
    private var lastSectionPosition = -1

    private var hitboxPx = 0

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ScrollbarHostActivity::class.java)
        scenario.onActivity { activity ->
            recycler = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = ClickableAdapter()
            }
            activity.container.addView(
                recycler,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            recycler.attachScrollbarDrag { position ->
                lastSectionPosition = position
                sectionLetter("Item $position")
            }
            hitboxPx = (24 * activity.resources.displayMetrics.density).toInt()
        }
        // Esperar a que el RecyclerView esté medido y con hijos antes de tocarlo.
        waitUntil("el RecyclerView no llegó a pintarse") {
            var ready = false
            scenario.onActivity { ready = recycler.height > 0 && recycler.childCount > 0 }
            ready
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    // --- utilidades ----------------------------------------------------------------------------

    /** Reintenta [condition] durante un segundo; el sistema pinta por frames, no al instante. */
    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 1000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(16)
        }
        if (!condition()) throw AssertionError(message)
    }

    private var downTime = 0L

    private fun send(action: Int, x: Float, y: Float) {
        scenario.onActivity { activity ->
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            activity.container.dispatchTouchEvent(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    /** Arrastra de [fromY] a [toY] sobre la vertical [x], en pasos pequeños como haría un dedo. */
    private fun drag(x: Float, fromY: Float, toY: Float, steps: Int = 12) {
        send(MotionEvent.ACTION_DOWN, x, fromY)
        for (step in 1..steps) {
            send(MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / steps)
            SystemClock.sleep(16)
        }
        send(MotionEvent.ACTION_UP, x, toY)
    }

    private fun firstVisiblePosition(): Int {
        var position = -1
        scenario.onActivity {
            position = (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        }
        return position
    }

    private fun recyclerSize(): Pair<Int, Int> {
        var size = 0 to 0
        scenario.onActivity { size = recycler.width to recycler.height }
        return size
    }

    /** Centro de la franja sensible de la derecha. */
    private fun scrollbarX(): Float = (recyclerSize().first - hitboxPx / 2).toFloat()

    /** Arrastra la barra hasta [fraction] de la altura y comprueba dónde acaba la lista. */
    private fun assertBarDragReaches(fraction: Float) {
        val height = recyclerSize().second
        drag(scrollbarX(), fromY = 20f, toY = height * fraction)

        val expected = (ITEM_COUNT * fraction).toInt()
        var actual = firstVisiblePosition()
        waitUntil("(esperando a que asiente el scroll)") {
            actual = firstVisiblePosition()
            abs(actual - expected) <= TOLERANCE
        }
        assertTrue(
            "la lista debería haber saltado a ~$expected pero se quedó en $actual",
            abs(actual - expected) <= TOLERANCE
        )
    }

    // --- tests ---------------------------------------------------------------------------------

    /**
     * EL FALLO: dedo abajo sobre la barra con la lista quieta y arrastre hasta el 80% de la
     * pantalla. La lista debería saltar a ~el 80% de los items.
     */
    @Test
    fun dragFromRestJumpsThroughTheList() {
        assertEquals("la lista debería empezar arriba del todo", 0, firstVisiblePosition())
        assertBarDragReaches(0.8f)
    }

    /**
     * DIAGNÓSTICO: lo mismo pero con la lista ya moviéndose al poner el dedo. Si éste pasa y el
     * anterior falla, el problema es exactamente el que se describe: la barra sólo agarra cuando el
     * RecyclerView ya estaba interceptando los toques.
     */
    @Test
    fun dragWhileAlreadyScrollingJumpsThroughTheList() {
        scenario.onActivity { recycler.smoothScrollBy(0, 8000) }
        SystemClock.sleep(100) // que le dé tiempo a ponerse en marcha
        assertBarDragReaches(0.8f)
    }

    /** Durante el arrastre hay que pedirle la letra a la lambda para poder pintar la burbuja. */
    @Test
    fun dragFeedsTheSectionBubble() {
        val height = recyclerSize().second
        drag(scrollbarX(), fromY = 20f, toY = height * 0.5f)

        assertTrue(
            "no se llegó a pedir la letra de sección (posición $lastSectionPosition)",
            lastSectionPosition > 0
        )
    }

    /** Un arrastre normal por el centro de la lista tiene que seguir haciendo scroll de toda la vida. */
    @Test
    fun dragOnTheListBodyStillScrollsNormally() {
        val (width, height) = recyclerSize()
        drag(x = width / 2f, fromY = height * 0.8f, toY = height * 0.2f)

        waitUntil("el arrastre normal no movió la lista") { firstVisiblePosition() > 0 }
    }

    /** Y tocar fuera de la franja no debe provocar el salto de la barra. */
    @Test
    fun dragOutsideTheHitboxDoesNotJump() {
        val height = recyclerSize().second
        // Arrastre vertical corto pegado al borde izquierdo: mueve la lista un poco, pero nunca
        // debe teletransportarla al 80% como haría la barra.
        drag(x = 10f, fromY = 20f, toY = height * 0.8f)

        val actual = firstVisiblePosition()
        assertTrue(
            "un arrastre a la izquierda no debe saltar por la lista (acabó en $actual)",
            actual < ITEM_COUNT / 4
        )
    }

    // --- adaptador de mentira ------------------------------------------------------------------

    private class ClickableAdapter : RecyclerView.Adapter<ClickableAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ITEM_HEIGHT_PX
                )
                isClickable = true
                setOnClickListener { /* como las filas de canciones de la app */ }
            }
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = Unit
        override fun getItemCount() = ITEM_COUNT
    }
}
