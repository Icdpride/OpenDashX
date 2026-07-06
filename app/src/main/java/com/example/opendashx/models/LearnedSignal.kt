package com.example.opendashx.models

data class LearnedSignal(
    val signal: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: String,
    val currentValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val confidence: Double,
    val reason: String,
    val dataHex: String
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }

    fun displayValue(): String {
        return when (signal) {
            "RPM" -> "${currentValue.toInt()} rpm"
            "TPS" -> "%.1f %%".format(currentValue)
            "MAP" -> "%.1f kPa".format(currentValue)
            "Battery" -> "%.2f V".format(currentValue)
            "AFR" -> "%.2f".format(currentValue)
            "Coolant" -> "%.1f F".format(currentValue)
            else -> "%.2f".format(currentValue)
        }
    }
}
