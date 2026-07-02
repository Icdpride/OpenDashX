package com.example.opendashx.models

data class CanLogFrame(
    val timestampMs: Long,
    val id: Int,
    val isExtended: Boolean,
    val dlc: Int,
    val data: ByteArray
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }

    fun dataHex(): String {
        return data.take(dlc.coerceIn(0, 8)).joinToString(" ") {
            (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }
}
