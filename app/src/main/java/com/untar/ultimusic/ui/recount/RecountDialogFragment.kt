package com.untar.ultimusic.ui.recount

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.untar.ultimusic.R
import com.untar.ultimusic.model.HistogramData
import com.untar.ultimusic.model.RecountData
import com.untar.ultimusic.ui.PlayerViewModel
import com.untar.ultimusic.ui.common.FlowLayout
import com.untar.ultimusic.util.AccentTint
import kotlinx.coroutines.launch

/**
 * UltiMusic Recount: el resumen anual de lo que se ha escuchado.
 *
 * Solo existe la última semana de diciembre: se abre desde el aviso de arranque, su notificación o el
 * último ajuste de Ajustes (que solo aparece esa semana; ver
 * [com.untar.ultimusic.recount.RecountReminder]). Solo muestra el año actual. Es un diálogo a
 * pantalla completa, como el resto de pantallas de la app.
 *
 * Aquí no se calcula nada: todo llega hecho de [RecountViewModel], que a su vez cuelga de flujos de
 * Room. Eso es lo que hace que una edición de metadatos se vea al instante, incluso con la pantalla
 * abierta detrás del editor.
 *
 * Nada de esta pantalla se puede pinchar: es una foto de lo que sonó, no un atajo para volver a
 * reproducirlo. Por eso los tops no llevan `selectableItemBackground` ni listener de clic.
 */
class RecountDialogFragment : DialogFragment() {

    private val viewModel: RecountViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by activityViewModels()

    private lateinit var histogramTitle: TextView
    private lateinit var topSongsTitle: TextView
    private lateinit var topArtistsTitle: TextView
    private lateinit var topGenresTitle: TextView
    private lateinit var histogram: HistogramView
    private lateinit var histogramUnknown: TextView
    private lateinit var histogramEmpty: TextView
    private lateinit var pie: PieChartView
    private lateinit var legend: FlowLayout
    private lateinit var pieEmpty: TextView
    private lateinit var emptyView: TextView
    private lateinit var content: View

    private val songsAdapter = RecountTopAdapter()
    private val artistsAdapter = RecountTopAdapter()
    private val genresAdapter = RecountTopAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_UltiMusic_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_recount, container, false)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.recountRoot)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.recountToolbar)
        histogramTitle = view.findViewById(R.id.histogramTitle)
        topSongsTitle = view.findViewById(R.id.topSongsTitle)
        topArtistsTitle = view.findViewById(R.id.topArtistsTitle)
        topGenresTitle = view.findViewById(R.id.topGenresTitle)
        histogram = view.findViewById(R.id.recountHistogram)
        histogramUnknown = view.findViewById(R.id.histogramUnknown)
        histogramEmpty = view.findViewById(R.id.histogramEmpty)
        pie = view.findViewById(R.id.recountPie)
        legend = view.findViewById(R.id.recountLegend)
        pieEmpty = view.findViewById(R.id.pieEmpty)
        emptyView = view.findViewById(R.id.recountEmpty)
        content = view.findViewById(R.id.recountContent)

        // La barra de estado es transparente en este tema, así que la toolbar se aparta ella sola de
        // la hora y la batería con un padding del tamaño de ese hueco (igual que en Ajustes).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
        toolbar.setNavigationOnClickListener { dismiss() }

        emptyView.text = getString(R.string.recount_year_empty, viewModel.year)
        setupTop(view.findViewById(R.id.topSongsRecycler), songsAdapter)
        setupTop(view.findViewById(R.id.topArtistsRecycler), artistsAdapter)
        setupTop(view.findViewById(R.id.topGenresRecycler), genresAdapter)
        observeState()
    }

    private fun setupTop(recycler: RecyclerView, adapter: RecountTopAdapter) {
        // isNestedScrollingEnabled a false ya viene del XML: estas listas no se desplazan solas, se
        // estiran enteras dentro del ScrollView de la pantalla.
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.histogram.collect { data -> renderHistogram(data) } }
                launch { viewModel.recount.collect { data -> renderRecount(data) } }
                // Regla de color dinámico del proyecto: todo lo que en el XML es @color/um_yellow
                // (los cuatro títulos) más los dos gráficos. Ver la cabecera de AccentTint: nadie
                // adivina qué vistas son amarillas, cada pantalla las da de alta.
                launch {
                    playerViewModel.accentColor.collect { accent ->
                        for (title in titles()) title.setTextColor(accent)
                        histogram.accentColor = accent
                        pie.accentColor = accent
                        renderLegend(viewModel.recount.value)
                    }
                }
            }
        }
    }

    private fun titles(): List<TextView> =
        listOf(histogramTitle, topSongsTitle, topArtistsTitle, topGenresTitle)

    private fun renderHistogram(data: HistogramData?) {
        histogram.data = data
        val empty = data == null || data.isEmpty
        histogram.visibility = if (empty) View.GONE else View.VISIBLE
        histogramEmpty.visibility = if (empty) View.VISIBLE else View.GONE

        val unknown = data?.unknownYearCount ?: 0
        histogramUnknown.visibility = if (unknown > 0) View.VISIBLE else View.GONE
        if (unknown > 0) {
            histogramUnknown.text =
                resources.getQuantityString(R.plurals.recount_unknown_year, unknown, unknown)
        }
    }

    private fun renderRecount(data: RecountData?) {
        // Mientras el primer cálculo está en marcha (data == null) no se enseña ni el contenido ni
        // el "no escuchaste nada": un parpadeo del estado vacío antes de que llegue el dato real
        // sería mentira durante una décima de segundo.
        val loading = data == null
        val empty = data != null && data.isEmpty
        content.visibility = if (loading || empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (data == null) return

        songsAdapter.submit(data.topSongs)
        artistsAdapter.submit(data.topArtists)
        genresAdapter.submit(data.topGenres)

        pie.slices = data.genreSlices
        val noGenres = data.genreSlices.isEmpty()
        pie.visibility = if (noGenres) View.GONE else View.VISIBLE
        pieEmpty.visibility = if (noGenres) View.VISIBLE else View.GONE
        renderLegend(data)
    }

    /**
     * La leyenda de la tarta, inflada a mano dentro de un [FlowLayout] (mismo patrón que las
     * etiquetas mini de una fila de canción). Se repinta también al cambiar el acento, porque el
     * cuadradito de cada fila tiene que seguir casando con su sector.
     *
     * A diferencia de la tarta, aquí SÍ aparecen todos los géneros, incluido el sector agregado
     * "Otros": lo que se agrupa es el dibujo, no el dato.
     */
    private fun renderLegend(data: RecountData?) {
        legend.removeAllViews()
        val slices = data?.genreSlices ?: return
        val inflater = LayoutInflater.from(requireContext())
        for ((index, slice) in slices.withIndex()) {
            val row = inflater.inflate(R.layout.item_recount_legend, legend, false)
            AccentTint.fill(row, R.id.legendSwatch, pie.colorAt(index))
            row.findViewById<TextView>(R.id.legendLabel).text = getString(
                R.string.recount_legend_entry,
                if (slice.isOther) getString(R.string.recount_other_genres) else slice.label,
                Math.round(slice.fraction * 100f)
            )
            legend.addView(row)
        }
    }

    companion object {
        private const val TAG = "recount"

        /**
         * Abre la pantalla si no está ya abierta. La guarda del TAG evita apilar dos copias por un
         * doble toque, igual que en el resto de diálogos de la app.
         */
        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            RecountDialogFragment().show(manager, TAG)
        }
    }
}
