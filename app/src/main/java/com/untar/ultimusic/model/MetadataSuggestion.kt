package com.untar.ultimusic.model

/** De qué campo del editor viene la búsqueda: cambia qué se busca (canción vs álbum) y qué
 * campos del formulario tiene sentido rellenar después. */
enum class SuggestionKind { SONG, ALBUM }

/**
 * Qué clase de publicación es un candidato, para la etiqueta de su fila en la lista de sugerencias.
 *
 * iTunes no lo da como campo: se deduce del nombre de la colección y del número de pistas (ver
 * `ItunesApi.releaseTypeOf`), y por eso siempre hay uno — no hace falta contemplar el caso de "sin
 * clasificar". Tampoco hay tipos secundarios (en directo,
 * recopilatorio, banda sonora, remix): esa información sencillamente no existe en el catálogo de
 * iTunes.
 */
enum class ReleaseType { ALBUM, SINGLE, EP }

/**
 * Un candidato de autorrelleno encontrado en iTunes, para
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment].
 *
 * Representa siempre UNA publicación concreta (lo que iTunes llama una *collection*: el álbum, el
 * single, un recopilatorio…), nunca la canción en abstracto: así se puede elegir, por ejemplo, el
 * año y la portada del single en vez de los del álbum en el que la canción acabó apareciendo, que
 * es el caso que motivó todo el autorrelleno. En iTunes eso sale gratis, porque cada resultado de
 * búsqueda de canción YA es "esta grabación dentro de esta publicación".
 *
 * Es un único modelo compartido por canción y álbum (en vez de dos separados) porque el diálogo,
 * el adaptador de la lista y el ViewModel de sugerencias son los mismos para los dos casos; lo
 * único que cambia es qué campos trae rellenos cada uno.
 */
data class MetadataSuggestion(
    /** Título de la canción o del álbum, según qué se haya buscado (ver [SuggestionKind]). */
    val title: String,
    val artist: String,
    /** Álbum al que pertenece esta publicación. Solo tiene sentido en una sugerencia de canción: en
     * una de álbum, [title] YA es el título del álbum, así que se queda a null. */
    val albumTitle: String?,
    val year: Int?,
    /** URL de la carátula en iTunes, en tamaño miniatura para la lista. Es lo primero que pinta cada
     * fila (ver [com.untar.ultimusic.ui.editor.MetadataSuggestionsAdapter]); en una sugerencia de
     * canción con Genius disponible, la fila la sustituye después por la carátula propia de la
     * canción si la encuentra (ver
     * [com.untar.ultimusic.ui.editor.MetadataSuggestionsViewModel.coverArtOverride]), sin dejar la
     * fila en blanco mientras tanto. Al elegir el candidato se pide la misma imagen ganadora a máxima
     * resolución (ver [com.untar.ultimusic.data.remote.ItunesApi.fullResCoverUrl]) — cuál gana lo
     * decide [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment.coverUrlFor] con la
     * misma regla que [coverArtOverride]. Null solo si el resultado llegara sin carátula; Coil cae
     * entonces al placeholder de siempre, como en el resto de la app. */
    val coverUrl: String?,
    /** iTunes clasifica cada publicación con UN género (`primaryGenreName`), no con una lista de
     * etiquetas votadas como hacía MusicBrainz. Viene ya en la respuesta de búsqueda, así que no
     * hace falta ninguna petición aparte al elegir el candidato. Llega traducido al idioma de la
     * tienda consultada (ver `ItunesApi.storefront`). */
    val genre: String?,
    /** Solo en una sugerencia de canción: dónde cae esta grabación dentro de esta publicación. */
    val trackNumber: Int?,
    val discNumber: Int?,
    val releaseType: ReleaseType
)
