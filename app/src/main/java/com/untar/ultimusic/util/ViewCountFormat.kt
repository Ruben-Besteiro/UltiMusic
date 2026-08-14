package com.untar.ultimusic.util

import java.math.BigDecimal
import java.math.MathContext

/**
 * Compacta un número grande (las visitas del vídeo de una canción, ver
 * [com.untar.ultimusic.model.Song.youtubeViewCount], o los suscriptores del canal de un artista, ver
 * [com.untar.ultimusic.model.PersonSummary.popularity]) a como mucho 3 cifras significativas más un
 * sufijo de magnitud: "los 3 primeros dígitos del número completo, seguido de un sufijo como
 * K, M...", tal y como se pidió. Por debajo de mil se enseña el número entero, sin sufijo: ya cabe
 * en 3 cifras o menos.
 *
 * El sufijo de millar va en mayúscula (K, no k), igual que M y B, para que las tres magnitudes se
 * escriban de la misma forma.
 */
fun formatCompactCount(count: Long): String {
    if (count < 1_000L) return count.toString()

    // De mayor a menor: la primera magnitud cuyo umbral no supere `count` es con la que se empieza.
    val tiers = listOf(1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")
    var index = tiers.indexOfFirst { count >= it.first }
    var scaled: BigDecimal
    while (true) {
        scaled = BigDecimal(count).divide(BigDecimal(tiers[index].first), MathContext(3))
        // Redondear a 3 cifras significativas puede empujar el número justo a la magnitud de
        // arriba (999 999 -> 999.999K, que redondeado da 1000K): en ese caso se sube un peldaño
        // más para que el sufijo siga encajando con el número que lleva delante.
        if (scaled < BigDecimal(1000) || index == 0) break
        index--
    }
    return scaled.stripTrailingZeros().toPlainString() + tiers[index].second
}
