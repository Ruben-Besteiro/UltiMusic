package com.untarlamanteca.ultimusic.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.untarlamanteca.ultimusic.ui.common.PlaceholderFragment
import com.untarlamanteca.ultimusic.ui.library.AlbumsFragment
import com.untarlamanteca.ultimusic.ui.library.PeopleFragment
import com.untarlamanteca.ultimusic.ui.library.PersonKind
import com.untarlamanteca.ultimusic.ui.playlists.PlaylistsFragment
import com.untarlamanteca.ultimusic.ui.songs.SongsFragment

/**
 * Adaptador de las páginas del ViewPager2. Cinco de las seis son fragmentos reales (Canciones,
 * Álbumes, Artistas, Productores y Listas de reproducción); solo Géneros sigue siendo placeholder.
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
        5 -> PlaylistsFragment()
        else -> PlaceholderFragment.newInstance(titles[position])
    }
}
