package com.untar.ultimusic.ui.sort

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.untar.ultimusic.R
import com.untar.ultimusic.data.remote.YouTubeApiKeyStore
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.SongsViewModel
import com.untar.ultimusic.ui.library.LibraryViewModel
import com.untar.ultimusic.ui.playlists.PlaylistsViewModel
import com.untar.ultimusic.util.AccentTint
import com.untar.ultimusic.util.LibraryTab
import com.untar.ultimusic.util.SortField
import com.untar.ultimusic.util.SortOption

/**
 * El diálogo de ordenar de la pestaña actual (ver [com.untar.ultimusic.ui.MainActivity.showSort],
 * abierto desde el ojo de la barra superior): un grupo de radio buttons con el criterio
 * (Nombre/Número de canciones/Duración/Año/Popularidad, solo los que valgan para esta pestaña, ver
 * [LibraryTab.availableFields]) y debajo la casilla "Invertir orden", que invierte la dirección
 * natural de ese criterio (ver [SortField.naturalDirection]) en vez de elegir Ascendente/Descendente
 * a secas: así la casilla no cambia de sentido al cambiar de criterio (marcarla siempre significa
 * "al revés de lo normal"), y con ella desmarcada cada criterio ya ordena como se espera sin tocar
 * nada más.
 *
 * Se muestra vía `supportFragmentManager` (no `childFragmentManager`), así que los `activityViewModels`
 * de aquí son la MISMA instancia que ya observan las seis pestañas: al pulsar "Aceptar" no hace falta
 * ningún `setFragmentResult` para avisar a nadie, basta con escribir directamente en el ViewModel que
 * corresponda (igual de directo que [com.untar.ultimusic.ui.playlists.PlaylistsFragment.showNameDialog]
 * tocando su propio ViewModel).
 */
class SortDialogFragment : DialogFragment() {

    private val songsViewModel: SongsViewModel by activityViewModels()
    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val playlistsViewModel: PlaylistsViewModel by activityViewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private val tab: LibraryTab
        get() = LibraryTab.valueOf(requireArguments().getString(ARG_TAB)!!)

    private lateinit var fieldsGroup: RadioGroup
    private lateinit var invertCheckbox: CheckBox

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val tab = this.tab
        val content = layoutInflater.inflate(R.layout.dialog_sort, null)
        fieldsGroup = content.findViewById(R.id.sortFieldGroup)
        invertCheckbox = content.findViewById(R.id.sortInvertOrder)

        val fieldButtons = mapOf(
            SortField.NAME to R.id.sortByName,
            SortField.SONG_COUNT to R.id.sortBySongCount,
            SortField.DURATION to R.id.sortByDuration,
            SortField.YEAR to R.id.sortByYear,
            SortField.POPULARITY to R.id.sortByPopularity
        )
        // Solo se enseñan los campos que valen para esta pestaña; el resto se queda oculto en vez de
        // no inflarse, para no duplicar el layout por pestaña (ver el comentario de dialog_sort.xml).
        for ((field, id) in fieldButtons) {
            content.findViewById<RadioButton>(id).isVisible = field in tab.availableFields
        }

        // Tinte de una sola vez con el acento dinámico (regla del proyecto para todo lo amarillo, ver
        // AccentTint): un DialogFragment que monta su vista en onCreateDialog no pasa por
        // onViewCreated, así que no hay collector de accentColor vivo aquí (mismo motivo que el
        // enlace de GeniusTokenDialogFragment).
        val accent = playerViewModel.accentColor.value
        for (id in fieldButtons.values) {
            content.findViewById<RadioButton>(id).buttonTintList = ColorStateList.valueOf(accent)
        }
        invertCheckbox.buttonTintList = ColorStateList.valueOf(accent)

        val current = tab.sanitize(currentOption())
        fieldsGroup.check(fieldButtons.getValue(current.field))
        // La casilla arranca marcada si el orden guardado va al revés de la dirección natural de su
        // campo (ver SortField.naturalDirection): es la única forma de que recuerde su valor entre
        // aperturas, ya que [applySelection] siempre calcula direction a partir de la propia casilla,
        // así que la comparación es exacta para cualquier preferencia guardada por este diálogo.
        invertCheckbox.isChecked = current.direction != current.field.naturalDirection

        // "Popularidad" exige clave de la YouTube Data API (ver YouTubeApiKeyStore). Si se marca ese
        // radio y no hay clave utilizable, se revierte la marca al criterio anterior; si además el
        // usuario no ha rechazado ya la configuración, se le pide (si la pone o la rechaza, el
        // resultado llega por el childFragmentManager de abajo). lastFieldId se actualiza en
        // cualquier otro cambio válido.
        //
        // Se mira `key.isEmpty()` y no `shouldOfferSetup` a propósito: `shouldOfferSetup` se vuelve
        // false en cuanto se rechaza una vez, y usarlo aquí dejaría marcar "Popularidad" sin clave
        // ninguna, con un orden que no hace nada (siempre el mismo, sin ningún dato de popularidad
        // que consultar). El radio tiene que seguir bloqueado sin clave pase lo que pase; lo único
        // que cambia con el rechazo es si se vuelve a ofrecer el diálogo o solo se avisa por Toast.
        var lastFieldId = fieldsGroup.checkedRadioButtonId
        fieldsGroup.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId == R.id.sortByPopularity && YouTubeApiKeyStore.key.isEmpty()) {
                group.check(lastFieldId)
                if (YouTubeApiKeyStore.shouldOfferSetup) {
                    if (childFragmentManager.findFragmentByTag(YouTubeApiKeyDialogFragment.TAG) == null) {
                        YouTubeApiKeyDialogFragment.newInstance()
                            .show(childFragmentManager, YouTubeApiKeyDialogFragment.TAG)
                    }
                } else {
                    // Ya dijo que no antes: no se le vuelve a insistir con el diálogo, pero sí hay
                    // que decir por qué el radio no se queda marcado y dónde retomarlo si cambia de
                    // idea (ver SettingsDialogFragment.setupServices).
                    Toast.makeText(
                        requireContext(), R.string.youtube_setup_declined_hint, Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                lastFieldId = checkedId
            }
        }
        childFragmentManager.setFragmentResultListener(
            YouTubeApiKeyDialogFragment.RESULT_KEY, this
        ) { _, bundle ->
            // Solo si se configuró: si se rechazó, la selección se queda donde estaba (revertida más
            // arriba), sin volver a insistir en esta misma apertura del diálogo.
            if (bundle.getBoolean(YouTubeApiKeyDialogFragment.RESULT_CONFIGURED)) {
                fieldsGroup.check(R.id.sortByPopularity)
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sort_title, getString(tab.titleRes)))
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> applySelection(tab, fieldButtons) }
            .create()
        dialog.setOnShowListener { AccentTint.buttons(dialog, accent) }
        return dialog
    }

    private fun currentOption(): SortOption = when (tab) {
        LibraryTab.SONGS -> songsViewModel.sort.value
        LibraryTab.ALBUMS -> libraryViewModel.albumsSort.value
        LibraryTab.ARTISTS -> libraryViewModel.artistsSort.value
        LibraryTab.PRODUCERS -> libraryViewModel.producersSort.value
        LibraryTab.GENRES -> libraryViewModel.genresSort.value
        LibraryTab.PLAYLISTS -> playlistsViewModel.sort.value
    }

    private fun applySelection(tab: LibraryTab, fieldButtons: Map<SortField, Int>) {
        val field = fieldButtons.entries.first { it.value == fieldsGroup.checkedRadioButtonId }.key
        val direction = if (invertCheckbox.isChecked) {
            field.naturalDirection.opposite()
        } else {
            field.naturalDirection
        }
        val option = SortOption(field, direction)
        when (tab) {
            LibraryTab.SONGS -> songsViewModel.setSort(option)
            LibraryTab.PLAYLISTS -> playlistsViewModel.setSort(option)
            LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.PRODUCERS, LibraryTab.GENRES ->
                libraryViewModel.setSort(tab, option)
        }
    }

    companion object {
        const val TAG = "sort_dialog"
        private const val ARG_TAB = "tab"

        fun newInstance(tab: LibraryTab): SortDialogFragment = SortDialogFragment().apply {
            arguments = bundleOf(ARG_TAB to tab.name)
        }
    }
}
