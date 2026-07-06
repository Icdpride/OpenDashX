package com.example.opendashx.core.plugins

import com.example.opendashx.core.decoder.DecoderProfile

interface EcuPlugin {
    val name: String
    val supportedHardware: List<String>

    fun defaultProfile(): DecoderProfile?
    fun canAutoDetect(deviceName: String?): Boolean
}
