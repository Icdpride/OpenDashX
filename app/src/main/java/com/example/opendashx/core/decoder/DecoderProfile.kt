package com.example.opendashx.core.decoder

data class DecoderProfile(
    val ecuName: String,
    val source: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val signals: List<SignalDefinition> = emptyList()
)

data class SignalDefinition(
    val name: String,
    val unit: String,
    val id: Int,
    val isExtended: Boolean,
    val expression: String,
    val scale: Double,
    val offset: Double,
    val confidence: Double
) {
    fun idHex(): String {
        return if (isExtended) {
            "0x" + id.toUInt().toString(16).uppercase().padStart(8, '0')
        } else {
            "0x" + id.toUInt().toString(16).uppercase().padStart(3, '0')
        }
    }
}
