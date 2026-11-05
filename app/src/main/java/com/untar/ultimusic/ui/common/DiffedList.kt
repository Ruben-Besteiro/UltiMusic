package com.untar.ultimusic.ui.common

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de un adaptador que se actualiza con [DiffUtil] en vez de con `notifyDataSetChanged`.
 *
 * `notifyDataSetChanged` tira TODAS las filas a la papelera de reciclaje y las vuelve a crear y
 * enlazar enteras (carátula incluida), así que cualquier reemisión de la base de datos -aunque solo
 * haya cambiado una fila, o ninguna- hacía parpadear y tironear la lista entera. Con el diff solo
 * se tocan las filas que de verdad han cambiado.
 *
 * Caso especial, a propósito: si llega una lista IGUAL a la que ya había, se repintan las filas
 * visibles (con payload, sin fundido) en vez de no hacer nada. Es lo que necesitan los fragmentos que
 * combinan su lista con [com.untar.ultimusic.util.CoverArt.revision] para recoger una carátula que ha
 * cambiado de contenido sin cambiar de nombre: reenvían la misma lista, y antes eso bastaba porque
 * `notifyDataSetChanged` lo repintaba todo.
 *
 * El diff es síncrono y sin detección de movimientos: estas listas (álbumes, artistas, géneros...)
 * son de cientos de elementos como mucho, y así no hay ninguna carrera entre la lista que se está
 * pintando y la que acaba de llegar.
 *
 * Solo para adaptadores cuyas filas lean su elemento en el bind (no su POSICIÓN capturada en un
 * listener): una fila que el diff solo desplaza no se vuelve a enlazar.
 */
class DiffedList<T>(
    private val adapter: RecyclerView.Adapter<*>,
    private val idOf: (T) -> Any?
) {
    var items: List<T> = emptyList()
        private set

    val size: Int get() = items.size

    operator fun get(index: Int): T = items[index]

    fun submit(list: List<T>) {
        val old = items
        items = list
        if (old == list) {
            if (list.isNotEmpty()) adapter.notifyItemRangeChanged(0, list.size, PAYLOAD_REBIND)
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = idOf(old[oldPos]) == idOf(list[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == list[newPos]
        }, false).dispatchUpdatesTo(adapter)
    }

    companion object {
        /** Payload sin significado propio: solo sirve para que RecyclerView reutilice la misma fila
         *  (sin animación de fundido) al repintarla. El bind normal ignora los payloads. */
        val PAYLOAD_REBIND = Any()
    }
}
