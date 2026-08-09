package com.untar.ultimusic.model

/** De qué campo del editor viene la búsqueda: cambia qué se busca (canción vs álbum) y qué
 * campos del formulario tiene sentido rellenar después. */
enum class SuggestionKind { SONG, ALBUM }

/**
 * Un candidato de autorrelleno encontrado en MusicBrainz, para
 * [com.untar.ultimusic.ui.editor.MetadataSuggestionsDialogFragment].
 *
 * MusicBrainz distingue la grabación en abstracto (*Recording*, "esta canción, la que sea que
 * suene") de sus publicaciones concretas (*Release*Release Group*: el álbum, el single, un
 * recopilatorio...), cada una con su propio año y su propia portada. Por eso este modelo
 * representa siempre UNA publicación, nunca la canción en abstracto: así se puede elegir, por
 * ejemplo, el año y la portada del single en vez de los del álbum en el que acabó apareciendo,
 * que es justo lo que [com.untar.ultimusic.data.remote.MusicBrainzApi] resuelve.
 *
 * Es un único modelo compartido por canción y álbum (en vez de dos separados) porque el diálogo,
 * el adaptador de la lista y el ViewModel de sugerencias son los mismos para los dos casos; lo
 * único que cambia es qué campos trae rellenos cada uno (ver [kind]).
 */
data class MetadataSuggestion(
    val kind: SuggestionKind,
    /** Título de la canción ([SuggestionKind.SONG]) o del álbum ([SuggestionKind.ALBUM]). */
    val title: String,
    val artist: String,
    /** Álbum al que pertenece esta publicación. Solo tiene sentido en [SuggestionKind.SONG]: en
     * una sugerencia de álbum, [title] YA es el título del álbum. */
    val albumTitle: String?,
    val year: Int?,
    /** Código de país de esta publicación tal como lo da MusicBrainz (p. ej. "JP", "US"; "XW" es
     * "en todo el mundo"). Solo en [SuggestionKind.SONG]: en una sugerencia de álbum no hay una
     * única publicación elegida de la que sacarlo (ver [com.untar.ultimusic.data.remote.
     * MusicBrainzApi.searchAlbums]). */
    val country: String?,
    /** URL de Cover Art Archive. No siempre hay portada archivada para una publicación (404
     * frecuente); se deja que Coil caiga solo al placeholder de siempre, como en el resto de la
     * app, en vez de comprobarlo antes con una petición aparte. */
    val coverUrl: String?,
    /** Id de MusicBrainz con el que pedir los géneros de ESTE candidato, y solo de este: ver
     * [com.untar.ultimusic.data.remote.MusicBrainzApi.fetchGenres]. Es el `recording.id` en
     * canciones o el `release-group.id` en álbumes — nunca el de la publicación (`release.id`),
     * que no tiene géneros propios en MusicBrainz. */
    val genreEntityId: String,
    /** Solo en [SuggestionKind.SONG]: dónde cae esta grabación dentro de esta publicación. */
    val trackNumber: Int?,
    val discNumber: Int?,
    /** "Album", "Single", "EP"... tal cual lo da MusicBrainz, en inglés; se traduce a español
     * solo al pintar la fila (ver `MetadataSuggestionsAdapter`), para no mezclar el dato con su
     * presentación. */
    val primaryType: String?,
    /** "Live", "Compilation", "Soundtrack", "Remix"... Puede haber varios a la vez (un directo
     * recopilatorio, por ejemplo). */
    val secondaryTypes: List<String>
)
