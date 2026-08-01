package com.untar.ultimusic.util

/**
 * Traduce un enlace de YouTube al identificador de 11 caracteres que necesita el reproductor.
 *
 * Existe porque el reproductor (`YouTubePlayer.loadVideo`) NO acepta una URL: quiere solo el ID. Y
 * el mismo vídeo se puede escribir de muchas maneras según de dónde se copie el enlace —el botón de
 * compartir de la app da `youtu.be`, la barra del navegador da `youtube.com/watch?v=`, un Short da
 * `/shorts/`—, así que todas tienen que acabar en el mismo ID.
 *
 * Un ID de YouTube son siempre 11 caracteres de `A-Z a-z 0-9 - _`. Comprobarlo sirve de validación:
 * si lo que el usuario pegó no encaja, [videoId] devuelve null y quien llame avisa en vez de
 * intentar reproducir algo que no existe.
 */
object YouTubeUrl {

    /** Los 11 caracteres exactos que forman un identificador de vídeo. */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    /**
     * Rutas cuyo primer segmento es un ID: `youtu.be/ID`, `/embed/ID`, `/shorts/ID`, `/live/ID` y
     * `/v/ID`. Se tratan todas igual porque todas tienen la misma forma.
     */
    private val PATH_PREFIXES = listOf("embed/", "shorts/", "live/", "v/")

    /**
     * Extrae el ID de [input], o null si no se reconoce.
     *
     * Se hace a mano en vez de con `Uri.parse` porque así funciona también con lo que el usuario
     * escriba a medias (sin `https://`, sin `www.`) y, de paso, la función no depende de Android y
     * se puede probar sin emulador.
     */
    fun videoId(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // Caso 1: han pegado directamente el ID, sin enlace ninguno.
        if (raw.matches(ID_PATTERN)) return raw

        // Quitamos protocolo, subdominio y, si lo hay, el prefijo de móvil, para dejar la ruta
        // desnuda: así `https://m.youtube.com/watch?v=X` y `youtube.com/watch?v=X` se tratan igual.
        val bare = raw
            .substringAfter("://")
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("music.")

        // Caso 2: `watch?v=ID`, que es la forma normal. El ID puede llevar detrás más parámetros
        // (`&t=42s`, `&list=...`), así que cortamos en el primer `&`.
        if (bare.contains("watch?")) {
            val query = bare.substringAfter("watch?")
            // Los parámetros pueden venir en cualquier orden: buscamos el que se llame `v`.
            val v = query.split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            if (v != null && v.matches(ID_PATTERN)) return v
        }

        // Caso 3: el ID es el primer trozo de la ruta. `youtu.be` lleva el ID pegado al dominio; el
        // resto van tras una carpeta conocida. En ambos casos hay que cortar lo que venga después
        // (`?si=...` del botón de compartir, o un `/` de más).
        val afterHost = when {
            bare.startsWith("youtu.be/") -> bare.removePrefix("youtu.be/")
            else -> {
                val path = bare.substringAfter("youtube.com/", missingDelimiterValue = "")
                PATH_PREFIXES.firstOrNull { path.startsWith(it) }
                    ?.let { path.removePrefix(it) }
                    ?: return null
            }
        }
        val id = afterHost.substringBefore('?').substringBefore('&').substringBefore('/')
        return id.takeIf { it.matches(ID_PATTERN) }
    }

    /** URL de la lista de resultados de YouTube para [query]. La abre el buscador del iPod. */
    fun searchUrl(query: String): String =
        "https://m.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")

    /** Enlace canónico de un vídeo, que es lo que se guarda en `Song.videoUrl`. */
    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /**
     * Miniatura oficial de un vídeo, servida por el propio YouTube (no es una captura del
     * reproductor: es un recurso público pensado para esto, así que se puede cargar y mostrar sin
     * restricción). Se usa `hqdefault.jpg` porque existe siempre para cualquier vídeo público; las
     * resoluciones mayores (`maxresdefault.jpg`, etc.) no las tienen todos los vídeos y, si falta,
     * el servidor no da error: devuelve igualmente una imagen (un recuadro gris), así que Coil no
     * podría detectar el fallo para caer en la siguiente.
     */
    fun thumbnailUrl(videoId: String): String = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
}
