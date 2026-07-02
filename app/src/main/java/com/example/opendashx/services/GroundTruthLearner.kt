package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.LearnedSignalDefinition
import kotlin.math.abs
import kotlin.math.sqrt

class GroundTruthLearner {
    fun learn(
        frames: List<CanLogFrame>,
        snapshots: List<GroundTruthSnapshot>,
        windowMs: Long = 350L
    ): List<LearnedSignalDefinition> {
        if (frames.size < 100 || snapshots.isEmpty()) return emptyList()

        val targets = listOf(
            "RPM" to snapshots.mapNotNull { s -> s.rpm?.let { s.timestampMs to it } },
            "TPS" to snapshots.mapNotNull { s -> s.tps?.let { s.timestampMs to it } },
            "MAP" to snapshots.mapNotNull { s -> s.mapKpa?.let { s.timestampMs to it } },
            "Coolant" to snapshots.mapNotNull { s -> s.coolantF?.let { s.timestampMs to it } },
            "AFR" to snapshots.mapNotNull { s -> s.afr?.let { s.timestampMs to it } },
            "Battery" to snapshots.mapNotNull { s -> s.batteryV?.let { s.timestampMs to it } }
        )

        val groups = frames
            .groupBy { "${it.id}:${it.isExtended}" }
            .values
            .filter { it.size >= 10 }
            .sortedByDescending { it.size }
            .take(60)

        val learned = mutableListOf<LearnedSignalDefinition>()

        for ((signal, points) in targets) {
            if (points.size < 2) continue
            if (points.map { it.first }.distinct().size < 2) continue

            val candidates = mutableListOf<LearnedSignalDefinition>()

            for (group in groups) {
                val id = group.first().id
                val ext = group.first().isExtended

                for (expr in expressions()) {
                    val xVals = mutableListOf<Double>()
                    val yVals = mutableListOf<Double>()

                    for ((ts, truth) in points) {
                        val nearby = group
                            .asSequence()
                            .filter { abs(it.timestampMs - ts) <= windowMs }
                            .mapNotNull { raw(expr, it.data) }
                            .toList()

                        if (nearby.isEmpty()) continue
                        xVals.add(nearby.average())
                        yVals.add(truth)
                    }

                    if (xVals.size < 2) continue
                    if (((xVals.maxOrNull() ?: 0.0) - (xVals.minOrNull() ?: 0.0)) == 0.0) continue

                    val fit = linearFit(xVals, yVals) ?: continue
                    val predicted = xVals.map { fit.first * it + fit.second }
                    val err = rmse(predicted, yVals)

                    if (!sane(signal, fit.first, fit.second, predicted)) continue

                    val confidence = confidence(signal, expr, id, err, xVals, yVals, points.size)
                    candidates.add(
                        LearnedSignalDefinition(
                            signal = signal,
                            id = id,
                            isExtended = ext,
                            expression = expr,
                            scale = fit.first,
                            offset = fit.second,
                            error = err,
                            confidence = confidence,
                            samples = xVals.size
                        )
                    )
                }
            }

            candidates.maxByOrNull { it.confidence }?.let { learned.add(it) }
        }

        return learned.sortedBy { it.signal }
    }

    fun exportProfile(defs: List<LearnedSignalDefinition>): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"source\": \"OpenDash X Interactive Replay Studio\",")
        lines.add("  \"signals\": {")
        defs.forEachIndexed { index, d ->
            lines.add("    \"${d.signal}\": {")
            lines.add("      \"id\": \"${d.idHex()}\",")
            lines.add("      \"extended\": ${d.isExtended},")
            lines.add("      \"expression\": \"${d.expression}\",")
            lines.add("      \"scale\": ${"%.9f".format(d.scale)},")
            lines.add("      \"offset\": ${"%.9f".format(d.offset)},")
            lines.add("      \"rmse\": ${"%.6f".format(d.error)},")
            lines.add("      \"confidence\": ${"%.3f".format(d.confidence)}")
            lines.add("    }${if (index == defs.lastIndex) "" else ","}")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun confidence(signal: String, expr: String, id: Int, err: Double, x: List<Double>, y: List<Double>, total: Int): Double {
        val yRange = ((y.maxOrNull() ?: 0.0) - (y.minOrNull() ?: 0.0)).coerceAtLeast(1.0)
        val coverage = x.size.toDouble() / total.toDouble().coerceAtLeast(1.0)
        var score = (1000.0 / (1.0 + err)) + (coverage * 500.0) + (yRange * 5.0)
        if (id == 0x1E0057BD) score += 250.0
        if (signal == "RPM" && expr.startsWith("U16")) score += 160.0
        if (signal == "RPM" && expr.startsWith("S16")) score += 100.0
        if (signal == "TPS" && expr.startsWith("U8")) score += 140.0
        if (signal in listOf("AFR", "Battery", "Coolant", "MAP") && expr.startsWith("U16")) score += 100.0
        if (signal in listOf("AFR", "Battery", "Coolant", "MAP") && expr.startsWith("S16")) score += 50.0
        return score
    }

    private fun sane(signal: String, scale: Double, offset: Double, predicted: List<Double>): Boolean {
        if (!scale.isFinite() || !offset.isFinite()) return false
        if (abs(scale) < 0.00000001 || abs(scale) > 100000.0) return false
        val pMin = predicted.minOrNull() ?: return false
        val pMax = predicted.maxOrNull() ?: return false

        return when (signal) {
            "RPM" -> pMin >= 0.0 && pMax <= 9000.0
            "TPS" -> pMin >= -10.0 && pMax <= 110.0
            "MAP" -> pMin >= 0.0 && pMax <= 400.0
            "Coolant" -> pMin >= -60.0 && pMax <= 320.0
            "AFR" -> pMin >= 5.0 && pMax <= 30.0
            "Battery" -> pMin >= 6.0 && pMax <= 22.0
            else -> true
        }
    }

    private fun linearFit(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val meanX = x.average()
        val meanY = y.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            val dx = x[i] - meanX
            num += dx * (y[i] - meanY)
            den += dx * dx
        }
        if (den == 0.0) return null
        val scale = num / den
        val offset = meanY - scale * meanX
        return scale to offset
    }

    private fun rmse(predicted: List<Double>, truth: List<Double>): Double {
        var sum = 0.0
        for (i in predicted.indices) {
            val e = predicted[i] - truth[i]
            sum += e * e
        }
        return sqrt(sum / predicted.size.toDouble())
    }

    private fun expressions(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0..7) { out.add("U8[$i]"); out.add("S8[$i]") }
        for (i in 0..6) {
            out.add("U16LE[$i]")
            out.add("U16BE[$i]")
            out.add("S16LE[$i]")
            out.add("S16BE[$i]")
        }
        return out
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
        } catch (_: Exception) { null }
    }
}
