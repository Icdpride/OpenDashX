package com.example.opendashx.core.dashboard

data class AlarmRule(
    val signal: String,
    val min: Double? = null,
    val max: Double? = null,
    val message: String,
    val enabled: Boolean = true
) {
    fun isActive(value: Double?): Boolean {
        if (!enabled || value == null) return false
        if (min != null && value < min) return true
        if (max != null && value > max) return true
        return false
    }
}
