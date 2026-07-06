package com.example.opendashx.models

data class ProfilerSignal(
    val signal: String,
    val canId: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: String,
    val value: Double,
    val confidence: Double,
    val minValue: Double,
    val maxValue: Double,
    val note: String
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + canId.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + canId.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }

    fun displayValue(): String {
        return when (signal) {
            "RPM" -> "${value.toInt()} rpm"
            "TPS" -> "%.1f %%".format(value)
            "MAP" -> "%.1f kPa".format(value)
            "Battery" -> "%.2f V".format(value)
            "AFR" -> "%.2f".format(value)
            "Coolant" -> "%.1f F".format(value)
            else -> "%.2f".format(value)
        }
    }
}
