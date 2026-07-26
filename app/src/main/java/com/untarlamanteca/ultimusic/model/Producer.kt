package com.untarlamanteca.ultimusic.model

/**
 * Productor de una canción. Es el gemelo de [Artist]: mismos campos, mismo tratamiento y misma
 * pestaña propia. Se separan en dos modelos (en vez de reutilizar [Artist] con un "rol") porque
 * una misma persona puede aparecer como artista y como productor con imágenes/nombres distintos, y
 * porque así cada tabla se poda de forma independiente.
 */
data class Producer(
    val id: Long = 0,
    val name: String,
    val imageName: String?
)
