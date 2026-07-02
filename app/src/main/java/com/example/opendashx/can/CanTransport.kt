package com.example.opendashx.can

import com.example.opendashx.usb.GsUsbDiagnostics

interface CanTransport {
    fun isReady(): Boolean
    fun frameCount(): Long
    fun framesPerSecond(): Long
    fun lastFrame(): CanFrame?
    fun diagnostics(): GsUsbDiagnostics
    fun start()
    fun stop()
}
