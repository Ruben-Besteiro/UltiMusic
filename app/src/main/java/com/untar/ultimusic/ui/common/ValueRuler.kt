package com.untar.ultimusic.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Regla horizontal que se arrastra como una cinta métrica: marcas y números que se mueven con el
 * dedo bajo un indicador fijo en el centro. Lo que quede bajo el indicador es el valor elegido.
 *
 * La usa la pantalla de ajustes (ver [com.untar.ultimusic.ui.settings.SettingsDialogFragment]) como
 * sustituto del slider cuando se desactiva el límite del amplificador. El motivo del cambio es que
 * un slider **dibuja su recorrido**: necesita un máximo cercano para que el dedo tenga un rango
 * cómodo. Cuando el rango pasa a ser enorme, un slider se vuelve inservible (un píxel valdría
 * decenas de unidades) y una cinta que se arrastra, no: el recorrido es tan largo como haga falta.
 *
 * ### Por qué una View propia y no un RecyclerView
 *
 * La idea de «lista horizontal larguísima» en Android sería un `RecyclerView` horizontal, pero aquí
 * **no hay elementos que reciclar** — solo rayas y números. Sale más simple dibujarlo a mano: se
 * guarda cuánto se ha arrastrado y se pintan solo las marcas que caen dentro de la pantalla. Da
 * igual que el valor sea 150 o 900: siempre se pintan las mismas ~30 rayas.
 *
 * ### Cómo funciona
 *
 * El estado es un único número, [scrollPx]: cuántos píxeles se ha desplazado la cinta desde
 * [minValue]. De ahí sale el valor (`minValue + scrollPx / pxPerUnit`) y de ahí sale dónde va cada
 * marca al dibujar.
 *
 * ### Por qué hay un máximo
 *
 * La primera versión de esto no tenía tope por arriba, y estaba **rota**. Sin un máximo, un
 * lanzamiento del dedo podía llevar el desplazamiento a cientos de millones de píxeles, y ahí se
 * rompen dos cosas a la vez: un `Float` solo guarda unos 7 dígitos significativos, así que sumarle
 * el arrastre deja de tener efecto y el valor se queda pillado en un número absurdo; y al convertir
 * a `Int` para el cálculo de la inercia, el número se satura en `Int.MAX_VALUE`.
 *
 * Así que «arrastre sin tope» es una idea de interfaz, no de aritmética: el tope tiene que existir,
 * solo tiene que estar **lo bastante lejos como para que no se note**. Con la separación de marcas
 * de esta regla, ir del mínimo al máximo son varios metros de dedo.
 */
class ValueRuler @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Extremo izquierdo de la cinta. */
    var minValue: Int = 0
        set(newMin) {
            field = newMin
            setValue(currentValue, notify = false)
        }

    /**
     * Extremo derecho. Tiene que existir (ver la nota de la cabecera), pero está pensado para
     * quedar tan lejos que el usuario no llegue nunca por accidente.
     */
    var maxValue: Int = 100
        set(newMax) {
            field = newMax
            setValue(currentValue, notify = false)
        }

    /** Se avisa cada vez que cambia el valor bajo el indicador, arrastrando o por inercia. */
    var onValueChanged: ((Int) -> Unit)? = null

    /** Color del indicador central y de las marcas grandes. Lo fija el acento de la canción. */
    var accentColor: Int = Color.WHITE
        set(newColor) {
            field = newColor
            invalidate()
        }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    /**
     * Cuántos píxeles ocupa una unidad. Cuanto mayor, más fino el ajuste y más hay que arrastrar
     * para subir lo mismo.
     */
    private val pxPerUnit = dp(14f)

    /** Desplazamiento de la cinta, en píxeles, desde [minValue]. Siempre dentro de 0..[maxScrollPx]. */
    private var scrollPx = 0f

    /** Tope del desplazamiento: lo que mide la cinta entera de [minValue] a [maxValue]. */
    private val maxScrollPx: Float
        get() = ((maxValue - minValue).coerceAtLeast(0) * pxPerUnit)

    /** Cada cuántas unidades va una marca grande con su número. */
    private val majorEvery = 10

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
    }

    /**
     * Calcula la inercia: cuando se suelta el dedo con velocidad, la cinta sigue moviéndose y va
     * frenando sola. Es la misma pieza que usan las listas de Android para el "fling".
     */
    private val scroller = OverScroller(context)

    /**
     * Queda un encaje pendiente: el dedo ha soltado y, en cuanto pare la inercia, hay que cuadrar la
     * cinta con la marca más cercana.
     *
     * Hace falta como bandera porque `computeScroll()` se llama en **cada** fotograma que se dibuja,
     * también estando la cinta quieta. Sin ella, el encaje se intentaba una y otra vez sin que nadie
     * lo hubiera pedido, y con la coma flotante el desfase nunca llegaba a ser exactamente cero, así
     * que se reprogramaba a sí mismo sin fin.
     */
    private var pendingSnap = false

    /**
     * Traduce los toques a gestos con sentido (arrastre, lanzamiento). Sin esto habría que medir a
     * mano distancias y velocidades en el `onTouchEvent`.
     */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            // Tocar la cinta corta la inercia en seco, como en cualquier lista.
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            // distanceX es cuánto se ha movido el dedo hacia la IZQUIERDA. Arrastrar a la izquierda
            // trae hacia el centro los valores de la derecha, que son los mayores: por eso se suma.
            scrollPx = (scrollPx + distanceX).coerceIn(0f, maxScrollPx)
            notifyAndDraw()
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            // Ojo al signo: `distanceX` (arriba) es positivo cuando el dedo va a la izquierda, pero
            // `velocityX` es positivo cuando va a la DERECHA. Por eso aquí lleva un menos y allí no.
            scroller.fling(
                scrollPx.roundToInt(), 0,
                -velocityX.roundToInt(), 0,
                0, maxScrollPx.roundToInt(),
                0, 0
            )
            ViewCompat.postInvalidateOnAnimation(this@ValueRuler)
            return true
        }
    })

    /** Valor que hay ahora mismo bajo el indicador. */
    val currentValue: Int
        get() = (minValue + (scrollPx / pxPerUnit).roundToInt()).coerceIn(minValue, maxValue)

    /**
     * Coloca la cinta en [value]. Con [notify] en false no se dispara [onValueChanged]: es lo que
     * hace falta cuando quien manda es el estado (por ejemplo al abrir la pantalla), para no
     * devolverle el mismo valor que acaba de dar.
     */
    fun setValue(value: Int, notify: Boolean = true) {
        scroller.forceFinished(true)
        pendingSnap = false
        scrollPx = ((value - minValue) * pxPerUnit).coerceIn(0f, maxScrollPx)
        lastNotifiedValue = currentValue
        if (notify) onValueChanged?.invoke(currentValue)
        invalidate()
    }

    private var lastNotifiedValue = Int.MIN_VALUE

    /** Redibuja y, si el valor ha cambiado de verdad, avisa. */
    private fun notifyAndDraw() {
        val value = currentValue
        if (value != lastNotifiedValue) {
            lastNotifiedValue = value
            onValueChanged?.invoke(value)
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // El padre puede ser un ScrollView: mientras se arrastra la cinta hay que pedirle que no
        // robe el gesto, o el ajuste se convertiría en un scroll de la pantalla.
        parent?.requestDisallowInterceptTouchEvent(true)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // Al soltar hay que cuadrar la cinta con la marca más cercana, pero puede que el gesto
            // haya dejado inercia: se apunta como pendiente y se hace cuando esta se agote.
            pendingSnap = true
            ViewCompat.postInvalidateOnAnimation(this)
        }
        return handled || super.onTouchEvent(event)
    }

    /**
     * `computeScroll` lo llama Android en cada fotograma que se dibuja. Aquí se lee dónde va la
     * inercia y se pide el siguiente fotograma hasta que se pare; cuando para, se hace el encaje si
     * había uno pendiente.
     */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currX.toFloat().coerceIn(0f, maxScrollPx)
            notifyAndDraw()
            ViewCompat.postInvalidateOnAnimation(this)
        } else if (pendingSnap) {
            pendingSnap = false
            snapToNearest()
        }
    }

    /** Cuadra la cinta con la marca entera más cercana, con una animación cortita. */
    private fun snapToNearest() {
        val target = ((scrollPx / pxPerUnit).roundToInt() * pxPerUnit).coerceIn(0f, maxScrollPx)
        val delta = (target - scrollPx).roundToInt()
        if (delta == 0) {
            scrollPx = target
            invalidate()
            return
        }
        scroller.startScroll(scrollPx.roundToInt(), 0, delta, 0, 150)
        ViewCompat.postInvalidateOnAnimation(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Alto fijo si nadie dice otra cosa: lo justo para las marcas y sus números.
        val desiredHeight = dp(64f).roundToInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val baseline = height * 0.72f          // de dónde cuelgan las marcas
        val minorHeight = dp(12f)
        val majorHeight = dp(24f)

        // Solo se dibuja lo que se ve: se calcula qué unidades caen dentro del ancho de la vista.
        // Esto es lo que hace que arrastrar mucho no cueste nada: da igual lo lejos que se llegue,
        // siempre se pintan las mismas ~30 rayas.
        val halfWidthUnits = (centerX / pxPerUnit)
        val centerUnit = scrollPx / pxPerUnit
        val lastUnit = ceil(centerUnit + halfWidthUnits).toInt().coerceAtMost(maxValue - minValue)
        val firstUnit = floor(centerUnit - halfWidthUnits).toInt().coerceAtLeast(0)

        for (unit in firstUnit..lastUnit) {
            val x = centerX + (unit - centerUnit) * pxPerUnit
            val value = minValue + unit
            val isMajor = value % majorEvery == 0
            tickPaint.color = if (isMajor) accentColor else MUTED_TICK_COLOR
            val tickHeight = if (isMajor) majorHeight else minorHeight
            canvas.drawLine(x, baseline - tickHeight, x, baseline, tickPaint)

            if (isMajor) {
                labelPaint.color = MUTED_TICK_COLOR
                canvas.drawText("$value", x, baseline + dp(16f), labelPaint)
            }
        }

        // Indicador fijo del centro: lo que marque es el valor elegido.
        indicatorPaint.color = accentColor
        canvas.drawLine(centerX, baseline - dp(30f), centerX, baseline + dp(2f), indicatorPaint)
    }

    private companion object {
        /** Gris de las marcas pequeñas y los números (equivale a `um_on_surface_muted`). */
        const val MUTED_TICK_COLOR = 0xFFB0B0B0.toInt()
    }
}
