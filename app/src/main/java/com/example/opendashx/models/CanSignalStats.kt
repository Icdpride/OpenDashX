package com.example.opendashx.models

data class CanSignalStats(
    val id: Int,
    val isExtended: Boolean,
    val dlc: Int,
    val count: Long,
    val framesPerSecond: Long,
    val lastDataHex: String,
    val minBytesHex: String,
    val maxBytesHex: String,
    val changedMask: String,
    val changeScore: Long,
    val likelySignal: String
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }
}
