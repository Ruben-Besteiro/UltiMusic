package com.untar.ultimusic.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Idioma de la aplicación: español o inglés, elegido a mano desde Ajustes (ver
 * [com.untar.ultimusic.ui.settings.SettingsDialogFragment]) con las dos banderas, o -la primera vez
 * que se abre la aplicación- deducido del idioma del sistema.
 *
 * No hay ningún `SharedPreferences` propio aquí: la elección la guarda `AppCompatDelegate` sola, en
 * cuanto el manifiesto declara `AppLocalesMetadataHolderService` con `autoStoreLocales="true"` (ver
 * `AndroidManifest.xml`). Aplicar un idioma con [select] recrea sola, al vuelo, cualquier
 * `AppCompatActivity` que esté viva en ese momento -así el cambio se ve al instante en toda la
 * aplicación, tal y como pide la regla de la app-, así que tampoco hace falta reaccionar a mano en
 * ningún ViewModel ni fragmento.
 *
 * Solo hay dos idiomas soportados, así que todo se reduce a "es" o cualquier otra cosa ("en" de
 * verdad, y cualquier idioma no soportado que el sistema pudiera traer, tratado también como "en" -
 * ver la cabecera de [applyDefaultIfUnset]).
 */
object AppLocale {

    private const val SPANISH = "es"
    private const val ENGLISH = "en"

    /**
     * Si el usuario todavía no ha elegido idioma a mano (instalación recién hecha, sin ningún
     * `AppCompatDelegate.setApplicationLocales` previo), fija uno según el idioma del sistema:
     * español si el sistema está en español, inglés en cualquier otro caso. Hay que llamarlo lo
     * antes posible -[com.untar.ultimusic.UltiMusicApp.onCreate], antes de que exista ninguna
     * pantalla-, porque en caso contrario el primer arranque podría llegar a pintarse con la
     * resolución de recursos normal de Android (que para un idioma sin carpeta `values-xx` propia
     * cae al `values/` por defecto, es decir español, no inglés).
     *
     * Si ya hay un idioma elegido -por el usuario o por una llamada anterior de este mismo método-
     * no hace nada: no hay que pisar la elección de vuelta cada vez que arranca la aplicación.
     */
    fun applyDefaultIfUnset() {
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        val systemLanguage = Locale.getDefault().language
        val target = if (systemLanguage == SPANISH) SPANISH else ENGLISH
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(target))
    }

    /** `true` si el idioma activo ahora mismo es el español (para marcar qué bandera va seleccionada). */
    fun isSpanishSelected(): Boolean {
        val locales = AppCompatDelegate.getApplicationLocales()
        val language = if (!locales.isEmpty) locales[0]?.language else Locale.getDefault().language
        return language == SPANISH
    }

    /** Cambia el idioma de toda la aplicación. Sin efecto si [spanish] ya es el idioma activo. */
    fun select(spanish: Boolean) {
        if (isSpanishSelected() == spanish) return
        val target = if (spanish) SPANISH else ENGLISH
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(target))
    }
}
