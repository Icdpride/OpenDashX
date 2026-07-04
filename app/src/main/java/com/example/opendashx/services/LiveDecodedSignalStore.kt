package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.LearnedSignalDefinition
import com.example.opendashx.models.LiveDashboardSnapshot
import kotlin.math.abs

class LiveDecodedSignalStore {
    private val lock = Any()

    private var rpm: Double? = null
    private var tps: Double? = null
    private var mapKpa: Double? = null
    private var coolantF: Double? = null
    private var afr: Double? = null
    private var batteryV: Double? = null

    private var decodedTotal: Long = 0
    private var acceptedFrames: Long = 0
    private var ignoredFrames: Long = 0
    private var sourceFrameId: String = "-"
    private var sourceTimeMs: Long = 0L
    private var lastUpdateMs: Long = 0L

    fun reset() {
        synchronized(lock) {
            rpm = null
            tps = null
            mapKpa = null
            coolantF = null
            afr = null
            batteryV = null
            decodedTotal = 0
            acceptedFrames = 0
            ignoredFrames = 0
            sourceFrameId = "-"
            sourceTimeMs = 0L
            lastUpdateMs = 0L
        }
    }

    fun observe(frame: CanLogFrame, definitions: List<LearnedSignalDefinition>) {
        if (definitions.isEmpty()) return

        val relevant = definitions.any { it.id == frame.id && it.isExtended == frame.isExtended }
        if (!relevant) {
            synchronized(lock) { ignoredFrames++ }
            return
        }

        var decodedThisFrame = 0

        synchronized(lock) {
            for (def in definitions) {
                if (def.id != frame.id || def.isExtended != frame.isExtended) continue
                val raw = rawValue(def.expression, frame.data) ?: continue
                val value = raw * def.scale + def.offset
                if (!isReasonable(def.signal, value)) continue

                when (def.signal.lowercase()) {
                    "rpm" -> rpm = smooth("rpm", rpm, value)
                    "tps" -> tps = smooth("tps", tps, value)
                    "map" -> mapKpa = smooth("map", mapKpa, value)
                    "coolant" -> coolantF = smooth("coolant", coolantF, value)
                    "afr" -> afr = smooth("afr", afr, value)
                    "battery" -> batteryV = smooth("battery", batteryV, value)
                }
                decodedThisFrame++
            }

            if (decodedThisFrame > 0) {
                decodedTotal += decodedThisFrame.toLong()
                acceptedFrames++
                sourceFrameId = frame.idHex()
                sourceTimeMs = frame.timestampMs
                lastUpdateMs = System.currentTimeMillis()
            }
        }
    }

    fun snapshot(): LiveDashboardSnapshot {
        synchronized(lock) {
            return LiveDashboardSnapshot(
                rpm = rpm,
                tps = tps,
                mapKpa = mapKpa,
                coolantF = coolantF,
                afr = afr,
                batteryV = batteryV,
                decodedCount = decodedTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                sourceFrameId = "$sourceFrameId  accepted=$acceptedFrames ignored=$ignoredFrames age=${System.currentTimeMillis() - lastUpdateMs}ms",
                sourceTimeMs = sourceTimeMs
            )
        }
    }

    private fun smooth(name: String, previous: Double?, next: Double): Double {
        if (previous == null) return next

        val maxJump = when (name) {
            "rpm" -> 900.0
            "tps" -> 22.0
            "map" -> 50.0
            "afr" -> 2.5
            "coolant" -> 4.0
            "battery" -> 0.8
            else -> Double.MAX_VALUE
        }

        if (abs(next - previous) > maxJump) return previous

        val alpha = when (name) {
            "rpm" -> 0.10
            "tps" -> 0.08
            "map" -> 0.08
            "afr" -> 0.05
            "coolant" -> 0.025
            "battery" -> 0.03
            else -> 0.08
        }

        return previous + ((next - previous) * alpha)
    }

    private fun isReasonable(signal: String, value: Double): Boolean {
        if (value.isNaN() || value.isInfinite()) return false
        return when (signal.lowercase()) {
            "rpm" -> value in 0.0..9000.0
            "tps" -> value in -5.0..110.0
            "map" -> value in 0.0..400.0
            "coolant" -> value in -40.0..320.0
            "afr" -> value in 5.0..30.0
            "battery" -> value in 5.0..22.0
            else -> true
        }
    }

    private fun rawValue(expr: String, data: ByteArray): Double? {
        val offset = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
        fun b(i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0
        fun s8(i: Int): Int = b(i).toByte().toInt()
        fun u16le(i: Int): Int = b(i) or (b(i + 1) shl 8)
        fun u16be(i: Int): Int = (b(i) shl 8) or b(i + 1)
        fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v
        fun u24le(i: Int): Int = b(i) or (b(i + 1) shl 8) or (b(i + 2) shl 16)
        fun u24be(i: Int): Int = (b(i) shl 16) or (b(i + 1) shl 8) or b(i + 2)
        fun u32le(i: Int): Double = (b(i).toLong() or (b(i + 1).toLong() shl 8) or (b(i + 2).toLong() shl 16) or (b(i + 3).toLong() shl 24)).toDouble()
        fun u32be(i: Int): Double = ((b(i).toLong() shl 24) or (b(i + 1).toLong() shl 16) or (b(i + 2).toLong() shl 8) or b(i + 3).toLong()).toDouble()

        return when {
            expr.startsWith("U8") -> b(offset).toDouble()
            expr.startsWith("S8") -> s8(offset).toDouble()
            expr.startsWith("U16LE") -> u16le(offset).toDouble()
            expr.startsWith("U16BE") -> u16be(offset).toDouble()
            expr.startsWith("S16LE") -> s16(u16le(offset)).toDouble()
            expr.startsWith("S16BE") -> s16(u16be(offset)).toDouble()
            expr.startsWith("U24LE") -> u24le(offset).toDouble()
            expr.startsWith("U24BE") -> u24be(offset).toDouble()
            expr.startsWith("U32LE") -> u32le(offset)
            expr.startsWith("U32BE") -> u32be(offset)
            else -> null
        }
    }
}
