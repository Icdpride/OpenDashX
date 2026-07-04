package com.example.opendashx.services

import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.ReverseEngineeringCandidate
import com.example.opendashx.models.ReverseEngineeringReport
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class HolleyReverseEngineeringAnalyzer {
    private data class Layout(val expression: String, val read: (ByteArray) -> Double?)
    private data class PairSample(val raw: Double, val truth: Double)
    private data class FormulaOption(val name: String, val scale: Double, val offset: Double)

    fun analyze(
        frames: List<CanLogFrame>,
        snapshots: List<GroundTruthSnapshot>,
        topPerSignal: Int = 12
    ): ReverseEngineeringReport {
        if (frames.isEmpty() || snapshots.size < 2) {
            return ReverseEngineeringReport(frames.size, snapshots.size, emptyMap())
        }

        val sampledFrames = if (frames.size > 80000) {
            val step = (frames.size / 80000).coerceAtLeast(1)
            frames.filterIndexed { index, _ -> index % step == 0 }
        } else {
            frames
        }

        val byId = sampledFrames.groupBy { "${it.id}:${it.isExtended}" }

        val signals = linkedMapOf<String, (GroundTruthSnapshot) -> Double?>(
            "RPM" to { it.rpm },
            "TPS" to { it.tps },
            "MAP" to { it.mapKpa },
            "Coolant" to { it.coolantF },
            "AFR" to { it.afr },
            "Battery" to { it.batteryV }
        )

        val result = linkedMapOf<String, List<ReverseEngineeringCandidate>>()

        for ((signal, truthGetter) in signals) {
            val truths = snapshots.mapNotNull { snap ->
                val v = truthGetter(snap)
                if (v == null || v.isNaN() || v.isInfinite()) null else snap to v
            }
            if (truths.size < 2) continue

            val candidates = mutableListOf<ReverseEngineeringCandidate>()
            val layouts = buildLayouts(8)
            val formulas = formulaOptionsFor(signal)

            for ((_, group) in byId) {
                if (group.size < 8) continue

                val id = group.first().id
                val ext = group.first().isExtended

                for (layout in layouts) {
                    val samples = mutableListOf<PairSample>()

                    for ((snap, truth) in truths) {
                        val frame = nearestFrame(group, snap.timestampMs) ?: continue
                        val raw = layout.read(frame.data) ?: continue
                        if (raw.isFinite()) samples.add(PairSample(raw, truth))
                    }

                    if (samples.size < 2) continue

                    val rawRange = samples.maxOf { it.raw } - samples.minOf { it.raw }
                    val valueRange = samples.maxOf { it.truth } - samples.minOf { it.truth }

                    if (rawRange <= 0.000001 || valueRange <= 0.000001) continue

                    val corr = abs(correlation(samples.map { it.raw }, samples.map { it.truth }))
                    if (corr < 0.55) continue

                    for (formula in formulas) {
                        val predictions = samples.map { it.raw * formula.scale + formula.offset }
                        val errors = samples.zip(predictions).map { abs(it.first.truth - it.second) }
                        val rmse = sqrt(samples.zip(predictions).map { (it.first.truth - it.second).pow(2) }.average())
                        val mae = errors.average()

                        val candidate = ReverseEngineeringCandidate(
                            signal = signal,
                            id = id,
                            isExtended = ext,
                            expression = "${layout.expression}:${formula.name}",
                            scale = formula.scale,
                            offset = formula.offset,
                            rmse = rmse,
                            mae = mae,
                            correlation = corr,
                            confidence = discreteScore(signal, corr, rmse, mae, valueRange, samples.size, formula),
                            samples = samples.size,
                            minRaw = samples.minOf { it.raw },
                            maxRaw = samples.maxOf { it.raw },
                            minValue = samples.minOf { it.truth },
                            maxValue = samples.maxOf { it.truth }
                        )

                        if (saneDiscreteCandidate(candidate)) candidates.add(candidate)
                    }
                }
            }

            result[signal] = candidates
                .sortedWith(
                    compareBy<ReverseEngineeringCandidate> { it.rmse }
                        .thenBy { it.mae }
                        .thenByDescending { it.correlation }
                        .thenByDescending { it.confidence }
                )
                .take(topPerSignal)
        }

        return ReverseEngineeringReport(frames.size, snapshots.size, result)
    }

    fun exportBestJson(report: ReverseEngineeringReport): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"source\": \"OpenDashX Sprint 36 Discrete Scale/Offset Search\",")
        lines.add("  \"signals\": {")
        val best = report.candidatesBySignal.mapNotNull { (signal, list) -> list.firstOrNull()?.let { signal to it } }
        best.forEachIndexed { index, (signal, c) ->
            val comma = if (index == best.lastIndex) "" else ","
            val exprOnly = c.expression.substringBefore(":")
            val formulaName = c.expression.substringAfter(":", "")
            lines.add("    \"$signal\": {")
            lines.add("      \"id\": \"${c.idHex()}\",")
            lines.add("      \"extended\": ${c.isExtended},")
            lines.add("      \"expression\": \"$exprOnly\",")
            lines.add("      \"formulaName\": \"$formulaName\",")
            lines.add("      \"scale\": ${"%.9f".format(c.scale)},")
            lines.add("      \"offset\": ${"%.9f".format(c.offset)},")
            lines.add("      \"rmse\": ${"%.6f".format(c.rmse)},")
            lines.add("      \"mae\": ${"%.6f".format(c.mae)},")
            lines.add("      \"correlation\": ${"%.6f".format(c.correlation)},")
            lines.add("      \"confidence\": ${"%.3f".format(c.confidence)},")
            lines.add("      \"samples\": ${c.samples}")
            lines.add("    }$comma")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun formulaOptionsFor(signal: String): List<FormulaOption> {
        val scales = when (signal.lowercase()) {
            "rpm" -> listOf(1.0, 0.5, 0.25, 0.2, 0.125, 0.1, 0.0625, 0.05, 0.03125, 0.025, 0.02, 0.015625, 0.01, 0.0078125, 0.00390625)
            "afr", "battery" -> listOf(1.0, 0.5, 0.25, 0.2, 0.125, 0.1, 0.0625, 0.05, 0.02, 0.01, 0.001)
            else -> listOf(1.0, 0.5, 0.25, 0.2, 0.125, 0.1, 0.0625, 0.05, 0.03125, 0.02, 0.01, 0.001)
        }

        val offsets = when (signal.lowercase()) {
            "coolant" -> listOf(0.0, -40.0, 32.0, 100.0, 128.0, -273.15)
            "map" -> listOf(0.0, -100.0, 100.0, 101.3)
            "tps" -> listOf(0.0, 10.0, 12.5, -10.0)
            "afr" -> listOf(0.0, 10.0, 11.0, 11.5, 12.0)
            "battery" -> listOf(0.0, 10.0, 11.0, 12.0)
            "rpm" -> listOf(0.0)
            else -> listOf(0.0)
        }

        val out = mutableListOf<FormulaOption>()
        for (scale in scales) {
            for (offset in offsets) {
                val sName = scaleName(scale)
                val oName = if (offset == 0.0) "" else if (offset > 0) "+${trim(offset)}" else trim(offset)
                out.add(FormulaOption("$sName$oName", scale, offset))
            }
        }
        return out
    }

    private fun trim(v: Double): String {
        return if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)
    }

    private fun scaleName(scale: Double): String = when (scale) {
        1.0 -> "x1"
        0.5 -> "/2"
        0.25 -> "/4"
        0.2 -> "/5"
        0.125 -> "/8"
        0.1 -> "/10"
        0.0625 -> "/16"
        0.05 -> "x0.05"
        0.03125 -> "/32"
        0.025 -> "/40"
        0.02 -> "x0.02"
        0.015625 -> "/64"
        0.01 -> "/100"
        0.0078125 -> "/128"
        0.00390625 -> "/256"
        0.001 -> "/1000"
        else -> "x$scale"
    }

    private fun nearestFrame(group: List<CanLogFrame>, timestampMs: Long): CanLogFrame? {
        if (group.isEmpty()) return null
        var best: CanLogFrame? = null
        var bestDelta = Long.MAX_VALUE

        for (f in group) {
            val d = abs(f.timestampMs - timestampMs)
            if (d < bestDelta) {
                bestDelta = d
                best = f
            } else if (best != null && f.timestampMs > timestampMs && d > bestDelta + 250L) {
                break
            }
        }

        return best
    }

    private fun buildLayouts(maxDlc: Int): List<Layout> {
        val out = mutableListOf<Layout>()

        fun b(data: ByteArray, i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0
        fun s8(data: ByteArray, i: Int): Int = b(data, i).toByte().toInt()
        fun u16le(data: ByteArray, i: Int): Int = b(data, i) or (b(data, i + 1) shl 8)
        fun u16be(data: ByteArray, i: Int): Int = (b(data, i) shl 8) or b(data, i + 1)
        fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v
        fun u24le(data: ByteArray, i: Int): Int = b(data, i) or (b(data, i + 1) shl 8) or (b(data, i + 2) shl 16)
        fun u24be(data: ByteArray, i: Int): Int = (b(data, i) shl 16) or (b(data, i + 1) shl 8) or b(data, i + 2)
        fun u32le(data: ByteArray, i: Int): Double = (b(data, i).toLong() or (b(data, i + 1).toLong() shl 8) or (b(data, i + 2).toLong() shl 16) or (b(data, i + 3).toLong() shl 24)).toDouble()
        fun u32be(data: ByteArray, i: Int): Double = ((b(data, i).toLong() shl 24) or (b(data, i + 1).toLong() shl 16) or (b(data, i + 2).toLong() shl 8) or b(data, i + 3).toLong()).toDouble()

        for (i in 0 until maxDlc) {
            out.add(Layout("U8[$i]") { d -> b(d, i).toDouble() })
            out.add(Layout("S8[$i]") { d -> s8(d, i).toDouble() })
        }

        for (i in 0 until maxDlc - 1) {
            out.add(Layout("U16LE[$i]") { d -> u16le(d, i).toDouble() })
            out.add(Layout("U16BE[$i]") { d -> u16be(d, i).toDouble() })
            out.add(Layout("S16LE[$i]") { d -> s16(u16le(d, i)).toDouble() })
            out.add(Layout("S16BE[$i]") { d -> s16(u16be(d, i)).toDouble() })
        }

        for (i in 0 until maxDlc - 2) {
            out.add(Layout("U24LE[$i]") { d -> u24le(d, i).toDouble() })
            out.add(Layout("U24BE[$i]") { d -> u24be(d, i).toDouble() })
        }

        for (i in 0 until maxDlc - 3) {
            out.add(Layout("U32LE[$i]") { d -> u32le(d, i) })
            out.add(Layout("U32BE[$i]") { d -> u32be(d, i) })
        }

        return out
    }

    private fun correlation(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size != ys.size || xs.size < 2) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0

        for (i in xs.indices) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }

        val denom = sqrt(dx * dy)
        return if (denom <= 0.000001) 0.0 else num / denom
    }

    private fun discreteScore(signal: String, corr: Double, rmse: Double, mae: Double, range: Double, samples: Int, formula: FormulaOption): Double {
        val normalizedError = rmse / range.coerceAtLeast(0.0001)
        val sampleBonus = samples.coerceAtMost(20) / 20.0
        val offsetPenalty = if (formula.offset == 0.0) 0.0 else abs(formula.offset) * offsetPenaltyWeight(signal)
        return ((corr * 1000.0) - (normalizedError * 900.0) - (mae * errorWeight(signal)) + (sampleBonus * 50.0) - offsetPenalty).coerceAtLeast(0.0)
    }

    private fun errorWeight(signal: String): Double = when (signal.lowercase()) {
        "rpm" -> 0.04
        "tps" -> 3.0
        "map" -> 0.7
        "coolant" -> 0.7
        "afr" -> 10.0
        "battery" -> 15.0
        else -> 1.0
    }

    private fun offsetPenaltyWeight(signal: String): Double = when (signal.lowercase()) {
        "rpm" -> 4.0
        "tps" -> 0.5
        "map" -> 0.2
        "coolant" -> 0.05
        "afr" -> 0.2
        "battery" -> 0.4
        else -> 0.2
    }

    private fun saneDiscreteCandidate(c: ReverseEngineeringCandidate): Boolean {
        if (c.samples < 2) return false
        if (c.correlation < 0.55) return false
        return when (c.signal.lowercase()) {
            "rpm" -> c.rmse < 350.0
            "tps" -> c.rmse < 6.0
            "map" -> c.rmse < 35.0
            "coolant" -> c.rmse < 10.0
            "afr" -> c.rmse < 2.0
            "battery" -> c.rmse < 1.5
            else -> true
        }
    }
}
