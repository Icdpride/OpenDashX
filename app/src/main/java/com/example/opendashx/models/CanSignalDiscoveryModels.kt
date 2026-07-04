package com.example.opendashx.models

data class ByteActivity(
    val byteIndex: Int,
    val min: Int,
    val max: Int,
    val changes: Int,
    val uniqueValues: Int,
    val activityPct: Double,
    val entropy: Double
)

data class FieldDiscoveryCandidate(
    val signal: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: Double,
    val offset: Double,
    val rmse: Double,
    val mae: Double,
    val correlation: Double,
    val samples: Int,
    val minRaw: Double,
    val maxRaw: Double,
    val minTruth: Double,
    val maxTruth: Double
) {
    fun idHex(): String = "0x" + id.toUInt().toString(16).uppercase().padStart(if (isExtended) 8 else 3, '0')
}

data class CanIdDiscovery(
    val id: Int,
    val isExtended: Boolean,
    val frameCount: Int,
    val byteActivity: List<ByteActivity>,
    val candidates: List<FieldDiscoveryCandidate>
) {
    fun idHex(): String = "0x" + id.toUInt().toString(16).uppercase().padStart(if (isExtended) 8 else 3, '0')
}

data class CanSignalDiscoveryReport(
    val totalFrames: Int,
    val totalSnapshots: Int,
    val ids: List<CanIdDiscovery>,
    val bestBySignal: Map<String, List<FieldDiscoveryCandidate>>
)
