package com.example.opendashx.models

data class DecodedSignalCandidate(
    val label: String,
    val id: Int,
    val isExtended: Boolean,
    val byteRange: String,
    val endian: String,
    val rawValue: Int,
    val scaledValue: Double,
    val score: Double,
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
        return when (label) {
            "RPM" -> "${scaledValue.toInt()} rpm"
            "TPS" -> "%.1f %%".format(scaledValue)
            "MAP" -> "%.1f kPa".format(scaledValue)
            "Battery" -> "%.2f V".format(scaledValue)
            "Coolant" -> "%.1f °F".format(scaledValue)
            "IAT" -> "%.1f °F".format(scaledValue)
            "AFR" -> "%.2f".format(scaledValue)
            else -> "%.2f".format(scaledValue)
        }
    }
}
