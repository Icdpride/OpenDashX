package com.example.opendashx.services

import com.example.opendashx.models.LiveDashboardSnapshot
import kotlin.math.abs

class LiveDecoderSmoother {
    private val lock = Any()

    private var rpm: Double? = null
    private var tps: Double? = null
    private var mapKpa: Double? = null
    private var coolantF: Double? = null
    private var afr: Double? = null
    private var batteryV: Double? = null

    private var decodedCount: Int = 0
    private var sourceFrameId: String = "-"
    private var sourceTimeMs: Long = 0L
    private var lastUiSnapshotMs: Long = 0L

    fun observe(raw: LiveDashboardSnapshot) {
        if (raw.decodedCount <= 0) return

        synchronized(lock) {
            rpm = smooth("rpm", rpm, raw.rpm)
            tps = smooth("tps", tps, raw.tps)
            mapKpa = smooth("map", mapKpa, raw.mapKpa)
            coolantF = smooth("coolant", coolantF, raw.coolantF)
            afr = smooth("afr", afr, raw.afr)
            batteryV = smooth("battery", batteryV, raw.batteryV)

            decodedCount = raw.decodedCount
            sourceFrameId = raw.sourceFrameId
            sourceTimeMs = raw.sourceTimeMs
        }
    }

    fun snapshot(): LiveDashboardSnapshot {
        synchronized(lock) {
            lastUiSnapshotMs = System.currentTimeMillis()
            return LiveDashboardSnapshot(
                rpm = rpm,
                tps = tps,
                mapKpa = mapKpa,
                coolantF = coolantF,
                afr = afr,
                batteryV = batteryV,
                decodedCount = decodedCount,
                sourceFrameId = sourceFrameId,
                sourceTimeMs = sourceTimeMs
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            rpm = null
            tps = null
            mapKpa = null
            coolantF = null
            afr = null
            batteryV = null
            decodedCount = 0
            sourceFrameId = "-"
            sourceTimeMs = 0L
            lastUiSnapshotMs = 0L
        }
    }

    private fun smooth(name: String, previous: Double?, next: Double?): Double? {
        if (next == null || next.isNaN() || next.isInfinite()) return previous
        if (!isReasonable(name, previous, next)) return previous
        if (previous == null) return next

        val alpha = when (name) {
            "rpm" -> 0.22
            "tps" -> 0.18
            "map" -> 0.16
            "afr" -> 0.12
            "coolant" -> 0.06
            "battery" -> 0.08
            else -> 0.15
        }

        return previous + ((next - previous) * alpha)
    }

    private fun isReasonable(name: String, previous: Double?, next: Double): Boolean {
        val inRange = when (name) {
            "rpm" -> next in 0.0..9000.0
            "tps" -> next in -5.0..110.0
            "map" -> next in 0.0..400.0
            "afr" -> next in 5.0..30.0
            "coolant" -> next in -40.0..320.0
            "battery" -> next in 5.0..22.0
            else -> true
        }
        if (!inRange) return false
        if (previous == null) return true

        val maxJump = when (name) {
            "rpm" -> 2500.0
            "tps" -> 55.0
            "map" -> 180.0
            "afr" -> 8.0
            "coolant" -> 20.0
            "battery" -> 4.0
            else -> Double.MAX_VALUE
        }

        return abs(next - previous) <= maxJump
    }
}
