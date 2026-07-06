package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.LearnedSignalDefinition
import kotlin.math.abs
import kotlin.math.sqrt

class GroundTruthLearner {
    private data class Point(val truth: Double, val frame: CanLogFrame)
    private data class ScoredCandidate(
        val def: LearnedSignalDefinition,
        val corr: Double,
        val normalizedError: Double,
        val rawRange: Double,
        val truthRange: Double
    )

    fun learn(
        frames: List<CanLogFrame>,
        snapshots: List<GroundTruthSnapshot>,
        windowMs: Long = 250L
    ): List<LearnedSignalDefinition> {
        if (frames.size < 100 || snapshots.size < 2) return emptyList()

        val groups = frames
            .groupBy { "${it.id}:${it.isExtended}" }
            .values
            .filter { it.size >= 10 }
            .sortedByDescending { it.size }
            .take(120)
            .map { it.sortedBy { f -> f.timestampMs } }

        val targets = listOf(
            "RPM" to snapshots.mapNotNull { s -> s.rpm?.let { s to it } },
            "TPS" to snapshots.mapNotNull { s -> s.tps?.let { s to it } },
            "MAP" to snapshots.mapNotNull { s -> s.mapKpa?.let { s to it } },
            "Coolant" to snapshots.mapNotNull { s -> s.coolantF?.let { s to it } },
            "AFR" to snapshots.mapNotNull { s -> s.afr?.let { s to it } },
            "Battery" to snapshots.mapNotNull { s -> s.batteryV?.let { s to it } }
        )

        val learned = mutableListOf<LearnedSignalDefinition>()

        for ((signal, targetPoints) in targets) {
            val usefulPoints = targetPoints.filter { (_, v) -> v.isFinite() }
            if (usefulPoints.size < 2) continue
            val truthValues = usefulPoints.map { it.second }
            val truthRange = range(truthValues)
            if (truthRange <= minimumRequiredTruthRange(signal)) continue

            val scored = mutableListOf<ScoredCandidate>()

            for (group in groups) {
                val id = group.first().id
                val ext = group.first().isExtended
                val points = usefulPoints.mapNotNull { (snap, truth) ->
                    val frame = nearestFrame(group, snap.timestampMs, windowMs) ?: return@mapNotNull null
                    Point(truth, frame)
                }
                if (points.size < 2) continue

                for (expr in expressions()) {
                    val rawValues = points.mapNotNull { raw(expr, it.frame.data) }
                    if (rawValues.size != points.size) continue
                    val yVals = points.map { it.truth }
                    val rawRange = range(rawValues)
                    if (rawRange <= 0.000001) continue

                    val fit = linearFit(rawValues, yVals) ?: continue
                    val predicted = rawValues.map { fit.first * it + fit.second }
                    val err = rmse(predicted, yVals)
                    val normalizedError = err / truthRange.coerceAtLeast(1.0)
                    val corr = abs(correlation(rawValues, yVals))

                    if (corr < minimumCorrelation(signal)) continue
                    if (normalizedError > maxNormalizedError(signal)) continue
                    if (!sane(signal, fit.first, fit.second, predicted)) continue

                    val confidence = confidence(
                        signal = signal,
                        expr = expr,
                        id = id,
                        err = err,
                        normalizedError = normalizedError,
                        corr = corr,
                        rawRange = rawRange,
                        truthRange = truthRange,
                        matched = points.size,
                        total = usefulPoints.size
                    )

                    scored.add(
                        ScoredCandidate(
                            def = LearnedSignalDefinition(
                                signal = signal,
                                id = id,
                                isExtended = ext,
                                expression = expr,
                                scale = fit.first,
                                offset = fit.second,
                                error = err,
                                confidence = confidence,
                                samples = points.size
                            ),
                            corr = corr,
                            normalizedError = normalizedError,
                            rawRange = rawRange,
                            truthRange = truthRange
                        )
                    )
                }
            }

            scored
                .sortedWith(compareByDescending<ScoredCandidate> { it.def.confidence }.thenBy { it.def.error })
                .firstOrNull()
                ?.let { learned.add(it.def) }
        }

        return learned.sortedBy { it.signal }
    }

    fun exportProfile(defs: List<LearnedSignalDefinition>): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"source\": \"OpenDash X Sprint 30 Timestamp-Aligned Learner\",")
        lines.add("  \"signals\": {")
        defs.forEachIndexed { index, d ->
            lines.add("    \"${d.signal}\": {")
            lines.add("      \"id\": \"${d.idHex()}\",")
            lines.add("      \"extended\": ${d.isExtended},")
            lines.add("      \"expression\": \"${d.expression}\",")
            lines.add("      \"scale\": ${"%.9f".format(d.scale)},")
            lines.add("      \"offset\": ${"%.9f".format(d.offset)},")
            lines.add("      \"formula\": \"${d.formula()}\",")
            lines.add("      \"rmse\": ${"%.6f".format(d.error)},")
            lines.add("      \"confidence\": ${"%.3f".format(d.confidence)},")
            lines.add("      \"samples\": ${d.samples}")
            lines.add("    }${if (index == defs.lastIndex) "" else ","}")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun nearestFrame(group: List<CanLogFrame>, ts: Long, windowMs: Long): CanLogFrame? {
        if (group.isEmpty()) return null
        var low = 0
        var high = group.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (group[mid].timestampMs < ts) low = mid + 1 else high = mid
        }
        val candidates = listOf(low - 2, low - 1, low, low + 1, low + 2)
            .filter { it in group.indices }
            .map { group[it] }
        val best = candidates.minByOrNull { abs(it.timestampMs - ts) } ?: return null
        return if (abs(best.timestampMs - ts) <= windowMs) best else null
    }

    private fun confidence(
        signal: String,
        expr: String,
        id: Int,
        err: Double,
        normalizedError: Double,
        corr: Double,
        rawRange: Double,
        truthRange: Double,
        matched: Int,
        total: Int
    ): Double {
        val coverage = matched.toDouble() / total.toDouble().coerceAtLeast(1.0)
        var score = 0.0
        score += corr * 2000.0
        score += coverage * 600.0
        score += 800.0 / (1.0 + err)
        score -= normalizedError * 1200.0
        score += rawRange.coerceAtMost(10000.0) / 50.0
        score += truthRange.coerceAtMost(5000.0) / 25.0

        // Holley data seen during real-vehicle testing commonly packs key live values in this frame.
        if (id == 0x1E0057BD) score += 150.0

        when (signal) {
            "RPM" -> {
                if (expr.startsWith("U16")) score += 120.0
                if (expr.startsWith("S16")) score += 80.0
                if (expr.startsWith("U8") || expr.startsWith("S8")) score -= 300.0
            }
            "TPS" -> {
                if (expr.startsWith("U8")) score += 120.0
                if (expr.startsWith("S8")) score += 40.0
            }
            "AFR", "Battery", "Coolant", "MAP" -> {
                if (expr.startsWith("U16") || expr.startsWith("S16")) score += 80.0
            }
        }
        return score
    }

    private fun minimumRequiredTruthRange(signal: String): Double = when (signal) {
        "RPM" -> 150.0
        "TPS" -> 2.0
        "MAP" -> 2.0
        "Coolant" -> 0.5
        "AFR" -> 0.2
        "Battery" -> 0.05
        else -> 0.1
    }

    private fun minimumCorrelation(signal: String): Double = when (signal) {
        "Battery", "Coolant" -> 0.55
        else -> 0.70
    }

    private fun maxNormalizedError(signal: String): Double = when (signal) {
        "RPM" -> 0.35
        "TPS" -> 0.45
        "MAP" -> 0.50
        "Coolant" -> 0.50
        "AFR" -> 0.55
        "Battery" -> 0.60
        else -> 0.50
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
        if (x.size != y.size || x.size < 2) return null
        val meanX = x.average()
        val meanY = y.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            val dx = x[i] - meanX
            num += dx * (y[i] - meanY)
            den += dx * dx
        }
        if (abs(den) < 0.000000001) return null
        val scale = num / den
        val offset = meanY - scale * meanX
        return scale to offset
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.size < 2) return 0.0
        val meanA = a.average()
        val meanB = b.average()
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val den = sqrt(denA * denB)
        return if (den <= 0.000000001) 0.0 else num / den
    }

    private fun rmse(predicted: List<Double>, truth: List<Double>): Double {
        if (predicted.size != truth.size || predicted.isEmpty()) return Double.POSITIVE_INFINITY
        var sum = 0.0
        for (i in predicted.indices) {
            val e = predicted[i] - truth[i]
            sum += e * e
        }
        return sqrt(sum / predicted.size.toDouble())
    }

    private fun range(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        return max - min
    }

    private fun expressions(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0..7) {
            out.add("U8[$i]")
            out.add("S8[$i]")
        }
        for (i in 0..6) {
            out.add("U16LE[$i]")
            out.add("U16BE[$i]")
            out.add("S16LE[$i]")
            out.add("S16BE[$i]")
        }
        for (i in 0..5) {
            out.add("U24LE[$i]")
            out.add("U24BE[$i]")
        }
        return out.distinct()
    }

    private fun raw(expr: String, data: ByteArray): Double? {
        val i = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
        fun u8(ix: Int): Int = if (ix in data.indices) data[ix].toInt() and 0xFF else throw IndexOutOfBoundsException()
        fun s8(ix: Int): Int = u8(ix).toByte().toInt()
        fun u16le(ix: Int): Int = u8(ix) or (u8(ix + 1) shl 8)
        fun u16be(ix: Int): Int = (u8(ix) shl 8) or u8(ix + 1)
        fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v
        fun u24le(ix: Int): Int = u8(ix) or (u8(ix + 1) shl 8) or (u8(ix + 2) shl 16)
        fun u24be(ix: Int): Int = (u8(ix) shl 16) or (u8(ix + 1) shl 8) or u8(ix + 2)
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
