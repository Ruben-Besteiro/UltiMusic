package com.untar.ultimusic.data

import android.content.Context
import android.content.SharedPreferences
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortDirection
import com.untar.ultimusic.util.SortField
import com.untar.ultimusic.util.SortOption

/**
 * Criterio de orden elegido por el usuario para cada una de las seis pestañas (ver
 * [com.untar.ultimusic.ui.sort.SortDialogFragment]), uno por [LibraryTab] y persistido para que
 * sobreviva a cerrar la aplicación — igual que [com.untar.ultimusic.data.remote.GeniusTokenStore],
 * pero sin nada que validar contra ninguna API: aquí basta con guardar dos enums.
 */
object SortPreferences {

    private const val PREFS_NAME = "library_sort"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de *Store. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** [SortOption.DEFAULT] si nunca se ha tocado esta pestaña, o si el campo guardado ya no es
     *  válido para ella (ver [LibraryTab.sanitize]: pasa si se cambian los criterios disponibles de
     *  una pestaña entre versiones). */
    fun get(tab: LibraryTab): SortOption {
        val p = prefs ?: return SortOption.DEFAULT
        val field = p.getString(fieldKey(tab), null)?.let { runCatching { SortField.valueOf(it) }.getOrNull() }
            ?: return SortOption.DEFAULT
        val direction = p.getString(directionKey(tab), null)
            ?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
            ?: SortDirection.ASCENDING
        return tab.sanitize(SortOption(field, direction))
    }

    fun save(tab: LibraryTab, option: SortOption) {
        prefs?.edit()
            ?.putString(fieldKey(tab), option.field.name)
            ?.putString(directionKey(tab), option.direction.name)
            ?.apply()
    }

    private fun fieldKey(tab: LibraryTab) = "${tab.name}_field"
    private fun directionKey(tab: LibraryTab) = "${tab.name}_direction"
}
