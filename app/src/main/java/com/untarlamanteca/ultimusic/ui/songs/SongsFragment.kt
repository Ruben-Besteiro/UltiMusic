package com.untarlamanteca.ultimusic.ui.songs

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.untarlamanteca.ultimusic.R
import com.untarlamanteca.ultimusic.ui.PlayerViewModel
import com.untarlamanteca.ultimusic.ui.SongsViewModel
import kotlinx.coroutines.launch

/** Primer fragmento: lista de canciones con cabecera "Escoger al azar". */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    private val songsViewModel: SongsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSongs)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val spinner = view.findViewById<ProgressBar>(R.id.loadingSpinner)

        val adapter = SongsAdapter(
            onShuffle = { playerViewModel.playRandom(songsViewModel.songs.value) },
            onSongClick = { song -> playerViewModel.play(song) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    songsViewModel.songs.collect { list ->
                        adapter.submit(list)
                        emptyView.visibility =
                            if (list.isEmpty() && !songsViewModel.loading.value) View.VISIBLE
                            else View.GONE
                    }
                }
                launch {
                    songsViewModel.loading.collect { loading ->
                        spinner.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
}
