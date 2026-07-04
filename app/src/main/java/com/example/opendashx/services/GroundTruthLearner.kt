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
    ): List<LearnedSignalDefinition> {

        val groups = frames
            .groupBy { "${it.id}:${it.isExtended}" }
            .values
            .filter { it.size >= 10 }
            .sortedByDescending { it.size }

        val learned = mutableListOf<LearnedSignalDefinition>()



            for (group in groups) {
                val id = group.first().id
                val ext = group.first().isExtended

                for (expr in expressions()) {

                    val err = rmse(predicted, yVals)

                    if (!sane(signal, fit.first, fit.second, predicted)) continue

                                signal = signal,
                                id = id,
                                isExtended = ext,
                                expression = expr,
                                scale = fit.first,
                                offset = fit.second,
                                error = err,
                                confidence = confidence,
                        )
                    )
                }
            }

        }

        return learned.sortedBy { it.signal }
    }

    fun exportProfile(defs: List<LearnedSignalDefinition>): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"Holley Terminator X / Terminator X Max\",")
        lines.add("  \"signals\": {")
        defs.forEachIndexed { index, d ->
            lines.add("    \"${d.signal}\": {")
            lines.add("      \"id\": \"${d.idHex()}\",")
            lines.add("      \"extended\": ${d.isExtended},")
            lines.add("      \"expression\": \"${d.expression}\",")
            lines.add("      \"scale\": ${"%.9f".format(d.scale)},")
            lines.add("      \"offset\": ${"%.9f".format(d.offset)},")
            lines.add("      \"rmse\": ${"%.6f".format(d.error)},")
            lines.add("    }${if (index == defs.lastIndex) "" else ","}")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

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
        for (i in 0..6) {
            out.add("U16LE[$i]")
            out.add("U16BE[$i]")
            out.add("S16LE[$i]")
            out.add("S16BE[$i]")
        }
    }

    private fun raw(expr: String, data: ByteArray): Double? {
        val i = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
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
    }
}
