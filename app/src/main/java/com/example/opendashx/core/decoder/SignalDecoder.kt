package com.example.opendashx.core.decoder

import com.example.opendashx.models.CanLogFrame

class SignalDecoder(private val profile: DecoderProfile) {

    fun decode(frame: CanLogFrame): List<DecodedSignal> {
        val out = mutableListOf<DecodedSignal>()
        for (definition in profile.signals) {
            if (definition.id != frame.id) continue
            if (definition.isExtended != frame.isExtended) continue

            val raw = readExpression(frame, definition.expression) ?: continue
            val value = raw * definition.scale + definition.offset
            out.add(
                DecodedSignal(
                    name = definition.name,
                    value = value,
                    unit = definition.unit,
                    confidence = definition.confidence,
                    sourceId = frame.id,
                    isExtended = frame.isExtended,
                    expression = definition.expression,
                    updatedAtMs = frame.timestampMs
                )
            )
        }
        return out
    }

    private fun readExpression(frame: CanLogFrame, expression: String): Double? {
        val offset = expression.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
        return when {
            expression.startsWith("U8") -> u8(frame, offset).toDouble()
            expression.startsWith("S8") -> s8(frame, offset).toDouble()
            expression.startsWith("U16LE") -> u16le(frame, offset).toDouble()
            expression.startsWith("U16BE") -> u16be(frame, offset).toDouble()
            expression.startsWith("S16LE") -> s16le(frame, offset).toDouble()
            expression.startsWith("S16BE") -> s16be(frame, offset).toDouble()
            expression.startsWith("U24LE") -> u24le(frame, offset).toDouble()
            expression.startsWith("U24BE") -> u24be(frame, offset).toDouble()
            expression.startsWith("U32LE") -> u32le(frame, offset)
            expression.startsWith("U32BE") -> u32be(frame, offset)
            else -> null
        }
    }

    private fun byte(frame: CanLogFrame, index: Int): Int {
        return if (index in frame.data.indices) frame.data[index].toInt() and 0xFF else 0
    }

    private fun u8(f: CanLogFrame, o: Int): Int = byte(f, o)
    private fun s8(f: CanLogFrame, o: Int): Int = byte(f, o).toByte().toInt()
    private fun u16le(f: CanLogFrame, o: Int): Int = byte(f, o) or (byte(f, o + 1) shl 8)
    private fun u16be(f: CanLogFrame, o: Int): Int = (byte(f, o) shl 8) or byte(f, o + 1)
    private fun s16le(f: CanLogFrame, o: Int): Int = u16le(f, o).toShort().toInt()
    private fun s16be(f: CanLogFrame, o: Int): Int = u16be(f, o).toShort().toInt()
    private fun u24le(f: CanLogFrame, o: Int): Int = byte(f, o) or (byte(f, o + 1) shl 8) or (byte(f, o + 2) shl 16)
    private fun u24be(f: CanLogFrame, o: Int): Int = (byte(f, o) shl 16) or (byte(f, o + 1) shl 8) or byte(f, o + 2)

    private fun u32le(f: CanLogFrame, o: Int): Double {
        return (byte(f, o).toLong()
            or (byte(f, o + 1).toLong() shl 8)
            or (byte(f, o + 2).toLong() shl 16)
            or (byte(f, o + 3).toLong() shl 24)).toDouble()
    }

    private fun u32be(f: CanLogFrame, o: Int): Double {
        return ((byte(f, o).toLong() shl 24)
            or (byte(f, o + 1).toLong() shl 16)
            or (byte(f, o + 2).toLong() shl 8)
            or byte(f, o + 3).toLong()).toDouble()
    }
}
