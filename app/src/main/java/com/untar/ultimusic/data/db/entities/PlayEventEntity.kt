package com.untar.ultimusic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una escucha contada: la canción [songId] sonó [playedMs] milisegundos empezando en [startedAt].
 * Es LO ÚNICO que guarda UltiMusic Recount (ver
 * [com.untar.ultimusic.data.RecountRepository]); el top de artistas, el de géneros, los tiempos y
 * la tarta NO se guardan en ningún sitio, se derivan de estas filas cruzándolas con la fonoteca en
 * el momento de mirar el Recount.
 *
 * Esa es toda la gracia del diseño: como las métricas se recalculan al mirarlas, el Recount -el de
 * este año y los de años pasados- se ajusta solo a lo que el usuario edite después. Si le cambia el
 * género a una canción, el top de géneros de 2024 cambia con ella; si la borra, sus escuchas pasan
 * a "Canciones borradas" porque en ese momento ya no hay ninguna canción con ese id.
 *
 * Solo entra aquí lo que llegó al 50% de la duración de la canción, medido en tiempo REALMENTE
 * sonado, no en posición de la barra (ver [com.untar.ultimusic.playback.PlayTracker]).
 *
 * **NO lleva `foreignKeys`, y es a propósito.** Todas las demás tablas que apuntan a `songs` en
 * esta base de datos lo hacen con `ForeignKey.CASCADE`, así que la tentación de "arreglar" la
 * omisión es real: no lo hagas. Una cascada borraría estas filas justo al borrar la canción, que es
 * el único caso en el que hacen falta enteras — sin ellas no habría "Canciones borradas" y el
 * tiempo escuchado del año se encogería solo cada vez que el usuario limpia la fonoteca.
 *
 * Que un id huérfano signifique de verdad "borrada" y no "otra canción distinta" lo garantiza
 * SQLite: `autoGenerate = true` se traduce en `INTEGER PRIMARY KEY AUTOINCREMENT`, que nunca
 * reutiliza un id ya usado. Ojo con el reverso: si el usuario borra una canción desde la app y
 * luego el escaneo vuelve a encontrar el archivo, entra como fila NUEVA con id nuevo, así que las
 * escuchas viejas se quedan en "Canciones borradas" aunque la canción esté otra vez ahí. Es lo
 * correcto según la regla del Recount ("en el momento de mirarlo, esa canción no existe"), pero
 * conviene saberlo.
 *
 * [year] va desnormalizado, y no se calcula al vuelo con `strftime('%Y', ..., 'localtime')`, porque
 * ese `'localtime'` usa la zona horaria del dispositivo EN EL MOMENTO DE LA CONSULTA: quien se
 * mudara de país vería reasignarse escuchas de diciembre al año anterior o siguiente. El año se
 * decide una sola vez, con el calendario local del instante en que la canción empezó a sonar, y ya
 * no se vuelve a tocar. (De ahí también que una escucha que cruza la medianoche del 31 de diciembre
 * cuente para el año en el que empezó.)
 */
@Entity(
    tableName = "play_events",
    indices = [Index("year"), Index("songId")]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    /** Epoch ms del instante en que la canción empezó a sonar. */
    val startedAt: Long,
    /** Milisegundos que sonó de verdad, sin contar las pausas. Puede superar la duración de la
     *  canción si el usuario rebobinó y volvió a escuchar trozos dentro de la misma escucha. */
    val playedMs: Long,
    /** Año local de [startedAt]. Ver la cabecera de la clase sobre por qué está desnormalizado. */
    val year: Int
)
