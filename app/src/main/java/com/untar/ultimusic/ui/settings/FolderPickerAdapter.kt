package com.untar.ultimusic.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.untar.ultimusic.R
import java.io.File

/** Subcarpetas de la carpeta que se esté viendo en [FolderPickerDialogFragment]. Tocar una navega dentro. */
class FolderPickerAdapter(
    private val onFolderClick: (File) -> Unit
) : RecyclerView.Adapter<FolderPickerAdapter.FolderViewHolder>() {

    private var folders: List<File> = emptyList()

    fun submit(list: List<File>) {
        folders = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = folders.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return FolderViewHolder(inflater.inflate(R.layout.item_folder_picker_row, parent, false))
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(folders[position], onFolderClick)
    }

    class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.folderRowName)

        fun bind(folder: File, onFolderClick: (File) -> Unit) {
            name.text = folder.name
            itemView.setOnClickListener { onFolderClick(folder) }
        }
    }
}
