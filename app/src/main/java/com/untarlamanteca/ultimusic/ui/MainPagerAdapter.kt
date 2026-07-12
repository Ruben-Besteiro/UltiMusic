package com.untarlamanteca.ultimusic.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.untarlamanteca.ultimusic.ui.common.PlaceholderFragment
import com.untarlamanteca.ultimusic.ui.songs.SongsFragment

/**
 * Adaptador de las páginas del ViewPager2. La primera es el fragmento real de Canciones;
 * el resto son placeholders con el nombre de su pestaña.
 */
class MainPagerAdapter(
    activity: FragmentActivity,
    private val titles: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment =
        if (position == 0) SongsFragment()
        else PlaceholderFragment.newInstance(titles[position])
}
