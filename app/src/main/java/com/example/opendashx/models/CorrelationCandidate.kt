package com.example.opendashx.models

data class CorrelationCandidate(
    val name: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val currentRaw: Double,
    val currentScaled: Double,
    val minScaled: Double,
    val maxScaled: Double,
    val changeScore: Double,
    val dataHex: String
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }

    fun valueText(): String {
        return when (name) {
            "RPM" -> "${currentScaled.toInt()} rpm"
            "TPS" -> "%.1f %%".format(currentScaled)
            "MAP" -> "%.1f kPa".format(currentScaled)
            "Battery" -> "%.2f V".format(currentScaled)
            "AFR" -> "%.2f".format(currentScaled)
            "Coolant" -> "%.1f F".format(currentScaled)
            else -> "%.2f".format(currentScaled)
        }
    }
}
