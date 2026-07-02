package com.example.opendashx.models

data class GroundTruthSnapshot(
    val timestampMs: Long,
    val label: String,
    val rpm: Double?,
    val tps: Double?,
    val mapKpa: Double?,
    val coolantF: Double?,
    val afr: Double?,
    val batteryV: Double?,
    val frameCount: Int
)

data class LearnedSignalDefinition(
    val signal: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: Double,
    val offset: Double,
    val error: Double,
    val confidence: Double,
    val samples: Int
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }

    fun formula(): String {
        val scaleText = "%.6f".format(scale).trimEnd('0').trimEnd('.')
        val offsetText = "%.3f".format(offset).trimEnd('0').trimEnd('.')
        return if (offset >= 0.0) "$expression * $scaleText + $offsetText"
        else "$expression * $scaleText - ${offsetText.removePrefix("-")}"
    }
}
