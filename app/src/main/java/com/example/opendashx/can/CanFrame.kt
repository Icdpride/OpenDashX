package com.example.opendashx.can

data class CanFrame(
    val id: Int,
    val data: ByteArray,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isExtended: Boolean = false,
    val isRemoteFrame: Boolean = false,
    val isErrorFrame: Boolean = false
) {
    val dlc: Int get() = data.size

    fun idHex(): String = "0x" + id.toString(16).uppercase()

    fun dataHex(): String {
        if (data.isEmpty()) return "-"
        return data.joinToString(" ") { byte ->
            (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }
}
