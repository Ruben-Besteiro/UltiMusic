package com.untar.ultimusic.ui.recount

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untar.ultimusic.data.RecountRepository
import com.untar.ultimusic.model.HistogramData
import com.untar.ultimusic.model.RecountData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

/**
 * Estado de la pantalla de UltiMusic Recount. Vive en el ámbito del propio diálogo, así que se
 * destruye al cerrarlo.
 *
 * No calcula nada: todo sale ya masticado de [RecountRepository]. Solo se muestra el año actual, así
 * que lo único que aporta es ese año, fijado al abrir la pantalla.
 */
class RecountViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RecountRepository.get(app)

    /** El año que se está mirando: siempre el actual. */
    val year: Int = Calendar.getInstance().get(Calendar.YEAR)

    /** Los tops y la tarta del año actual. */
    val recount: StateFlow<RecountData?> = repository.recount(year)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** El histograma NO depende del año: es la fonoteca entera (ver [RecountRepository]). */
    val histogram: StateFlow<HistogramData?> = repository.histogram
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
