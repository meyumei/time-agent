package com.timeagent.projects

/** The project dot and its timer notification use the same stable palette. */
object ProjectColor {
    private val palette = intArrayOf(0xFF5B8DEF.toInt(), 0xFFE57373.toInt(), 0xFF4DB6AC.toInt(),
        0xFF9575CD.toInt(), 0xFFFFB74D.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt())

    fun argb(name: String): Int = palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
}
