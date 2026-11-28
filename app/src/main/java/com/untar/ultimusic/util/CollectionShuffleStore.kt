package com.untar.ultimusic.util

import android.content.Context
import android.content.SharedPreferences
import com.untar.ultimusic.ui.CollectionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El aleatorio de cada TIPO de ficha (el botón de flechas cruzadas de arriba a la derecha de
 * `DetailDialogFragment` y `CollectionDetailDialogFragment`): uno para todos los álbumes, otro para
 * todos los artistas, otro para los géneros, otro para las etiquetas y otro para las listas.
 * Activar el de artistas no toca a los álbumes, y viceversa.
 *
 * El botón ya no reproduce nada al pulsarlo: solo cambia este interruptor, y lo que cambia es cómo
 * elige `PlaybackService` la siguiente canción cuando la cola es de ese tipo:
 * - Artista, género y etiqueta van alargando la cola de una en una (ver
 *   `PlaybackService.startGrowing`): siguiendo el orden del ojo de su ficha, o al azar con esto activado.
 * - Álbum y lista llenan la cola entera de golpe: en su orden, o barajada con esto activado.
 *
 * Si el tipo que se cambia es el que suena en ese momento, `PlaybackService` (que observa
 * [flow]) recoloca al instante lo que queda por sonar, sin cortar la canción actual. Si es otro
 * tipo, no pasa nada hasta la próxima vez que se reproduzca desde una ficha de ese tipo.
 *
 * Persistido en su propio `SharedPreferences`, como el resto de *Store.
 */
object CollectionShuffleStore {

    private const val PREFS_NAME = "collection_shuffle"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val flows = CollectionKind.entries.associateWith { MutableStateFlow(false) }

    /** La llama [com.untar.ultimusic.UltiMusicApp], igual que al resto de *Store. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            for ((kind, flow) in flows) flow.value = p.getBoolean(kind.name, false)
        }
    }

    /** Falso (orden de la ficha) si nunca se ha tocado el botón de ese tipo. */
    fun isOn(kind: CollectionKind): Boolean = flows.getValue(kind).value

    /** Para repintar el botón y para que `PlaybackService` reaccione al instante (ver la cabecera). */
    fun flow(kind: CollectionKind): StateFlow<Boolean> = flows.getValue(kind).asStateFlow()

    fun set(kind: CollectionKind, enabled: Boolean) {
        prefs?.edit()?.putBoolean(kind.name, enabled)?.apply()
        flows.getValue(kind).value = enabled
    }

    /** Cambia el interruptor de [kind] y avisa con un Toast de cómo ha quedado. Devuelve el estado nuevo. */
    fun toggle(context: Context, kind: CollectionKind): Boolean {
        val enabled = !isOn(kind)
        set(kind, enabled)
        android.widget.Toast.makeText(
            context,
            if (enabled) com.untar.ultimusic.R.string.shuffle_mode_on else com.untar.ultimusic.R.string.shuffle_mode_off,
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return enabled
    }
}
