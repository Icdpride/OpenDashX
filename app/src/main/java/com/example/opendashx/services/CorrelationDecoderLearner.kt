package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.LearnedDecoder
import kotlin.math.abs
import kotlin.math.sqrt

class CorrelationDecoderLearner {

    data class Candidate(
        val id: Int,
        val isExtended: Boolean,
        val expression: String,
        val values: List<Double>
    )

    fun learn(
        frames: List<CanLogFrame>,
        snapshots: List<GroundTruthSnapshot>
    ): List<LearnedDecoder> {
        if (frames.isEmpty() || snapshots.size < 2) return emptyList()

        val signals = linkedMapOf(
            "RPM" to snapshots.map { (it.rpm ?: 0.0).toDouble() },
            "TPS" to snapshots.map { (it.tps ?: 0.0).toDouble() },
            "MAP" to snapshots.map { (it.mapKpa ?: 0.0).toDouble() },
            "Coolant" to snapshots.map { (it.coolantF ?: 0.0).toDouble() },
            "AFR" to snapshots.map { (it.afr ?: 0.0).toDouble() },
            "Battery" to snapshots.map { (it.batteryV ?: 0.0).toDouble() }
        )

        val byId = frames.groupBy { "${it.id}:${it.isExtended}" }
        val candidates = mutableListOf<Candidate>()

        for (group in byId.values) {
            if (group.size < 8) continue

            val id = group.first().id
            val ext = group.first().isExtended
            val sampled = sampleFrames(group, snapshots.size)

            for (offset in 0 until 8) {
                candidates.add(Candidate(id, ext, "U8[$offset]", sampled.map { u8(it, offset).toDouble() }))
                candidates.add(Candidate(id, ext, "S8[$offset]", sampled.map { s8(it, offset).toDouble() }))
            }

            for (offset in 0 until 7) {
                candidates.add(Candidate(id, ext, "U16LE[$offset]", sampled.map { u16le(it, offset).toDouble() }))
                candidates.add(Candidate(id, ext, "U16BE[$offset]", sampled.map { u16be(it, offset).toDouble() }))
                candidates.add(Candidate(id, ext, "S16LE[$offset]", sampled.map { s16le(it, offset).toDouble() }))
                candidates.add(Candidate(id, ext, "S16BE[$offset]", sampled.map { s16be(it, offset).toDouble() }))
            }

            for (offset in 0 until 6) {
                candidates.add(Candidate(id, ext, "U24LE[$offset]", sampled.map { u24le(it, offset).toDouble() }))
                candidates.add(Candidate(id, ext, "U24BE[$offset]", sampled.map { u24be(it, offset).toDouble() }))
            }

            for (offset in 0 until 5) {
                candidates.add(Candidate(id, ext, "U32LE[$offset]", sampled.map { u32le(it, offset) }))
                candidates.add(Candidate(id, ext, "U32BE[$offset]", sampled.map { u32be(it, offset) }))
            }
        }

        val learned = mutableListOf<LearnedDecoder>()

        for ((signal, truth) in signals) {
            val truthRange = maxOfList(truth) - minOfList(truth)
            if (truthRange <= 0.0001) continue

            val best = candidates
                .mapNotNull { score(signal, truth, it) }
                .filter { saneSignal(signal, it) }
                .maxByOrNull { it.confidence }

            if (best != null) learned.add(best)
        }

        return learned.sortedByDescending { it.confidence }
    }

    fun exportJson(decoders: List<LearnedDecoder>): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"source\": \"OpenDash X Sprint 26 Correlation Learner\",")
        lines.add("  \"signals\": {")
        decoders.forEachIndexed { index, d ->
            val comma = if (index == decoders.lastIndex) "" else ","
            lines.add("    \"${d.signal}\": {")
            lines.add("      \"id\": \"${d.idHex()}\",")
            lines.add("      \"extended\": ${d.isExtended},")
            lines.add("      \"expression\": \"${d.expression}\",")
            lines.add("      \"formula\": \"${d.formula}\",")
            lines.add("      \"confidence\": ${"%.3f".format(d.confidence)},")
            lines.add("      \"error\": ${"%.3f".format(d.error)}")
            lines.add("    }$comma")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun score(signal: String, truth: List<Double>, candidate: Candidate): LearnedDecoder? {
        val raw = candidate.values
        if (raw.size != truth.size) return null

        val rawRange = maxOfList(raw) - minOfList(raw)
        if (rawRange <= 0.0001) return null

        val corr = abs(correlation(raw, truth))
        if (corr < 0.70) return null

        val fit = linearFit(raw, truth) ?: return null
        val predicted = raw.map { it * fit.first + fit.second }

        val rmse = sqrt(predicted.zip(truth).sumOf { (p, t) -> (p - t) * (p - t) } / truth.size.toDouble())
        val truthRange = maxOf(0.001, maxOfList(truth) - minOfList(truth))
        val normalizedError = rmse / truthRange

        val confidence = (corr * 1000.0) - (normalizedError * 250.0) + rawRange.coerceAtMost(10000.0) / 500.0
        val formula = "value = raw * ${"%.8f".format(fit.first)} + ${"%.8f".format(fit.second)}"

        return LearnedDecoder(
            signal = signal,
            confidence = confidence,
            id = candidate.id,
            isExtended = candidate.isExtended,
            expression = candidate.expression,
            formula = formula,
            scale = fit.first,
            offset = fit.second,
            min = minOfList(predicted),
            max = maxOfList(predicted),
            sampleCount = truth.size,
            error = rmse,
            latestValue = predicted.lastOrNull()
        )
    }

    private fun saneSignal(signal: String, d: LearnedDecoder): Boolean {
        val v = d.latestValue ?: return false
        return when (signal) {
            "RPM" -> v in 0.0..9000.0
            "TPS" -> v in -5.0..105.0
            "MAP" -> v in 0.0..350.0
            "Coolant" -> v in -40.0..300.0
            "AFR" -> v in 5.0..25.0
            "Battery" -> v in 5.0..20.0
            else -> true
        }
    }

    private fun sampleFrames(frames: List<CanLogFrame>, count: Int): List<CanLogFrame> {
        if (frames.isEmpty()) return emptyList()
        if (count <= 1) return listOf(frames.last())
        return (0 until count).map { i ->
            val index = ((frames.lastIndex.toDouble() * i.toDouble()) / (count - 1).toDouble()).toInt().coerceIn(0, frames.lastIndex)
            frames[index]
        }
    }

    private fun linearFit(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val n = x.size
        val meanX = x.average()
        val meanY = y.average()
        var num = 0.0
        var den = 0.0

        for (i in 0 until n) {
            val dx = x[i] - meanX
            num += dx * (y[i] - meanY)
            den += dx * dx
        }

        if (abs(den) < 0.0000001) return null

        val slope = num / den
        val intercept = meanY - slope * meanX
        return slope to intercept
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
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
        return if (den <= 0.0000001) 0.0 else num / den
    }

    private fun maxOfList(values: List<Double>): Double {
        var result = Double.NEGATIVE_INFINITY
        for (v in values) if (v > result) result = v
        return result
    }

    private fun minOfList(values: List<Double>): Double {
        var result = Double.POSITIVE_INFINITY
        for (v in values) if (v < result) result = v
        return result
    }

    private fun byte(frame: CanLogFrame, index: Int): Int =
        if (index in frame.data.indices) frame.data[index].toInt() and 0xFF else 0

    private fun u8(f: CanLogFrame, o: Int): Int = byte(f, o)
    private fun s8(f: CanLogFrame, o: Int): Int = byte(f, o).toByte().toInt()
    private fun u16le(f: CanLogFrame, o: Int): Int = byte(f, o) or (byte(f, o + 1) shl 8)
    private fun u16be(f: CanLogFrame, o: Int): Int = (byte(f, o) shl 8) or byte(f, o + 1)
    private fun s16le(f: CanLogFrame, o: Int): Int = u16le(f, o).toShort().toInt()
    private fun s16be(f: CanLogFrame, o: Int): Int = u16be(f, o).toShort().toInt()
    private fun u24le(f: CanLogFrame, o: Int): Int = byte(f, o) or (byte(f, o + 1) shl 8) or (byte(f, o + 2) shl 16)
    private fun u24be(f: CanLogFrame, o: Int): Int = (byte(f, o) shl 16) or (byte(f, o + 1) shl 8) or byte(f, o + 2)
    private fun u32le(f: CanLogFrame, o: Int): Double =
        (byte(f, o).toLong()
            or (byte(f, o + 1).toLong() shl 8)
            or (byte(f, o + 2).toLong() shl 16)
            or (byte(f, o + 3).toLong() shl 24)).toDouble()

    private fun u32be(f: CanLogFrame, o: Int): Double =
        ((byte(f, o).toLong() shl 24)
            or (byte(f, o + 1).toLong() shl 16)
            or (byte(f, o + 2).toLong() shl 8)
            or byte(f, o + 3).toLong()).toDouble()
}
