package com.example.opendashx.core.export

import com.example.opendashx.core.decoder.DecoderProfile

class DecoderProfileJsonExporter {
    fun export(profile: DecoderProfile): String {
        val lines = mutableListOf<String>()
        lines.add("{")
        lines.add("  \"ecu\": \"${escape(profile.ecuName)}\",")
        lines.add("  \"source\": \"${escape(profile.source)}\",")
        lines.add("  \"createdAtMs\": ${profile.createdAtMs},")
        lines.add("  \"signals\": {")
        profile.signals.forEachIndexed { index, signal ->
            val comma = if (index == profile.signals.lastIndex) "" else ","
            lines.add("    \"${escape(signal.name)}\": {")
            lines.add("      \"unit\": \"${escape(signal.unit)}\",")
            lines.add("      \"id\": \"${signal.idHex()}\",")
            lines.add("      \"extended\": ${signal.isExtended},")
            lines.add("      \"expression\": \"${escape(signal.expression)}\",")
            lines.add("      \"scale\": ${signal.scale},")
            lines.add("      \"offset\": ${signal.offset},")
            lines.add("      \"confidence\": ${signal.confidence}")
            lines.add("    }$comma")
        }
        lines.add("  }")
        lines.add("}")
        return lines.joinToString("\n")
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
