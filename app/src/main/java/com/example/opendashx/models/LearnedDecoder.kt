package com.example.opendashx.models

data class LearnedDecoder(
    val signal: String,
    val confidence: Double,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val formula: String,
    val scale: Double,
    val offset: Double,
    val min: Double,
    val max: Double,
    val sampleCount: Int,
    val error: Double,
    val latestValue: Double? = null
) {
    fun idHex(): String = "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
}
