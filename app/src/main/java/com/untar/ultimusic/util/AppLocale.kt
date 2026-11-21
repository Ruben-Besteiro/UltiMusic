package com.untar.ultimusic.util

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
     * En este orden se pintan en la cuadrícula de Ajustes (5 columnas x 5 filas). El serbocroata va
     * en alfabeto latino: su carpeta es `values-b+sr+Latn` y el idioma que devuelve Java es "sr". El
     * hebreo es "he" para BCP-47 pero Java/Android pueden devolver el antiguo "iw".
     */
    val LANGUAGES: List<Language> = listOf(
        Language("es", "Español", R.drawable.flag_spain),
        Language("pt", "Português", R.drawable.flag_portugal),
        Language("fr", "Français", R.drawable.flag_france),
        Language("it", "Italiano", R.drawable.flag_italy),
        Language("en", "English", R.drawable.flag_britain),
        Language("de", "Deutsch", R.drawable.flag_germany),
        Language("nl", "Nederlands", R.drawable.flag_netherlands),
        Language("pl", "Polski", R.drawable.flag_poland),
        Language("sr-Latn", "Srpskohrvatski", R.drawable.flag_yugoslavia, aliases = setOf("sr", "sh", "hr", "bs")),
        Language("ru", "Русский", R.drawable.flag_russia),
        Language("tr", "Türkçe", R.drawable.flag_turkey),
        Language("sw", "Kiswahili", R.drawable.flag_tanzania),
        Language("he", "עברית", R.drawable.flag_israel, aliases = setOf("iw")),
        Language("ar", "العربية", R.drawable.flag_arabia),
        Language("fa", "فارسی", R.drawable.flag_iran),
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

    private val ENGLISH = LANGUAGES.first { it.tag == "en" }

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
            val stored = locales[0]
            if (stored != null && stored.getUnicodeLocaleType("nu") != LATIN_DIGITS) {
                AppCompatDelegate.setApplicationLocales(localesFor(find(stored.language) ?: ENGLISH))
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
