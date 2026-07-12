package com.untarlamanteca.ultimusic.ui.common

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.untarlamanteca.ultimusic.R

/** Fragmento temporal para las pestañas aún sin implementar. */
class PlaceholderFragment : Fragment(R.layout.fragment_placeholder) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.placeholderText).text =
            arguments?.getString(ARG_TITLE).orEmpty()
    }

    companion object {
        private const val ARG_TITLE = "title"

        fun newInstance(title: String) = PlaceholderFragment().apply {
            arguments = bundleOf(ARG_TITLE to title)
        }
    }
}
