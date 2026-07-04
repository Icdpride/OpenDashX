package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.LearnedSignalDefinition
import com.example.opendashx.models.LiveDashboardSnapshot

class ReplaySignalDecoder {
    fun decode(frame: CanLogFrame?, definitions: List<LearnedSignalDefinition>): LiveDashboardSnapshot {
        if (frame == null || definitions.isEmpty()) return LiveDashboardSnapshot()

        var rpm: Double? = null
        var tps: Double? = null
        var map: Double? = null
        var coolant: Double? = null
        var afr: Double? = null
        var battery: Double? = null
        var count = 0

        definitions.forEach { def ->
            if (def.id != frame.id || def.isExtended != frame.isExtended) return@forEach
            val raw = rawValue(def.expression, frame.data) ?: return@forEach
            val value = raw * def.scale + def.offset
            count++
            when (def.signal.lowercase()) {
                "rpm" -> rpm = value
                "tps" -> tps = value
                "map" -> map = value
                "coolant" -> coolant = value
                "afr" -> afr = value
                "battery" -> battery = value
            }
        }

        return LiveDashboardSnapshot(
            rpm = rpm,
            tps = tps,
            mapKpa = map,
            coolantF = coolant,
            afr = afr,
            batteryV = battery,
            decodedCount = count,
            sourceFrameId = frame.idHex(),
            sourceTimeMs = frame.timestampMs
        )
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
