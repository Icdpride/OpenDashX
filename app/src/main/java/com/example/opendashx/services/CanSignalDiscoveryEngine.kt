package com.example.opendashx.services

import com.example.opendashx.models.ByteActivity
import com.example.opendashx.models.CanIdDiscovery
import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.CanSignalDiscoveryReport
import com.example.opendashx.models.FieldDiscoveryCandidate
import com.example.opendashx.models.GroundTruthSnapshot
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class CanSignalDiscoveryEngine {
    private data class Layout(val expression: String, val read: (ByteArray) -> Double?)
    private data class PairSample(val raw: Double, val truth: Double)

    fun discover(
        frames: List<CanLogFrame>,
        snapshots: List<GroundTruthSnapshot>,
        topPerSignal: Int = 8,
        topIds: Int = 30
    ): CanSignalDiscoveryReport {
        if (frames.isEmpty()) {
            return CanSignalDiscoveryReport(0, snapshots.size, emptyList(), emptyMap())
        }

        val sampledFrames = if (frames.size > 120000) {
            val step = (frames.size / 120000).coerceAtLeast(1)
            frames.filterIndexed { index, _ -> index % step == 0 }
        } else {
            frames
        }

        val byId = sampledFrames.groupBy { "${it.id}:${it.isExtended}" }
        val signalTruths = linkedMapOf<String, (GroundTruthSnapshot) -> Double?>(
            "RPM" to { it.rpm },
            "TPS" to { it.tps },
            "MAP" to { it.mapKpa },
            "Coolant" to { it.coolantF },
            "AFR" to { it.afr },
            "Battery" to { it.batteryV }
        )

        val allCandidates = mutableListOf<FieldDiscoveryCandidate>()
        val idReports = mutableListOf<CanIdDiscovery>()
        val layouts = buildLayouts(8)

        for ((_, group) in byId) {
            if (group.size < 8) continue

            val id = group.first().id
            val ext = group.first().isExtended
            val activity = computeByteActivity(group)
            val idCandidates = mutableListOf<FieldDiscoveryCandidate>()

            for ((signal, getter) in signalTruths) {
                val truths = snapshots.mapNotNull { snap ->
                    val v = getter(snap)
                    if (v == null || v.isNaN() || v.isInfinite()) null else snap to v
                }
                if (truths.size < 2) continue

                for (layout in layouts) {
                    val samples = mutableListOf<PairSample>()
                    for ((snap, truth) in truths) {
                        val frame = nearestFrame(group, snap.timestampMs) ?: continue
                        val raw = layout.read(frame.data) ?: continue
                        if (raw.isFinite()) samples.add(PairSample(raw, truth))
                    }

                    if (samples.size < 2) continue
                    val rawRange = samples.maxOf { it.raw } - samples.minOf { it.raw }
                    val truthRange = samples.maxOf { it.truth } - samples.minOf { it.truth }
                    if (rawRange <= 0.000001 || truthRange <= 0.000001) continue

                    val corr = correlation(samples.map { it.raw }, samples.map { it.truth })
                    if (abs(corr) < 0.55) continue

                    val fit = linearFit(samples) ?: continue
                    val predictions = samples.map { it.raw * fit.first + fit.second }
                    val errors = samples.zip(predictions).map { abs(it.first.truth - it.second) }
                    val rmse = sqrt(samples.zip(predictions).map { (it.first.truth - it.second).pow(2) }.average())
                    val mae = errors.average()

                    val candidate = FieldDiscoveryCandidate(
                        signal = signal,
                        id = id,
                        isExtended = ext,
                        expression = layout.expression,
                        scale = fit.first,
                        offset = fit.second,
                        rmse = rmse,
                        mae = mae,
                        correlation = corr,
                        samples = samples.size,
                        minRaw = samples.minOf { it.raw },
                        maxRaw = samples.maxOf { it.raw },
                        minTruth = samples.minOf { it.truth },
                        maxTruth = samples.maxOf { it.truth }
                    )

                    if (sane(candidate)) {
                        idCandidates.add(candidate)
                        allCandidates.add(candidate)
                    }
                }
            }

            idReports.add(
                CanIdDiscovery(
                    id = id,
                    isExtended = ext,
                    frameCount = group.size,
                    byteActivity = activity,
                    candidates = idCandidates
                        .sortedWith(compareBy<FieldDiscoveryCandidate> { it.rmse }.thenByDescending { abs(it.correlation) })
                        .take(12)
                )
            )
        }

        val bestBySignal = allCandidates
            .groupBy { it.signal }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy<FieldDiscoveryCandidate> { normalizedError(it) }
                        .thenByDescending { abs(it.correlation) }
                        .thenBy { complexityPenalty(it.expression) }
                ).take(topPerSignal)
            }

        val rankedIds = idReports
            .sortedWith(
                compareByDescending<CanIdDiscovery> { it.candidates.size }
                    .thenByDescending { it.byteActivity.sumOf { b -> b.activityPct } }
                    .thenByDescending { it.frameCount }
            )
            .take(topIds)

        return CanSignalDiscoveryReport(frames.size, snapshots.size, rankedIds, bestBySignal)
    }

    fun formatReport(report: CanSignalDiscoveryReport): String {
        val lines = mutableListOf<String>()
        lines.add("CAN Signal Discovery Engine")
        lines.add("Frames: ${report.totalFrames}   Snapshots: ${report.totalSnapshots}")
        lines.add("")

        lines.add("Best candidates by signal")
        for ((signal, list) in report.bestBySignal) {
            lines.add("")
            lines.add("$signal")
            if (list.isEmpty()) {
                lines.add("  No candidates")
            } else {
                list.take(8).forEachIndexed { i, c ->
                    lines.add("  ${i + 1}) ${c.idHex()} ${c.expression} corr=${"%.3f".format(c.correlation)} rmse=${"%.3f".format(c.rmse)} mae=${"%.3f".format(c.mae)}")
                    lines.add("     raw=${"%.1f".format(c.minRaw)}..${"%.1f".format(c.maxRaw)} truth=${"%.1f".format(c.minTruth)}..${"%.1f".format(c.maxTruth)}")
                    lines.add("     formula: $signal = ${c.expression} * ${"%.9f".format(c.scale)} + ${"%.9f".format(c.offset)}")
                }
            }
        }

        lines.add("")
        lines.add("Most interesting CAN IDs")
        report.ids.take(20).forEach { id ->
            lines.add("")
            lines.add("${id.idHex()} frames=${id.frameCount} candidates=${id.candidates.size}")
            val activityLine = id.byteActivity.joinToString("  ") { b ->
                "B${b.byteIndex}:${"%.0f".format(b.activityPct)}% ${b.min}-${b.max}"
            }
            lines.add("  bytes: $activityLine")
            id.candidates.take(4).forEach { c ->
                lines.add("  ${c.signal}: ${c.expression} corr=${"%.3f".format(c.correlation)} rmse=${"%.2f".format(c.rmse)}")
            }
        }

        lines.add("")
        lines.add("DBC-style best guess")
        lines.add(exportBestJson(report))

        return lines.joinToString("\n")
    }

    fun exportBestJson(report: CanSignalDiscoveryReport): String {
        val best = report.bestBySignal.mapNotNull { (signal, list) -> list.firstOrNull()?.let { signal to it } }
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"source\": \"OpenDashX Sprint 37 CAN Signal Discovery Engine\",")
        lines.add("  \"signals\": {")
        best.forEachIndexed { index, (signal, c) ->
            val comma = if (index == best.lastIndex) "" else ","
            lines.add("    \"$signal\": {")
            lines.add("      \"id\": \"${c.idHex()}\",")
            lines.add("      \"extended\": ${c.isExtended},")
            lines.add("      \"expression\": \"${c.expression}\",")
            lines.add("      \"scale\": ${"%.9f".format(c.scale)},")
            lines.add("      \"offset\": ${"%.9f".format(c.offset)},")
            lines.add("      \"rmse\": ${"%.6f".format(c.rmse)},")
            lines.add("      \"mae\": ${"%.6f".format(c.mae)},")
            lines.add("      \"correlation\": ${"%.6f".format(c.correlation)},")
            lines.add("      \"samples\": ${c.samples}")
            lines.add("    }$comma")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun computeByteActivity(group: List<CanLogFrame>): List<ByteActivity> {
        val out = mutableListOf<ByteActivity>()
        for (i in 0 until 8) {
            val values = group.map { if (i in it.data.indices) it.data[i].toInt() and 0xFF else 0 }
            val changes = values.zipWithNext().count { it.first != it.second }
            val freq = values.groupingBy { it }.eachCount()
            val entropy = freq.values.sumOf { count ->
                val p = count.toDouble() / values.size.coerceAtLeast(1)
                if (p <= 0.0) 0.0 else -p * ln(p)
            }
            out.add(
                ByteActivity(
                    byteIndex = i,
                    min = values.minOrNull() ?: 0,
                    max = values.maxOrNull() ?: 0,
                    changes = changes,
                    uniqueValues = freq.size,
                    activityPct = 100.0 * changes / (values.size - 1).coerceAtLeast(1),
                    entropy = entropy
                )
            )
        }
        return out
    }

    private fun nearestFrame(group: List<CanLogFrame>, timestampMs: Long): CanLogFrame? {
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
        return out
    }

    private fun linearFit(samples: List<PairSample>): Pair<Double, Double>? {
        val n = samples.size.toDouble()
        val sumX = samples.sumOf { it.raw }
        val sumY = samples.sumOf { it.truth }
        val sumXY = samples.sumOf { it.raw * it.truth }
        val sumXX = samples.sumOf { it.raw * it.raw }
        val denom = n * sumXX - sumX * sumX
        if (abs(denom) < 0.000001) return null
        val scale = (n * sumXY - sumX * sumY) / denom
        val offset = (sumY - scale * sumX) / n
        return if (scale.isFinite() && offset.isFinite()) scale to offset else null
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

    private fun normalizedError(c: FieldDiscoveryCandidate): Double {
        val range = (c.maxTruth - c.minTruth).coerceAtLeast(0.0001)
        return c.rmse / range
    }

    private fun complexityPenalty(expr: String): Int = when {
        expr.startsWith("U8") || expr.startsWith("S8") -> 1
        expr.startsWith("U16") || expr.startsWith("S16") -> 2
        else -> 3
    }

    private fun sane(c: FieldDiscoveryCandidate): Boolean {
        if (c.samples < 2) return false
        val corr = abs(c.correlation)
        if (corr < 0.55) return false
        return when (c.signal.lowercase()) {
            "rpm" -> c.rmse < 600.0
            "tps" -> c.rmse < 8.0
            "map" -> c.rmse < 50.0
            "coolant" -> c.rmse < 15.0
            "afr" -> c.rmse < 2.5
            "battery" -> c.rmse < 2.0
            else -> true
        }
    }
}
