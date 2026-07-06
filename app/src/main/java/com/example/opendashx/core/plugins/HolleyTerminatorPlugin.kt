package com.example.opendashx.core.plugins

import com.example.opendashx.core.decoder.DecoderProfile

class HolleyTerminatorPlugin : EcuPlugin {
    override val name: String = "Holley Terminator X / Terminator X Max"
    override val supportedHardware: List<String> = listOf("canable2 gs_usb", "gs_usb", "Holley")

    override fun defaultProfile(): DecoderProfile? = null

    override fun canAutoDetect(deviceName: String?): Boolean {
        val n = deviceName?.lowercase() ?: return false
        return supportedHardware.any { hardware -> n.contains(hardware.lowercase()) }
    }
}
