package com.untar.ultimusic.model

import com.untar.ultimusic.util.CoverRef

/**
 * Modelos de DOMINIO de UltiMusic Recount. Ninguno de ellos se guarda: todos se CALCULAN al abrir la
 * pantalla, a partir de las escuchas crudas
 * ([com.untar.ultimusic.data.db.entities.PlayEventEntity]) cruzadas con la fonoteca tal y como está
 * en ese momento (ver [com.untar.ultimusic.data.RecountRepository]).
 *
 * Esa es la razón de que aquí no haya ids de artista ni de género y sí un [RecountEntry.label] de
 * texto: lo que se pinta es el resultado de una agregación, no una entidad. La única entrada que
 * conserva su id es la de una canción viva, y solo para poder resolver su carátula.
 */

/**
 * Una fila de cualquiera de los tres tops (canciones, artistas, géneros): quién es, cuántas veces
 * sonó y cuánto tiempo en total.
 *
 * [cover] es null en los géneros (no tienen imagen, ni ficha, ni id — ver [GenreSummary]) y en la
 * fila especial de canciones borradas.
 *
 * [isDeletedBucket] marca la ÚNICA fila que no representa nada real: el cubo "Canciones borradas",
 * que agrupa todas las escuchas cuyo id ya no corresponde a ninguna canción de la fonoteca. Compite
 * por su puesto en el top 10 de canciones como una más, pero la UI la pinta distinta (sin carátula,
 * en gris) y nunca aparece en los tops de artistas ni de géneros: de una canción que ya no existe no
 * se sabe de quién era ni de qué género.
 *
 * [share] es qué parte del año se llevó esta fila, en reproducciones y NO en tiempo: dos canciones
 * con las mismas escuchas pesan lo mismo aunque una dure el triple que la otra. Se reparte siempre
 * sobre [RecountData.totalPlays], el total de verdad del año, así que en el top de canciones las
 * fracciones suman como mucho 100%, pero en los de artistas y géneros pueden pasarse: una canción
 * con tres artistas suma su reproducción entera a los tres. No es un error de cuentas, es lo que
 * significa la cifra — "de todo lo que escuché este año, un 40% llevaba a este artista".
 */
data class RecountEntry(
    val label: String,
    val plays: Int,
    val playedMs: Long,
    val share: Float = 0f,
    val songId: Long? = null,
    val cover: CoverRef? = null,
    val isDeletedBucket: Boolean = false
)

/** Un sector de la tarta de géneros: qué género, qué fracción del total de reproducciones se lleva
 *  ([fraction], entre 0 y 1) y si es el sector agregado "Otros" (ver [RecountData.genreSlices]). */
data class GenreSlice(
    val label: String,
    val plays: Int,
    val playedMs: Long,
    val fraction: Float,
    val isOther: Boolean = false
)

/**
 * Todo lo que se pinta de un año concreto. [totalPlays]/[totalMs] son el total del año contando
 * también el cubo de canciones borradas (es tiempo que el usuario escuchó de verdad), mientras que
 * los tops de artistas y géneros y la tarta solo reparten lo que se puede atribuir a canciones que
 * siguen existiendo — de ahí que sus sumas no cuadren con el total, y es correcto que no cuadren.
 *
 * [isEmpty] distingue "todavía no hay nada de este año" de "hay datos pero todo son ceros", para que
 * la pantalla pueda enseñar su estado vacío en vez de cuatro listas en blanco.
 */
data class RecountData(
    val year: Int,
    val totalPlays: Int,
    val totalMs: Long,
    val topSongs: List<RecountEntry>,
    val topArtists: List<RecountEntry>,
    val topGenres: List<RecountEntry>,
    val genreSlices: List<GenreSlice>
) {
    val isEmpty: Boolean get() = totalPlays == 0
}

/**
 * Una columna del histograma de la fonoteca. [year] es el año de publicación
 * ([Song.year]) y [songCount] cuántas canciones de la fonoteca lo llevan.
 *
 * [isGapEdge] marca las dos columnas vacías que hacen de borde de un hueco largo (ver
 * [HistogramData.bars]): se dibujan a cero, y entre ellas va la raya vertical que resume todos los
 * años que se han ocultado.
 */
data class HistogramBar(
    val year: Int,
    val songCount: Int,
    val isGapEdge: Boolean = false
)

/**
 * El histograma entero de la fonoteca: cuántas canciones hay de cada año de publicación.
 *
 * [bars] NO es el rango de años completo. Cuando hay una cadena de 3 o más años seguidos sin ninguna
 * canción, solo sobreviven el primero y el último de esa cadena (ambos con [HistogramBar.isGapEdge]
 * a true) y los de en medio desaparecen; la UI dibuja una raya vertical entre esos dos bordes para
 * indicar el salto. Sin esto, una fonoteca con un disco de 1968 y el resto a partir de 2015 se
 * pintaría como 45 columnas vacías y un puñado de barras aplastadas en el borde derecho.
 *
 * [unknownYearCount] son las canciones sin año, que no pueden colocarse en ningún punto del eje X y
 * por eso se cuentan aparte, como nota bajo el gráfico, en vez de inventarles una columna.
 */
data class HistogramData(
    val bars: List<HistogramBar>,
    val unknownYearCount: Int
) {
    val maxCount: Int get() = bars.maxOfOrNull { it.songCount } ?: 0
    val isEmpty: Boolean get() = bars.none { it.songCount > 0 }
}
