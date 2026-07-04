package com.example.opendashx.models

data class LiveDashboardSnapshot(
    val rpm: Double? = null,
    val tps: Double? = null,
    val mapKpa: Double? = null,
    val coolantF: Double? = null,
    val afr: Double? = null,
    val batteryV: Double? = null,
    val decodedCount: Int = 0,
    val sourceFrameId: String = "-",
    val sourceTimeMs: Long = 0L
) {
    fun display(value: Double?, decimals: Int = 1): String {
        return if (value == null || value.isNaN() || value.isInfinite()) "--"
        else "%.${decimals}f".format(value)
    }
}
