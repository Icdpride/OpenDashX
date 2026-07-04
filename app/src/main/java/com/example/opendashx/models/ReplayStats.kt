package com.example.opendashx.models

data class ReplayStats(
    val loaded: Boolean = false,
    val fileName: String = "-",
    val frameCount: Int = 0,
    val idCount: Int = 0,
    val durationMs: Long = 0,
    val status: String = "No replay loaded"
)
