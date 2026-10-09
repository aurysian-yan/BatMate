package com.folio.batterysync

internal object BatteryValues {
    fun percentage(level: Int, scale: Int): Int? =
        if (scale > 0 && level in 0..scale) ((level.toLong() * 100) / scale).toInt() else null
}
