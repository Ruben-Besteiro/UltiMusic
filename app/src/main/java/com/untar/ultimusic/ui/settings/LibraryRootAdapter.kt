package com.untar.ultimusic.ui.settings

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.untar.ultimusic.R
import com.untar.ultimusic.model.LibraryRoot
import com.untar.ultimusic.util.SafStorage

/**
 * Lista de carpetas raíz de la fonoteca (ajustes > Carpetas de la fonoteca). Cada fila muestra el
 * docPath de la carpeta concedida (ver [SafStorage.docPathOfTree]) y una papelera que la quita (la
 * carpeta principal UltiMusic no la lleva).
 *
 * Pinta las filas directamente en un [LinearLayout] en vez de usar un RecyclerView: dentro del
 * ScrollView de ajustes, un RecyclerView `wrap_content` se quedaba midiendo una sola fila aunque el
 * adaptador tuviera varias, y las carpetas añadidas nunca aparecían. Son muy pocas filas, así que
 * no hace falta reciclar nada.
 */
class LibraryRootAdapter(
    private val container: LinearLayout,
    private val onDelete: (LibraryRoot) -> Unit
) {

    fun submit(list: List<LibraryRoot>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        for (root in list) {
            val row = inflater.inflate(R.layout.item_library_root, container, false)
            bind(row, root)
            container.addView(row)
        }
    }

    private fun bind(row: View, root: LibraryRoot) {
        val path: TextView = row.findViewById(R.id.libraryRootPath)
        val delete: ImageButton = row.findViewById(R.id.btnLibraryRootDelete)
        path.text = runCatching { Uri.parse(root.path) }.getOrNull()
            ?.let { SafStorage.docPathOfTree(it) } ?: root.path
        delete.setOnClickListener { onDelete(root) }
    }
}
