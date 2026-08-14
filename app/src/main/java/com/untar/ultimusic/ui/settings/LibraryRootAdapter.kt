package com.untar.ultimusic.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import com.untar.ultimusic.model.LibraryRoot

/**
 * Lista de carpetas raíz adicionales de la fonoteca (ajustes > Carpetas de la fonoteca). Cada fila
 * muestra la ruta absoluta completa (a diferencia de [GreylistAdapter], aquí no hay un prefijo común
 * como `UltiMusic/` que recortar: cada carpeta puede vivir en cualquier parte del dispositivo) y una
 * papelera que la quita. Sin switch: una carpeta raíz está dentro de la biblioteca o no lo está, no
 * tiene el estado intermedio de "excluida pero sin quitar" que sí tiene la lista gris. Al no llevar
 * switch tampoco lleva acento propio (la papelera no se tiñe en [GreylistAdapter] tampoco). Mismo
 * patrón plano que [GreylistAdapter]: sin DiffUtil, `submit()` repinta entera.
 */
class LibraryRootAdapter(
    private val onDelete: (LibraryRoot) -> Unit
) : RecyclerView.Adapter<LibraryRootAdapter.LibraryRootViewHolder>() {

    private var roots: List<LibraryRoot> = emptyList()

    fun submit(list: List<LibraryRoot>) {
        roots = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = roots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryRootViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return LibraryRootViewHolder(inflater.inflate(R.layout.item_library_root, parent, false))
    }

    override fun onBindViewHolder(holder: LibraryRootViewHolder, position: Int) {
        holder.bind(roots[position], onDelete)
    }

    class LibraryRootViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val path: TextView = itemView.findViewById(R.id.libraryRootPath)
        private val delete: ImageButton = itemView.findViewById(R.id.btnLibraryRootDelete)

        fun bind(root: LibraryRoot, onDelete: (LibraryRoot) -> Unit) {
            path.text = root.path
            delete.setOnClickListener { onDelete(root) }
        }
    }
}
