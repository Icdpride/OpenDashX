package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.CorrelationResult
import kotlin.math.abs
import kotlin.math.min

class CorrelationEngine {
    fun analyze(frames: List<CanLogFrame>): List<CorrelationResult> {
        if (frames.size < 100) return emptyList()

        val groups = frames
            .groupBy { "${it.id}:${it.isExtended}" }
            .values
            .filter { it.size >= 50 }
            .sortedByDescending { it.size }
            .take(20)

        val results = mutableListOf<CorrelationResult>()

        for (groupRaw in groups) {
            val group = downsample(groupRaw, 2500)
            val id = group.first().id
            val ext = group.first().isExtended

            for (expr in expressions()) {
                val rawValues = group.mapNotNull { raw(expr, it.data) }
                if (rawValues.size < 50) continue

                val rawMin = rawValues.minOrNull() ?: continue
                val rawMax = rawValues.maxOrNull() ?: continue
                if (rawMax == rawMin) continue

                for (scale in scales()) {
                    val values = rawValues.map { applyScale(it, scale) }
                    val cur = values.last()
                    val lo = values.minOrNull() ?: cur
                    val hi = values.maxOrNull() ?: cur
                    val activity = activityScore(values)

                    maybeAdd(results, "RPM", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                    maybeAdd(results, "TPS", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                    maybeAdd(results, "MAP", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                    maybeAdd(results, "Battery", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                    maybeAdd(results, "AFR", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                    maybeAdd(results, "Coolant", id, ext, expr, scale, cur, lo, hi, activity, values.size)
                }
            }
        }

        return results.sortedWith(
            compareByDescending<CorrelationResult> { it.confidence }
                .thenBy { it.signal }
                .thenBy { it.id }
        )
    }

    fun bestBySignal(frames: List<CanLogFrame>): List<CorrelationResult> {
        return analyze(frames)
            .groupBy { it.signal }
            .mapNotNull { it.value.maxByOrNull { r -> r.confidence } }
            .sortedBy { it.signal }
    }

    private fun downsample(input: List<CanLogFrame>, maxSamples: Int): List<CanLogFrame> {
        if (input.size <= maxSamples) return input
        val step = input.size.toDouble() / maxSamples.toDouble()
        val out = ArrayList<CanLogFrame>(maxSamples)
        var idx = 0.0
        while (idx < input.size && out.size < maxSamples) {
            out.add(input[idx.toInt()])
            idx += step
        }
        return out
    }

    private fun maybeAdd(
        out: MutableList<CorrelationResult>,
        signal: String,
        id: Int,
        ext: Boolean,
        expr: String,
        scale: String,
        cur: Double,
        lo: Double,
        hi: Double,
        activity: Double,
        samples: Int
    ) {
        val range = hi - lo
        val valid = when (signal) {
            "RPM" -> cur in 400.0..8500.0 && hi in 750.0..9000.0 && range > 250.0
            "TPS" -> cur in 0.0..100.0 && lo >= -3.0 && hi <= 110.0 && range > 5.0
            "MAP" -> cur in 15.0..320.0 && hi <= 350.0 && range > 2.0
            "Battery" -> cur in 9.0..19.0 && lo >= 7.0 && hi <= 21.0 && range < 8.0
            "AFR" -> cur in 7.0..23.5 && lo >= 5.0 && hi <= 26.0
            "Coolant" -> cur in 30.0..280.0 && lo >= -50.0 && hi <= 320.0
            else -> false
        }
        if (!valid) return

        val idBoost = if (id == 0x1E0057BD) 180.0 else 0.0
        val exprBoost = when {
            signal == "RPM" && expr.startsWith("U16") -> 220.0
            signal == "RPM" && expr.startsWith("U24") -> -300.0
            signal == "TPS" && expr.startsWith("U8") -> 160.0
            signal in listOf("Battery", "AFR", "Coolant") && !expr.startsWith("U24") -> 90.0
            expr.startsWith("U24") -> -400.0
            else -> 0.0
        }
        val scaleBoost = when {
            signal == "RPM" && scale in listOf("/4", "/8", "/10", "/16") -> 160.0
            signal == "TPS" && scale in listOf("pct255", "/10", "/100", "/1000") -> 130.0
            signal == "Battery" && scale in listOf("/100", "/1000") -> 160.0
            signal == "AFR" && scale in listOf("/100", "/1000") -> 160.0
            signal == "Coolant" && scale.contains("-") -> 110.0
            else -> 0.0
        }

        val confidence = activity + idBoost + exprBoost + scaleBoost
        if (confidence < 35.0) return

        out.add(
            CorrelationResult(
                signal = signal,
                id = id,
                isExtended = ext,
                expression = expr,
                scale = scale,
                currentValue = cur,
                minValue = lo,
                maxValue = hi,
                confidence = confidence,
                activity = activity,
                sampleCount = samples
            )
        )
    }

    private fun activityScore(values: List<Double>): Double {
        var score = 0.0
        var last = values.first()
        for (v in values.drop(1)) {
            score += min(25.0, abs(v - last))
            last = v
        }
        val range = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        return score + range
    }

    private fun expressions(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0..7) { out.add("U8[$i]"); out.add("S8[$i]") }
        for (i in 0..6) { out.add("U16LE[$i]"); out.add("U16BE[$i]"); out.add("S16LE[$i]"); out.add("S16BE[$i]") }
        // 24-bit disabled in first pass for speed; can be re-enabled later.
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
        fun u8(ix: Int) = data[ix].toInt() and 0xFF
        fun s8(ix: Int) = data[ix].toInt()
        fun u16le(ix: Int) = u8(ix) or (u8(ix + 1) shl 8)
        fun u16be(ix: Int) = (u8(ix) shl 8) or u8(ix + 1)
        fun s16(v: Int) = if (v >= 32768) v - 65536 else v
        return try {
            when {
                expr.startsWith("U8") -> u8(i).toDouble()
                expr.startsWith("S8") -> s8(i).toDouble()
                expr.startsWith("U16LE") -> u16le(i).toDouble()
                expr.startsWith("U16BE") -> u16be(i).toDouble()
                expr.startsWith("S16LE") -> s16(u16le(i)).toDouble()
                expr.startsWith("S16BE") -> s16(u16be(i)).toDouble()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
