package com.example.opendashx.core.decoder

import com.example.opendashx.models.LearnedSignalDefinition

object ProfileFromLearner {
    fun build(signals: List<LearnedSignalDefinition>): DecoderProfile {
        return DecoderProfile(
            ecuName = "Holley Terminator X / Terminator X Max",
            source = "OpenDash X Ground Truth Learner",
            signals = signals.map { learned ->
                SignalDefinition(
                    name = learned.signal,
                    unit = unitFor(learned.signal),
                    id = learned.id,
                    isExtended = learned.isExtended,
                    expression = learned.expression,
                    scale = learned.scale,
                    offset = learned.offset,
                    confidence = learned.confidence
                )
            }
        )
    }

    private fun unitFor(signal: String): String {
        return when (signal.lowercase()) {
            "rpm" -> "rpm"
            "tps" -> "%"
            "map" -> "kPa"
            "coolant" -> "F"
            "afr" -> "AFR"
            "battery" -> "V"
            "oilpressure", "oil pressure" -> "psi"
            "fuelpressure", "fuel pressure" -> "psi"
            else -> ""
        }
    }
}
