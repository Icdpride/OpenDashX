package com.example.opendashx.core.decoder

data class DecodedSignal(
    val name: String,
    val value: Double,
    val unit: String,
    val confidence: Double = 1.0,
    val sourceId: Int? = null,
    val isExtended: Boolean = true,
    val expression: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun displayValue(): String {
        return when (unit) {
            "rpm" -> "${value.toInt()} rpm"
            "%" -> "%.1f %%".format(value)
            "kPa" -> "%.1f kPa".format(value)
            "F" -> "%.1f F".format(value)
            "V" -> "%.2f V".format(value)
            "AFR" -> "%.2f".format(value)
            "psi" -> "%.1f psi".format(value)
            else -> "%.2f %s".format(value, unit)
        }
    }
}
