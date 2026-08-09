package com.untar.ultimusic.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.untar.ultimusic.ui.common.PlaceholderFragment
import com.untar.ultimusic.ui.library.AlbumsFragment
import com.untar.ultimusic.ui.library.GenresFragment
import com.untar.ultimusic.ui.library.PeopleFragment
import com.untar.ultimusic.ui.library.PersonKind
import com.untar.ultimusic.ui.playlists.PlaylistsFragment
import com.untar.ultimusic.ui.songs.SongsFragment

/**
 * Adaptador de las páginas del ViewPager2. Las seis son fragmentos reales: Canciones, Álbumes,
 * Artistas, Productores, Géneros y Listas de reproducción.
 *
 * Artistas y Productores son el MISMO fragmento con un argumento distinto: se tratan igual, así
 * que comparten código y solo cambia de qué flujo leen.
 */
class MainPagerAdapter(
    activity: FragmentActivity,
    private val titles: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SongsFragment()
        1 -> AlbumsFragment()
        2 -> PeopleFragment.newInstance(PersonKind.ARTIST)
        3 -> PeopleFragment.newInstance(PersonKind.PRODUCER)
        4 -> GenresFragment()
        5 -> PlaylistsFragment()
        else -> PlaceholderFragment.newInstance(titles[position])
    }
}
