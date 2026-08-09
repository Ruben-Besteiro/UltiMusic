package com.untar.ultimusic.ui.settings

import android.content.res.ColorStateList
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.untar.ultimusic.R
import com.untar.ultimusic.model.GreylistFolder
import com.untar.ultimusic.util.DynamicColor
import java.io.File

/**
 * Lista de subcarpetas de la lista gris (ajustes > Lista gris). Cada fila muestra la ruta relativa a
 * `UltiMusic` (la absoluta sería kilométrica), un switch que excluye/incluye la carpeta (encendido =
 * excluida, oculta de la biblioteca) y una papelera que la quita de la lista. Mismo patrón plano que
 * [com.untar.ultimusic.ui.playlists.PlaylistsAdapter]: sin DiffUtil, `submit()` repinta entera.
 */
class GreylistAdapter(
    private val onToggle: (GreylistFolder, Boolean) -> Unit,
    private val onDelete: (GreylistFolder) -> Unit
) : RecyclerView.Adapter<GreylistAdapter.GreylistViewHolder>() {

    private var folders: List<GreylistFolder> = emptyList()

    // Sin tinte propio, el switch cae en el colorSecondary del tema (el amarillo fijo de siempre);
    // ver [setAccent].
    private var accent: Int = DynamicColor.DEFAULT

    fun submit(list: List<GreylistFolder>) {
        folders = list
        notifyDataSetChanged()
    }

    /** Tiñe todos los switches con el color de la canción que suena, igual que el resto de la pantalla. */
    fun setAccent(color: Int) {
        accent = color
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = folders.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GreylistViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return GreylistViewHolder(inflater.inflate(R.layout.item_greylist_folder, parent, false))
    }

    override fun onBindViewHolder(holder: GreylistViewHolder, position: Int) {
        holder.bind(folders[position], onToggle, onDelete, accent)
    }

    class GreylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val path: TextView = itemView.findViewById(R.id.greylistFolderPath)
        private val switch: MaterialSwitch = itemView.findViewById(R.id.greylistFolderSwitch)
        private val delete: ImageButton = itemView.findViewById(R.id.btnGreylistFolderDelete)

        // Gris de fábrica del switch, capturado la primera vez que se llama a [bind] y ANTES de
        // pisar trackTintList: hace falta para que el interruptor apagado siga viéndose gris (ver
        // [DynamicColor.switchTrackTint]). El ViewHolder se recicla, así que solo se captura una vez.
        private var defaultTrackTint: ColorStateList? = null

        // Último acento ya aplicado a este switch, o null si todavía no se ha aplicado ninguno.
        // Sirve para no retocar trackTintList en cada [bind] (ver más abajo, es la única razón de
        // que exista este campo).
        private var lastAccent: Int? = null

        fun bind(
            folder: GreylistFolder,
            onToggle: (GreylistFolder, Boolean) -> Unit,
            onDelete: (GreylistFolder) -> Unit,
            accent: Int
        ) {
            path.text = relativeToUltiMusic(folder.path)

            // El listener se quita y se vuelve a poner SIEMPRE (barato, y tiene que capturar el
            // `folder` de esta llamada: el ViewHolder se recicla entre carpetas, así que uno viejo
            // podría avisar de la carpeta equivocada). `isChecked`, en cambio, solo se reasigna si
            // de verdad cambia de lo que el switch ya está mostrando.
            //
            // SwitchCompat.setChecked() no comprueba si el valor es el mismo que ya tenía: siempre
            // dispara su animación de mover el pomo, y si la vista está a mitad de un `layout` (que
            // es justo cuando corre `bind`) la deja sin animar, saltando directa a la posición
            // final. `submit()` no usa DiffUtil (ver la clase), así que tocar el switch repinta TODA
            // la lista con notifyDataSetChanged() — y como [greylistFolders] es un Flow de Room, la
            // propia escritura en la base de datos que dispara el toggle hace que esa lista se
            // vuelva a emitir y `bind` se repita en la misma fila con el mismo valor que el usuario
            // ya le había puesto a mano. Sin este guard, ese reasignado redundante volvía a llamar a
            // `setChecked()` en mitad de otro `layout`, y el pomo saltaba en seco en vez de animarse
            // solo. `eqSwitch` no tiene este problema porque su `isChecked` lo fija un `collect` de
            // `StateFlow` normal, nunca desde dentro de un paso de `layout` de un RecyclerView.
            switch.setOnCheckedChangeListener(null)
            if (switch.isChecked != folder.excluded) switch.isChecked = folder.excluded
            switch.setOnCheckedChangeListener { _, checked -> onToggle(folder, checked) }

            // Solo se retoca trackTintList cuando el acento ha cambiado de verdad, no en cada
            // `bind`. `submit()` no usa DiffUtil (ver la clase), así que tocar el switch repinta
            // TODA la lista de golpe con notifyDataSetChanged() — y como [greylistFolders] es un
            // Flow de Room, la propia escritura en la base de datos que dispara el toggle hace que
            // esa lista se vuelva a emitir y `bind` se repita en la misma fila justo mientras el
            // switch está a mitad de su animación nativa de pulsar/soltar. Reasignar el tinte ahí
            // interrumpía esa animación (y su ripple), dejándola en un salto seco sin transición ni
            // pulso visible; eqSwitch no tenía este problema porque su tinte lo aplica un flujo
            // aparte que solo se dispara cuando cambia la canción, no al tocarlo.
            if (accent != lastAccent) {
                lastAccent = accent

                // Solo se tiñe la pista (parte exterior); el pomo se deja con el tinte por defecto
                // del tema para que no todo el interruptor quede pintado del mismo color plano.
                if (defaultTrackTint == null) defaultTrackTint = switch.trackTintList
                switch.trackTintList = DynamicColor.switchTrackTint(accent, defaultTrackTint)
            }

            delete.setOnClickListener { onDelete(folder) }
        }

        private fun relativeToUltiMusic(path: String): String {
            val root = File(Environment.getExternalStorageDirectory(), "UltiMusic").path + "/"
            return path.removePrefix(root)
        }
    }
}
