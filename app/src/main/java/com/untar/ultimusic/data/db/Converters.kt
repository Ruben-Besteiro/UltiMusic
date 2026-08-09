package com.untar.ultimusic.data.db

import androidx.room.TypeConverter

/**
 * Conversores de tipos para Room. De momento solo la lista de géneros, guardada como texto
 * con un separador de unidad (0x1F) para no colisionar con comas/espacios de los nombres.
 *
 * La pestaña de Géneros (ver [com.untar.ultimusic.data.LibraryRepository.genres]) lee este texto
 * tal cual, sin tabla propia a propósito: un género no tiene ficha, id ni portada, así que no hay
 * nada que una tabla aportara aquí que no aporte ya agrupar esta lista en Kotlin.
 */
class Converters {

    @TypeConverter
    fun fromGenres(genres: List<String>?): String =
        genres?.joinToString(SEPARATOR).orEmpty()

    @TypeConverter
    fun toGenres(data: String?): List<String> =
        if (data.isNullOrEmpty()) emptyList() else data.split(SEPARATOR)

    private companion object {
        const val SEPARATOR = ""
    }
}
