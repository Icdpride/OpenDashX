package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.ProfilerSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SignalProfiler {
    data class FrameGroup(
        val id: Int,
        val isExtended: Boolean,
        val frames: List<CanLogFrame>
    )

    fun profile(frames: List<CanLogFrame>): List<ProfilerSignal> {
        if (frames.size < 100) return emptyList()

        val groups = frames
            .groupBy { "${it.id}:${it.isExtended}" }
            .values
            .filter { it.size >= 50 }
            .map { FrameGroup(it.first().id, it.first().isExtended, it) }

        val candidates = mutableListOf<ProfilerSignal>()
        for (g in groups) {
            for (expr in expressions()) {
                val rawValues = g.frames.mapNotNull { raw(expr, it.data) }
                if (rawValues.size < 20) continue
                val rawMin = rawValues.minOrNull() ?: continue
                val rawMax = rawValues.maxOrNull() ?: continue
                val rawRange = rawMax - rawMin
                if (rawRange == 0.0) continue

                for (scale in scales()) {
                    val values = rawValues.map { applyScale(it, scale) }
                    val cur = values.last()
                    val lo = values.minOrNull() ?: cur
                    val hi = values.maxOrNull() ?: cur
                    val range = hi - lo
                    val activity = rawRange + changeScore(values)

                    addIfValid(candidates, "RPM", g, expr, scale, cur, lo, hi, activity, range)
                    addIfValid(candidates, "TPS", g, expr, scale, cur, lo, hi, activity, range)
                    addIfValid(candidates, "MAP", g, expr, scale, cur, lo, hi, activity, range)
                    addIfValid(candidates, "Battery", g, expr, scale, cur, lo, hi, activity, range)
                    addIfValid(candidates, "AFR", g, expr, scale, cur, lo, hi, activity, range)
                    addIfValid(candidates, "Coolant", g, expr, scale, cur, lo, hi, activity, range)
                }
            }
        }

        return candidates.sortedWith(
            compareByDescending<ProfilerSignal> { it.confidence }
                .thenBy { it.signal }
                .thenBy { it.canId }
        )
    }

    fun profileBestBySignal(frames: List<CanLogFrame>): List<ProfilerSignal> {
        return profile(frames)
            .groupBy { it.signal }
            .mapNotNull { it.value.maxByOrNull { s -> s.confidence } }
            .sortedBy { it.signal }
    }

    private fun addIfValid(
        out: MutableList<ProfilerSignal>,
        signal: String,
        g: FrameGroup,
        expr: String,
        scale: String,
        cur: Double,
        minValue: Double,
        maxValue: Double,
        activity: Double,
        range: Double
    ) {
        val valid = when (signal) {
            "RPM" -> cur in 450.0..8500.0 && maxValue in 700.0..9000.0 && range > 250.0
            "TPS" -> cur in 0.0..100.0 && minValue >= -2.0 && maxValue <= 105.0 && range > 5.0
            "MAP" -> cur in 15.0..300.0 && maxValue <= 320.0 && range > 2.0
            "Battery" -> cur in 10.0..18.8 && minValue >= 8.0 && maxValue <= 20.0 && range < 6.0
            "AFR" -> cur in 8.0..22.5 && minValue >= 5.0 && maxValue <= 25.0
            "Coolant" -> cur in 40.0..260.0 && minValue >= -40.0 && maxValue <= 300.0
            else -> false
        }
        if (!valid) return

        val widthPriority = when {
            signal == "RPM" && expr.startsWith("U16") -> 250.0
            signal == "RPM" && expr.startsWith("U24") -> -300.0
            signal == "TPS" && expr.startsWith("U8") -> 200.0
            signal in listOf("Battery", "AFR", "Coolant") && expr.startsWith("S16") -> 170.0
            expr.startsWith("U24") -> -500.0
            else -> 20.0
        }

        val scalePriority = when {
            signal == "RPM" && scale in listOf("/4", "/8", "/10", "/16") -> 180.0
            signal == "TPS" && scale in listOf("pct255", "/10", "/100", "/1000") -> 150.0
            signal == "Battery" && scale in listOf("/100", "/1000") -> 180.0
            signal == "AFR" && scale in listOf("/100", "/1000") -> 180.0
            signal == "Coolant" && (scale.contains("-40") || scale.contains("-50")) -> 120.0
            else -> 0.0
        }

        val sameIdBoost = if (g.id == 0x1E0057BD) 250.0 else 0.0
        val confidence = activity + widthPriority + scalePriority + sameIdBoost

        out.add(
            ProfilerSignal(
                signal = signal,
                canId = g.id,
                isExtended = g.isExtended,
                expression = expr,
                scale = scale,
                value = cur,
                confidence = confidence,
                minValue = minValue,
                maxValue = maxValue,
                note = "range=${"%.1f".format(range)} samples=${g.frames.size}"
            )
        )
    }

    private fun changeScore(values: List<Double>): Double {
        var score = 0.0
        var last = values.first()
        values.drop(1).forEach {
            score += min(25.0, abs(it - last))
            last = it
        }
        return score
    }

    private fun expressions(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0..7) { out.add("U8[$i]"); out.add("S8[$i]") }
        for (i in 0..6) { out.add("U16LE[$i]"); out.add("U16BE[$i]"); out.add("S16LE[$i]"); out.add("S16BE[$i]") }
        for (i in 0..5) { out.add("U24LE[$i]"); out.add("U24BE[$i]") }
        return out
    }

    private fun scales(): List<String> = listOf(
        "x1", "/2", "/4", "/8", "/10", "/16", "/32", "/64", "/100", "/128", "/256", "/512", "/1000",
        "pct255", "-40", "-50", "/10-40", "/10-50", "/100-40", "/100-50"
    )

    private fun applyScale(raw: Double, scale: String): Double {
        return when (scale) {
            "x1" -> raw
            "/2" -> raw / 2.0
            "/4" -> raw / 4.0
            "/8" -> raw / 8.0
            "/10" -> raw / 10.0
            "/16" -> raw / 16.0
            "/32" -> raw / 32.0
            "/64" -> raw / 64.0
            "/100" -> raw / 100.0
            "/128" -> raw / 128.0
            "/256" -> raw / 256.0
            "/512" -> raw / 512.0
            "/1000" -> raw / 1000.0
            "pct255" -> raw * 100.0 / 255.0
            "-40" -> raw - 40.0
            "-50" -> raw - 50.0
            "/10-40" -> raw / 10.0 - 40.0
            "/10-50" -> raw / 10.0 - 50.0
            "/100-40" -> raw / 100.0 - 40.0
            "/100-50" -> raw / 100.0 - 50.0
            else -> raw
        }
    }

    private fun raw(expr: String, data: ByteArray): Double? {
        val i = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
        if (i !in 0..7) return null
        fun u8(ix: Int) = data[ix].toInt() and 0xFF
        fun s8(ix: Int) = data[ix].toInt()
        fun u16le(ix: Int) = u8(ix) or (u8(ix + 1) shl 8)
        fun u16be(ix: Int) = (u8(ix) shl 8) or u8(ix + 1)
        fun s16(v: Int) = if (v >= 32768) v - 65536 else v
        fun u24le(ix: Int) = u8(ix) or (u8(ix + 1) shl 8) or (u8(ix + 2) shl 16)
        fun u24be(ix: Int) = (u8(ix) shl 16) or (u8(ix + 1) shl 8) or u8(ix + 2)
        return try {
            when {
                expr.startsWith("U8") -> u8(i).toDouble()
                expr.startsWith("S8") -> s8(i).toDouble()
                expr.startsWith("U16LE") -> u16le(i).toDouble()
                expr.startsWith("U16BE") -> u16be(i).toDouble()
                expr.startsWith("S16LE") -> s16(u16le(i)).toDouble()
                expr.startsWith("S16BE") -> s16(u16be(i)).toDouble()
                expr.startsWith("U24LE") -> u24le(i).toDouble()
                expr.startsWith("U24BE") -> u24be(i).toDouble()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
