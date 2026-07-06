package com.example.opendashx.models

data class HolleyFrameSnapshot(
    val id: Int,
    val isExtended: Boolean,
    val dlc: Int,
    val count: Long,
    val fps: Long,
    val dataHex: String,
    val bytesHex: List<String>,
    val changedMask: String,
    val changeCounts: List<Long>,
    val u8Values: List<Int>,
    val u16LeValues: List<Int>,
    val u16BeValues: List<Int>
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }
}
