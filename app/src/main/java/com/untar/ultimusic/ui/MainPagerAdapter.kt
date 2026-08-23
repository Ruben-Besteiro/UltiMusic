package com.untar.ultimusic.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.untar.ultimusic.ui.common.PlaceholderFragment
import com.untar.ultimusic.ui.library.AlbumsFragment
import com.untar.ultimusic.ui.library.GenresFragment
import com.untar.ultimusic.ui.library.PeopleFragment
import com.untar.ultimusic.ui.library.TagsFragment
import com.untar.ultimusic.ui.playlists.PlaylistsFragment
import com.untar.ultimusic.ui.songs.SongsFragment

/**
 * Adaptador de las páginas del ViewPager2. Las seis son fragmentos reales: Canciones, Álbumes,
 * Artistas, Géneros, Etiquetas y Listas de reproducción.
 */
class MainPagerAdapter(
    activity: FragmentActivity,
    private val titles: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SongsFragment()
        1 -> AlbumsFragment()
        2 -> PeopleFragment()
        3 -> GenresFragment()
        4 -> TagsFragment()
        5 -> PlaylistsFragment()
        else -> PlaceholderFragment.newInstance(titles[position])
    }
}
