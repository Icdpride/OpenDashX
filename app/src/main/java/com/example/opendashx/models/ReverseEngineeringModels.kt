package com.example.opendashx.models

data class ReverseEngineeringCandidate(
    val signal: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: Double,
    val offset: Double,
    val rmse: Double,
    val mae: Double,
    val correlation: Double,
    val confidence: Double,
    val samples: Int,
    val minRaw: Double,
    val maxRaw: Double,
    val minValue: Double,
    val maxValue: Double
) {
    fun idHex(): String = "0x" + id.toUInt().toString(16).uppercase().padStart(if (isExtended) 8 else 3, '0')
}

data class ReverseEngineeringReport(
    val totalFrames: Int,
    val totalSnapshots: Int,
    val candidatesBySignal: Map<String, List<ReverseEngineeringCandidate>>
)
