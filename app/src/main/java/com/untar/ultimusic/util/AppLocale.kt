package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.telephony.TelephonyManager
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.untar.ultimusic.R
import java.util.Locale

/**
 * Idioma de la aplicación: uno de los 25 de [LANGUAGES], elegido a mano desde Ajustes (ver
 * [com.untar.ultimusic.ui.settings.SettingsDialogFragment]) con la cuadrícula de banderas, o -la
 * primera vez que se abre la aplicación- deducido del idioma del sistema.
 *
 * No hay ningún `SharedPreferences` propio aquí: la elección la guarda `AppCompatDelegate` sola, en
 * cuanto el manifiesto declara `AppLocalesMetadataHolderService` con `autoStoreLocales="true"` (ver
 * `AndroidManifest.xml`). Aplicar un idioma con [select] recrea sola, al vuelo, cualquier
 * `AppCompatActivity` que esté viva en ese momento -así el cambio se ve al instante en toda la
 * aplicación, tal y como pide la regla de la app-, así que tampoco hace falta reaccionar a mano en
 * ningún ViewModel ni fragmento.
 */
object AppLocale {

    /**
     * Un idioma soportado. [tag] es la etiqueta BCP-47 que se le pasa a `AppCompatDelegate` y casa
     * con la carpeta `values-xx` de sus strings; [aliases] son otros códigos ISO que Java/Android
     * pueden devolver para ese mismo idioma (el indonesio sale como "in" en muchas versiones de
     * Android, y el filipino a veces como "tl", tagalo). [nativeName] va como contentDescription de
     * la bandera, en el propio idioma, para que se reconozca aunque la app esté en otro.
     */
    data class Language(
        val tag: String,
        val nativeName: String,
        @DrawableRes val flag: Int,
        val aliases: Set<String> = emptySet(),
    ) {
        fun matches(language: String?): Boolean = language == tag || language in aliases
    }

    /**
     * País del usuario (ISO 3166, mayúsculas; vacío si no se sabe), fijado por [init] al arrancar.
     * Decide el alfabeto por defecto del serbocroata (ver [serbocroatianScript]).
     */
    private var region: String = ""

    private lateinit var prefs: SharedPreferences

    /**
     * Hay que llamarlo lo primero de todo en `Application.onCreate`, antes de tocar [LANGUAGES]: sin
     * el país, el serbocroata saldría siempre con el alfabeto por defecto. El país se saca, por este
     * orden, de la SIM, de la red móvil y de la región del sistema. NO se usa `Locale.getDefault()`:
     * tras un `setApplicationLocales` previo devuelve el locale de la propia app (sin país) y la
     * detección fallaría a partir del segundo arranque. No necesita ningún permiso.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        region = listOf(
            telephony?.simCountryIso,
            telephony?.networkCountryIso,
            Resources.getSystem().configuration.locales.takeUnless { it.isEmpty }?.get(0)?.country,
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)
    }

    private const val PREFS_NAME = "app_locale"
    private const val KEY_SERBOCROATIAN_SCRIPT = "serbocroatian_script"

    const val SCRIPT_LATIN = "Latn"
    const val SCRIPT_CYRILLIC = "Cyrl"

    /**
     * El alfabeto del serbocroata: el que el usuario haya elegido en Ajustes y, si todavía no ha
     * elegido, el que le toca por su país (cirílico en Serbia, latín en cualquier otro sitio).
     */
    fun serbocroatianScript(): String =
        prefs.getString(KEY_SERBOCROATIAN_SCRIPT, null)
            ?: if (region == "RS") SCRIPT_CYRILLIC else SCRIPT_LATIN

    /**
     * Elige el alfabeto del serbocroata ([SCRIPT_LATIN] o [SCRIPT_CYRILLIC]) y lo recuerda. Si el
     * serbocroata es el idioma activo, lo vuelve a aplicar con el alfabeto nuevo (eso recrea sola
     * cualquier Activity viva).
     */
    fun setSerbocroatianScript(script: String) {
        if (script == serbocroatianScript()) return
        prefs.edit().putString(KEY_SERBOCROATIAN_SCRIPT, script).apply()
        languages = null
        if (current().matches("sr")) AppCompatDelegate.setApplicationLocales(localesFor(current()))
    }

    /**
     * El serbocroata. Lo único que varía es el alfabeto ([serbocroatianScript]: el elegido en
     * Ajustes o, por defecto, el del país del usuario). El cirílico es `values-b+sr+Cyrl`, que genera
     * `tools/translate_strings.py` por transliteración del latino. La bandera es siempre la de
     * Yugoslavia y el nombre el neutro, a propósito: el idioma de la app es una variedad común, no la
     * de un país concreto, y una bandera nacional haría creer al usuario que lo es. En todos los
     * casos el idioma que devuelve Java es "sr".
     */
    private fun serbocroatian(): Language {
        val aliases = setOf("sr", "sh", "hr", "bs", "cnr")
        return if (serbocroatianScript() == SCRIPT_CYRILLIC) {
            Language("sr-Cyrl", "Српскохрватски", R.drawable.flag_yugoslavia, aliases)
        } else {
            Language("sr-Latn", "Srpskohrvatski", R.drawable.flag_yugoslavia, aliases)
        }
    }

    /**
     * En este orden se pintan en la cuadrícula de Ajustes (5 columnas x 5 filas). El serbocroata es
     * una sola celda cuya variante depende del país (ver [serbocroatian]). El hebreo es "he" para
     * BCP-47 pero Java/Android pueden devolver el antiguo "iw".
     */
    val LANGUAGES: List<Language>
        get() = languages ?: buildLanguages().also { languages = it }

    private var languages: List<Language>? = null

    private fun buildLanguages(): List<Language> = listOf(
        Language("es", "Español", R.drawable.flag_spain),
        Language("pt", "Português", R.drawable.flag_portugal),
        Language("fr", "Français", R.drawable.flag_france),
        Language("it", "Italiano", R.drawable.flag_italy),
        Language("en", "English", R.drawable.flag_britain),
        Language("de", "Deutsch", R.drawable.flag_germany),
        Language("nl", "Nederlands", R.drawable.flag_netherlands),
        Language("pl", "Polski", R.drawable.flag_poland),
        serbocroatian(),
        Language("ru", "Русский", R.drawable.flag_russia),
        Language("tr", "Türkçe", R.drawable.flag_turkey),
        Language("sw", "Kiswahili", R.drawable.flag_tanzania),
        Language("he", "עברית", R.drawable.flag_israel, aliases = setOf("iw")),
        Language("ar", "العربية", R.drawable.flag_arabia),
        Language("fa", "فارسی", R.drawable.flag_iran, aliases = setOf("prs")),
        Language("ur", "اردو", R.drawable.flag_pakistan),
        Language("hi", "हिन्दी", R.drawable.flag_india),
        Language("bn", "বাংলা", R.drawable.flag_bangladesh),
        Language("zh", "中文", R.drawable.flag_china),
        Language("ja", "日本語", R.drawable.flag_japan),
        Language("ko", "한국어", R.drawable.flag_korea),
        Language("vi", "Tiếng Việt", R.drawable.flag_vietnam),
        Language("th", "ไทย", R.drawable.flag_thailand),
        Language("id", "Bahasa Indonesia", R.drawable.flag_indonesia, aliases = setOf("in")),
        Language("fil", "Filipino", R.drawable.flag_philippines, aliases = setOf("tl")),
    )

    // Perezoso a propósito: [LANGUAGES] no se puede construir hasta que [init] haya fijado el país.
    private val ENGLISH by lazy { LANGUAGES.first { it.tag == "en" } }

    private fun find(language: String?): Language? = LANGUAGES.firstOrNull { it.matches(language) }

    /**
     * Si el usuario todavía no ha elegido idioma a mano (instalación recién hecha, sin ningún
     * `AppCompatDelegate.setApplicationLocales` previo), fija el del sistema si está soportado, e
     * inglés en cualquier otro caso. Hay que llamarlo lo antes posible
     * -[com.untar.ultimusic.UltiMusicApp.onCreate], antes de que exista ninguna pantalla-, porque en
     * caso contrario el primer arranque con un idioma no soportado se pintaría con el `values/` por
     * defecto, es decir español, no inglés.
     *
     * Si ya hay un idioma elegido -por el usuario o por una llamada anterior de este mismo método-
     * no hace nada: no hay que pisar la elección de vuelta cada vez que arranca la aplicación.
     */
    fun applyDefaultIfUnset() {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) {
            // Elecciones guardadas antes de forzar las cifras occidentales (ver [localesFor]):
            // se vuelven a aplicar con la extensión para que no sigan mezclando sistemas numéricos.
            // Lo mismo si lo guardado es el serbocroata en otro alfabeto que el de hoy (elegido antes
            // de que existiera el cirílico, o con el país cambiado): se vuelve a aplicar su variante.
            val stored = locales[0]
            if (stored != null) {
                val language = find(stored.language) ?: ENGLISH
                val staleDigits = stored.getUnicodeLocaleType("nu") != LATIN_DIGITS
                val staleScript = language.matches("sr") && stored.script != Locale.forLanguageTag(language.tag).script
                if (staleDigits || staleScript) AppCompatDelegate.setApplicationLocales(localesFor(language))
            }
            return
        }
        val target = find(Locale.getDefault().language) ?: ENGLISH
        AppCompatDelegate.setApplicationLocales(localesFor(target))
    }

    private const val LATIN_DIGITS = "latn"

    /**
     * El locale de [language] con las cifras forzadas a las occidentales (0-9) mediante la
     * extensión Unicode `-u-nu-latn`. Sin ella, en árabe, persa, urdu, bengalí... lo que se
     * formatea con el locale (`getString(id, n)`, `"%d".format(n)`) sale con las cifras propias
     * del idioma, mientras que lo que se concatena a pelo (`"$n"`) sale en 0-9, y ambos sistemas
     * acaban entremezclados en la misma pantalla. La extensión no afecta a la elección de la
     * carpeta `values-xx`, que solo mira idioma y región.
     */
    private fun localesFor(language: Language): LocaleListCompat =
        LocaleListCompat.create(
            Locale.Builder()
                .setLanguageTag(language.tag)
                .setUnicodeLocaleKeyword("nu", LATIN_DIGITS)
                .build()
        )

    /** El idioma activo ahora mismo (para marcar qué bandera va seleccionada). */
    fun current(): Language {
        val locales = AppCompatDelegate.getApplicationLocales()
        val language = if (!locales.isEmpty) locales[0]?.language else Locale.getDefault().language
        return find(language) ?: ENGLISH
    }

    /** Cambia el idioma de toda la aplicación. Sin efecto si [language] ya es el idioma activo. */
    fun select(language: Language) {
        if (current() == language) return
        AppCompatDelegate.setApplicationLocales(localesFor(language))
    }
}
