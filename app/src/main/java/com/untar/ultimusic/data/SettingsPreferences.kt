package com.untar.ultimusic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferencias sencillas de la pantalla de Ajustes que no son de audio (esas viven en
 * `PlaybackService`) ni de la biblioteca en Room. Mismo patrón que [ArtistGroupingPreferences]: un
 * `StateFlow` por ajuste para que cambiarlo se note al instante en toda la aplicación, y
 * SharedPreferences para que sobreviva al reinicio. Todas comparten un único archivo de prefs.
 *
 * [init] la llama [com.untar.ultimusic.UltiMusicApp] antes que cualquier pantalla.
 */
object SettingsPreferences {

    private const val PREFS_NAME = "settings"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            SleepTimerPreferences.load(p)
            ThemePreferences.load(p)
            DynamicColorPreferences.load(p)
            KeepScreenOnPreferences.load(p)
            MinDurationPreferences.load(p)
            ArtistSeparatorPreferences.load(p)
            BackgroundPreferences.load(context.applicationContext, p)
        }
    }

    internal fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }
}

/**
 * Temporizador de apagado: minutos tras los que se pausa la reproducción sola (0 = desactivado, el
 * valor por defecto). La cuenta atrás la lleva `PlaybackService`, no la interfaz, para que siga
 * aunque se cierre la pantalla.
 */
object SleepTimerPreferences {
    private const val KEY = "sleep_timer_minutes"
    private val _minutes = MutableStateFlow(0)
    val minutes: StateFlow<Int> = _minutes

    internal fun load(p: SharedPreferences) {
        _minutes.value = p.getInt(KEY, 0).coerceAtLeast(0)
    }

    fun set(value: Int) {
        val clamped = value.coerceAtLeast(0)
        _minutes.value = clamped
        SettingsPreferences.edit { putInt(KEY, clamped) }
    }
}

/** Tema claro/oscuro. Por defecto, el del sistema. */
object ThemePreferences {
    enum class Mode(val nightMode: Int) {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        DARK(AppCompatDelegate.MODE_NIGHT_YES),
    }

    private const val KEY = "theme_mode"
    private val _mode = MutableStateFlow(Mode.SYSTEM)
    val mode: StateFlow<Mode> = _mode

    internal fun load(p: SharedPreferences) {
        _mode.value = p.getString(KEY, null)?.let { name -> Mode.entries.firstOrNull { it.name == name } }
            ?: Mode.SYSTEM
        AppCompatDelegate.setDefaultNightMode(_mode.value.nightMode)
    }

    /** `true` si ahora mismo se está mostrando el tema claro (el de Ajustes, o el del sistema). */
    fun isLight(): Boolean = when (_mode.value) {
        Mode.LIGHT -> true
        Mode.DARK -> false
        Mode.SYSTEM -> (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** Guarda y aplica: `setDefaultNightMode` recrea solo las Activity vivas si el modo cambia. */
    fun set(value: Mode) {
        if (_mode.value == value) return
        _mode.value = value
        SettingsPreferences.edit { putString(KEY, value.name) }
        AppCompatDelegate.setDefaultNightMode(value.nightMode)
    }
}

/**
 * Color dinámico (el acento sacado de la carátula, ver [com.untar.ultimusic.util.DynamicColor]).
 * Activado por defecto. Apagado, todo usa el amarillo de siempre y no se analiza ninguna carátula,
 * que es lo que lo hace recomendable en móviles de gama baja.
 */
object DynamicColorPreferences {
    private const val KEY = "dynamic_color"
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    internal fun load(p: SharedPreferences) {
        _enabled.value = p.getBoolean(KEY, true)
    }

    fun set(value: Boolean) {
        _enabled.value = value
        SettingsPreferences.edit { putBoolean(KEY, value) }
    }
}

/** Mantener la pantalla encendida mientras se ve el UltiMusicPod. Desactivado por defecto. */
object KeepScreenOnPreferences {
    private const val KEY = "keep_screen_on_pod"
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    internal fun load(p: SharedPreferences) {
        _enabled.value = p.getBoolean(KEY, false)
    }

    fun set(value: Boolean) {
        _enabled.value = value
        SettingsPreferences.edit { putBoolean(KEY, value) }
    }
}

/**
 * Duración mínima (en segundos) para que una canción se liste. Por defecto 60. NO borra nada de la
 * BD: las canciones más cortas solo se ocultan (ver `LibraryRepository.songs`), así que bajar el
 * número las devuelve con todas sus ediciones intactas.
 */
object MinDurationPreferences {
    private const val KEY = "min_duration_seconds"
    const val DEFAULT = 60
    private val _seconds = MutableStateFlow(DEFAULT)
    val seconds: StateFlow<Int> = _seconds

    internal fun load(p: SharedPreferences) {
        _seconds.value = p.getInt(KEY, DEFAULT).coerceAtLeast(0)
    }

    fun set(value: Int) {
        val clamped = value.coerceAtLeast(0)
        _seconds.value = clamped
        SettingsPreferences.edit { putInt(KEY, clamped) }
    }
}

/**
 * Imagen de fondo de la actividad principal, la misma en los dos temas. `null` = la de serie
 * predeterminada; un nombre que empieza por `@`, otra de serie (ver [builtIns]); si no, el nombre de
 * una imagen subida por el usuario, que vive en `filesDir/backgrounds/` (ver [importImage]). Las
 * subidas se guardan ya reducidas y giradas según su EXIF, para no tener que decodificar una foto
 * de 50 MP cada vez que se recalcula el desenfoque.
 */
object BackgroundPreferences {
    private const val KEY = "background_image"
    private const val DIR = "backgrounds"

    /** Lado mayor máximo con el que se guarda una imagen subida. */
    private const val MAX_SIDE_PX = 2160

    private lateinit var dir: java.io.File

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    /** Imágenes subidas, de la más antigua a la más reciente. */
    private val _uploads = MutableStateFlow<List<String>>(emptyList())
    val uploads: StateFlow<List<String>> = _uploads

    /**
     * Las imágenes de serie, en el orden del selector, valgan para el tema que valgan: `null` es la
     * predeterminada (`bg_1`) y el resto se guardan con nombres que empiezan por `@`, que nunca
     * chocan con los de las subidas (`bg_<fecha>.jpg`).
     */
    private val builtIns: Map<String?, Int> = linkedMapOf(
        null to com.untar.ultimusic.R.drawable.bg_1,
        "@bg_2" to com.untar.ultimusic.R.drawable.bg_2,
    )

    /** Los nombres de las imágenes de serie (ver [builtIns]), la predeterminada primero. */
    val builtInNames: List<String?> = builtIns.keys.toList()

    /**
     * El drawable de una imagen de serie, o la predeterminada si [name] es de una subida. Sirve
     * también de respaldo cuando una subida no se puede leer.
     */
    @androidx.annotation.DrawableRes
    fun builtInImage(name: String?): Int = builtIns[name] ?: builtIns.getValue(null)

    internal fun load(context: Context, p: SharedPreferences) {
        dir = java.io.File(context.filesDir, DIR)
        _uploads.value = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.map { it.name }.orEmpty()
        // Si el archivo elegido ya no existe, se vuelve al de serie.
        _selected.value = p.getString(KEY, null)?.takeIf { it in _uploads.value || it in builtIns }
    }

    /** El archivo de una imagen subida, o null para las de serie. */
    fun fileFor(name: String?): java.io.File? = name?.takeIf { it !in builtIns }?.let { java.io.File(dir, it) }

    fun select(name: String?) {
        if (_selected.value == name) return
        _selected.value = name
        SettingsPreferences.edit { putString(KEY, name) }
    }

    /**
     * Copia la imagen de [uri] (reducida y bien orientada) a `filesDir/backgrounds/`, la añade a
     * [uploads] y la deja elegida. Es pesada: llamarla fuera del hilo principal. Devuelve false si
     * no se pudo leer como imagen.
     */
    fun importImage(context: Context, uri: android.net.Uri): Boolean {
        val bitmap = decodeScaled(context, uri) ?: return false
        dir.mkdirs()
        val name = "bg_${System.currentTimeMillis()}.jpg"
        val file = java.io.File(dir, name)
        try {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (e: java.io.IOException) {
            file.delete()
            return false
        } finally {
            bitmap.recycle()
        }
        _uploads.value = _uploads.value + name
        select(name)
        return true
    }

    private fun decodeScaled(context: Context, uri: android.net.Uri): android.graphics.Bitmap? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // ImageDecoder ya aplica la rotación del EXIF.
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_SIDE_PX) {
                    val scale = MAX_SIDE_PX / side.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Separadores de los campos multivalor de los editores de metadatos (artistas, productores,
 * géneros). Por defecto solo la coma. Nunca vacía y nunca con espacios (ver [isValid]).
 */
object ArtistSeparatorPreferences {
    private const val KEY = "artist_separators"
    val DEFAULT = listOf(",")
    private val _separators = MutableStateFlow(DEFAULT)
    val separators: StateFlow<List<String>> = _separators

    internal fun load(p: SharedPreferences) {
        val stored = p.getString(KEY, null)
            ?.split('\n')
            ?.filter { isValid(it) }
            ?.distinct()
        _separators.value = if (stored.isNullOrEmpty()) DEFAULT else stored
    }

    /** Un separador vale si no está vacío y no contiene espacios en blanco. */
    fun isValid(separator: String): Boolean =
        separator.isNotEmpty() && separator.none { it.isWhitespace() }

    /** false si no vale o ya está. */
    fun add(separator: String): Boolean {
        if (!isValid(separator) || separator in _separators.value) return false
        save(_separators.value + separator)
        return true
    }

    /** false si es el último que queda (siempre tiene que haber al menos uno). */
    fun remove(separator: String): Boolean {
        val next = _separators.value - separator
        if (next.isEmpty()) return false
        save(next)
        return true
    }

    private fun save(list: List<String>) {
        _separators.value = list
        SettingsPreferences.edit { putString(KEY, list.joinToString("\n")) }
    }
}
