package com.example.opendashx.models

data class ReplayProgress(
    val busy: Boolean = false,
    val phase: String = "Idle",
    val fileName: String = "-",
    val framesProcessed: Int = 0,
    val totalLinesEstimate: Int = 0,
    val percent: Int = 0,
    val message: String = "Ready"
)
