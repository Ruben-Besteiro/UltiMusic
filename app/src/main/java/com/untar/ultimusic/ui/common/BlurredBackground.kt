package com.untar.ultimusic.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.widget.ImageView
import com.untar.ultimusic.R
import com.untar.ultimusic.data.BackgroundPreferences
import com.untar.ultimusic.data.ThemePreferences
import java.lang.ref.WeakReference

/**
 * Versión borrosa de la imagen de fondo de la actividad principal (`@id/mainBackground`), para
 * pintarla dentro de las tarjetas de las listas ([ItemCardDecoration]) y que se vea la imagen a
 * través del rectángulo coloreado, pero difuminada.
 *
 * El desenfoque es barato: se reduce la imagen a unas decenas de píxeles y al ampliarla con filtro
 * bilineal queda suave. Lleva ya aplicado el mismo oscurecimiento que la capa de encima de la
 * imagen original en activity_main.xml, para que casen.
 */
internal class BlurredBackground private constructor(private val bitmap: Bitmap) {

    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()

    /**
     * Coloca el shader para que la imagen borrosa cubra [background] igual que su `centerCrop`, y
     * devuelve el shader en las coordenadas de [target] (la vista sobre la que se va a dibujar).
     */
    fun shaderFor(background: View, target: View): Shader {
        val bg = IntArray(2)
        val tg = IntArray(2)
        background.getLocationOnScreen(bg)
        target.getLocationOnScreen(tg)
        val scale = maxOf(background.width / bitmap.width.toFloat(), background.height / bitmap.height.toFloat())
        val dx = (background.width - bitmap.width * scale) / 2f + (bg[0] - tg[0])
        val dy = (background.height - bitmap.height * scale) / 2f + (bg[1] - tg[1])
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)
        return shader
    }

    companion object {
        @Volatile
        private var instance: BlurredBackground? = null
        private var backgroundRef = WeakReference<ImageView>(null)

        /** La vista de fondo de la ventana de [view], o null si esa ventana no la tiene (diálogos). */
        fun backgroundOf(view: View): ImageView? {
            backgroundRef.get()?.let { if (it.isAttachedToWindow && it.rootView === view.rootView) return it }
            val found = view.rootView.findViewById<ImageView>(R.id.mainBackground)
            if (found != null) backgroundRef = WeakReference(found)
            return found
        }

        /** La imagen ([BackgroundPreferences.selected]) de la que salen [instance] y [light]. */
        @Volatile
        private var builtFor: String? = null

        /** Si [instance] y [light] se calcularon con el tema claro (cambia el velo, ver [SCRIM]). */
        @Volatile
        private var builtLight: Boolean? = null

        /**
         * La versión de las tarjetas. Al cambiar de imagen de fondo sigue devolviendo la anterior
         * hasta que [lightBackground] (lanzada por MainActivity fuera del hilo principal) cambie
         * las dos a la vez; solo se calcula aquí si todavía no hay ninguna.
         */
        fun get(context: Context): BlurredBackground =
            instance ?: create(context, BackgroundPreferences.selected.value).also { instance = it }

        private fun create(context: Context, name: String?): BlurredBackground {
            // Desenfoque de verdad (no solo reducir y ampliar): las líneas finas, como las órbitas,
            // se ven pixeladas si se limitan a ampliarse; difuminadas quedan suaves.
            val result = blurredCopy(context, name, BLUR_WIDTH_PX, BLUR_RADIUS_PX, BLUR_PASSES)
            // Con ThemePreferences y no con getColor(um_background_scrim): el contexto que llega es
            // el de la aplicación, que no sigue el tema elegido en Ajustes.
            Canvas(result).drawColor(if (ThemePreferences.isLight()) SCRIM_LIGHT else SCRIM)
            return BlurredBackground(result)
        }

        private var light: Bitmap? = null

        /**
         * La imagen de fondo con un desenfoque suave, para la imagen que se ve directamente (donde no
         * hay tarjetas encima). Sin oscurecer: de eso se encarga la capa de activity_main.xml. Deja
         * calculada también la versión de las tarjetas ([get]), para que al cambiar de imagen de
         * fondo no se calcule en el hilo principal al pintar. Es pesada: llamarla fuera de él.
         */
        @Synchronized
        fun lightBackground(context: Context): Bitmap {
            val current = BackgroundPreferences.selected.value
            val isLight = ThemePreferences.isLight()
            light?.let { if (builtFor == current && builtLight == isLight) return it }
            val soft = blurredCopy(context, current, LIGHT_WIDTH_PX, LIGHT_RADIUS_PX, LIGHT_PASSES)
            val cards = create(context, current)
            builtFor = current
            builtLight = isLight
            light = soft
            instance = cards
            return soft
        }

        private fun blurredCopy(context: Context, name: String?, width: Int, radius: Int, passes: Int): Bitmap {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            // Una imagen subida que no se pueda leer (borrada a mano, corrupta) cae a la de serie
            // predeterminada.
            val source = BackgroundPreferences.fileFor(name)?.let { BitmapFactory.decodeFile(it.path, options) }
                ?: BitmapFactory.decodeResource(context.resources, BackgroundPreferences.builtInImage(name), options)
            val height = (width * source.height / source.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            if (small !== source) source.recycle()
            val result = small.copy(Bitmap.Config.ARGB_8888, true)
            if (result !== small) small.recycle()
            boxBlur(result, radius, passes)
            return result
        }

        /** Desenfoque de caja separable repetido [passes] veces (aproxima un gaussiano). */
        private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
            val w = bitmap.width
            val h = bitmap.height
            var src = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            var dst = IntArray(w * h)
            repeat(passes) {
                blurLine(src, dst, w, h, radius, horizontal = true)
                blurLine(dst, src, w, h, radius, horizontal = false)
            }
            bitmap.setPixels(src, 0, w, 0, 0, w, h)
        }

        private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            val step = if (horizontal) 1 else w
            val lineStep = if (horizontal) w else 1
            val window = 2 * radius + 1
            for (line in 0 until lines) {
                val base = line * lineStep
                var r = 0
                var g = 0
                var b = 0
                // Ventana inicial centrada en 0, repitiendo el borde.
                for (k in -radius..radius) {
                    val p = src[base + k.coerceIn(0, length - 1) * step]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                for (i in 0 until length) {
                    dst[base + i * step] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                    val add = src[base + (i + radius + 1).coerceIn(0, length - 1) * step]
                    val sub = src[base + (i - radius).coerceIn(0, length - 1) * step]
                    r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                    g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                    b += (add and 0xFF) - (sub and 0xFF)
                }
            }
        }

        /** Ancho al que se reduce la imagen antes de desenfocarla. */
        private const val BLUR_WIDTH_PX = 720

        /** Radio y pasadas del desenfoque: más = más borroso. */
        private const val BLUR_RADIUS_PX = 4
        private const val BLUR_PASSES = 3

        /** Desenfoque suave de la imagen visible: más resolución y radio pequeño. Subir el radio = más borrosa. */
        private const val LIGHT_WIDTH_PX = 1080
        private const val LIGHT_RADIUS_PX = 2
        private const val LIGHT_PASSES = 2

        /** El mismo `@color/um_background_scrim` de la capa de encima de la imagen en activity_main.xml
         *  en el tema oscuro. */
        private const val SCRIM = 0x4D000000

        /** El velo opuesto, el de `values-notnight`: blanco al 30 % que aclara en el tema claro. */
        private const val SCRIM_LIGHT = 0x4DFFFFFF
    }
}
