package com.untarlamanteca.ultimusic.data.db

import androidx.room.TypeConverter

/**
 * Conversores de tipos para Room. De momento solo la lista de géneros, guardada como texto
 * con un separador de unidad (0x1F) para no colisionar con comas/espacios de los nombres.
 *
 * TODO: cuando exista la pestaña de Géneros, normalizarlos en su propia tabla.
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
