package com.untar.ultimusic.model

/**
 * Un sitio donde se puede conseguir el archivo de una canción, sacado de los enlaces curados de
 * MusicBrainz (ver [com.untar.ultimusic.data.remote.MusicBrainzApi.downloadStores]).
 *
 * [name] es el de la tienda ("Bandcamp", "Amazon"...), o el dominio si no se reconoce. [url] es la
 * página del lanzamiento (o de la grabación) en esa tienda, no una búsqueda. [free] es `true` si el
 * enlace es de descarga gratuita (relación `download for free` de MusicBrainz) y `false` si es de
 * compra (`purchase for download`).
 */
data class StoreLink(
    val name: String,
    val url: String,
    val free: Boolean
)
