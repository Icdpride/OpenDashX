package com.example.opendashx.models

data class ReplayCursorState(
    val loaded: Boolean = false,
    val fileName: String = "-",
    val totalFrames: Int = 0,
    val cursorIndex: Int = 0,
    val cursorTimeMs: Long = 0L,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L
) {
    val percent: Int
        get() = if (totalFrames <= 1) 0 else ((cursorIndex.toDouble() / (totalFrames - 1).toDouble()) * 100.0).toInt().coerceIn(0, 100)

    val elapsedSec: Double
        get() = ((cursorTimeMs - startTimeMs).coerceAtLeast(0L)).toDouble() / 1000.0
}
