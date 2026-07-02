package com.example.opendashx.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class GsUsbDeviceConfig(
    val interfaceCount: Int,
    val softwareVersion: Long,
    val hardwareVersion: Long
)

data class GsUsbBitTimingConstants(
    val feature: Long,
    val clockHz: Long,
    val tseg1Min: Long,
    val tseg1Max: Long,
    val tseg2Min: Long,
    val tseg2Max: Long,
    val sjwMax: Long,
    val brpMin: Long,
    val brpMax: Long,
    val brpInc: Long
)

data class GsUsbBitTiming(
    val propSeg: Int,
    val phaseSeg1: Int,
    val phaseSeg2: Int,
    val sjw: Int,
    val brp: Int,
    val actualBitrate: Int
) {
    val tseg1: Int get() = propSeg + phaseSeg1
}

data class GsUsbProtocolStatus(
    val hostFormatSent: Boolean = false,
    val deviceConfigRead: Boolean = false,
    val bitTimingConstantsRead: Boolean = false,
    val bitTimingSet: Boolean = false,
    val canStarted: Boolean = false,
    val deviceConfig: GsUsbDeviceConfig? = null,
    val bitTimingConstants: GsUsbBitTimingConstants? = null,
    val bitTiming: GsUsbBitTiming? = null,
    val message: String = "Protocol not initialized"
) {
    val initialized: Boolean
        get() = hostFormatSent &&
            deviceConfigRead &&
            bitTimingConstantsRead &&
            bitTimingSet &&
            canStarted
}

class GsUsbProtocol(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface
) {
    fun initialize(targetBitrate: Int = 500_000): GsUsbProtocolStatus {
        val hostFormatOk = sendHostFormat()
        if (!hostFormatOk) {
            return GsUsbProtocolStatus(message = "Failed to send host format")
        }

        val deviceConfig = readDeviceConfig()
            ?: return GsUsbProtocolStatus(
                hostFormatSent = true,
                message = "Failed to read device config"
            )

        val bitTimingConstants = readBitTimingConstants()
            ?: return GsUsbProtocolStatus(
                hostFormatSent = true,
                deviceConfigRead = true,
                deviceConfig = deviceConfig,
                message = "Failed to read bit timing constants"
            )

        val timing = calculateBitTiming(bitTimingConstants, targetBitrate)
            ?: return GsUsbProtocolStatus(
                hostFormatSent = true,
                deviceConfigRead = true,
                bitTimingConstantsRead = true,
                deviceConfig = deviceConfig,
                bitTimingConstants = bitTimingConstants,
                message = "Could not calculate CAN bit timing"
            )

        val timingSet = setBitTiming(timing)
        if (!timingSet) {
            return GsUsbProtocolStatus(
                hostFormatSent = true,
                deviceConfigRead = true,
                bitTimingConstantsRead = true,
                deviceConfig = deviceConfig,
                bitTimingConstants = bitTimingConstants,
                bitTiming = timing,
                message = "Failed to set CAN bit timing"
            )
        }

        val started = setMode(GS_CAN_MODE_START)
        if (!started) {
            return GsUsbProtocolStatus(
                hostFormatSent = true,
                deviceConfigRead = true,
                bitTimingConstantsRead = true,
                bitTimingSet = true,
                deviceConfig = deviceConfig,
                bitTimingConstants = bitTimingConstants,
                bitTiming = timing,
                message = "Failed to start CAN controller"
            )
        }

        return GsUsbProtocolStatus(
            hostFormatSent = true,
            deviceConfigRead = true,
            bitTimingConstantsRead = true,
            bitTimingSet = true,
            canStarted = true,
            deviceConfig = deviceConfig,
            bitTimingConstants = bitTimingConstants,
            bitTiming = timing,
            message = "CAN controller started"
        )
    }

    fun stopCan(): Boolean {
        return setMode(GS_CAN_MODE_RESET)
    }

    private fun sendHostFormat(): Boolean {
        val buffer = ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(GS_HOST_BYTE_ORDER)
            .array()

        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_OUT,
            GS_USB_BREQ_HOST_FORMAT,
            1,
            usbInterface.id,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )

        return result == buffer.size
    }

    private fun readDeviceConfig(): GsUsbDeviceConfig? {
        val buffer = ByteArray(12)

        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN,
            GS_USB_BREQ_DEVICE_CONFIG,
            1,
            usbInterface.id,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )

        if (result < 12) return null

        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        byteBuffer.get()
        byteBuffer.get()
        byteBuffer.get()

        val interfaceCountZeroBased = byteBuffer.get().toInt() and 0xFF
        val softwareVersion = byteBuffer.int.toUnsignedLong()
        val hardwareVersion = byteBuffer.int.toUnsignedLong()

        return GsUsbDeviceConfig(
            interfaceCount = interfaceCountZeroBased + 1,
            softwareVersion = softwareVersion,
            hardwareVersion = hardwareVersion
        )
    }

    private fun readBitTimingConstants(): GsUsbBitTimingConstants? {
        val buffer = ByteArray(40)

        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN,
            GS_USB_BREQ_BT_CONST,
            1,
            usbInterface.id,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )

        if (result < 40) return null

        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        return GsUsbBitTimingConstants(
            feature = byteBuffer.int.toUnsignedLong(),
            clockHz = byteBuffer.int.toUnsignedLong(),
            tseg1Min = byteBuffer.int.toUnsignedLong(),
            tseg1Max = byteBuffer.int.toUnsignedLong(),
            tseg2Min = byteBuffer.int.toUnsignedLong(),
            tseg2Max = byteBuffer.int.toUnsignedLong(),
            sjwMax = byteBuffer.int.toUnsignedLong(),
            brpMin = byteBuffer.int.toUnsignedLong(),
            brpMax = byteBuffer.int.toUnsignedLong(),
            brpInc = byteBuffer.int.toUnsignedLong()
        )
    }

    private fun setBitTiming(timing: GsUsbBitTiming): Boolean {
        val buffer = ByteBuffer
            .allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(timing.propSeg)
            .putInt(timing.phaseSeg1)
            .putInt(timing.phaseSeg2)
            .putInt(timing.sjw)
            .putInt(timing.brp)
            .array()

        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_OUT,
            GS_USB_BREQ_BITTIMING,
            1,
            usbInterface.id,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )

        return result == buffer.size
    }

    private fun setMode(mode: Int): Boolean {
        val buffer = ByteBuffer
            .allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(mode)
            .putInt(0)
            .array()

        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_OUT,
            GS_USB_BREQ_MODE,
            1,
            usbInterface.id,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )

        return result == buffer.size
    }

    private fun calculateBitTiming(
        constants: GsUsbBitTimingConstants,
        targetBitrate: Int
    ): GsUsbBitTiming? {
        val brpMin = constants.brpMin.toInt().coerceAtLeast(1)
        val brpMax = constants.brpMax.toInt().coerceAtLeast(brpMin)
        val brpInc = constants.brpInc.toInt().coerceAtLeast(1)

        val tseg1Min = constants.tseg1Min.toInt().coerceAtLeast(1)
        val tseg1Max = constants.tseg1Max.toInt().coerceAtLeast(tseg1Min)
        val tseg2Min = constants.tseg2Min.toInt().coerceAtLeast(1)
        val tseg2Max = constants.tseg2Max.toInt().coerceAtLeast(tseg2Min)
        val sjwMax = constants.sjwMax.toInt().coerceAtLeast(1)

        var best: GsUsbBitTiming? = null
        var bestError = Long.MAX_VALUE
        var bestSampleError = Long.MAX_VALUE
        val clock = constants.clockHz

        for (brp in brpMin..brpMax step brpInc) {
            for (tseg1 in tseg1Min..tseg1Max) {
                for (tseg2 in tseg2Min..tseg2Max) {
                    val totalTq = 1 + tseg1 + tseg2
                    val actual = (clock / (brp.toLong() * totalTq.toLong())).toInt()
                    val bitrateError = abs(actual.toLong() - targetBitrate.toLong())

                    val samplePointPermille = ((1 + tseg1) * 1000L) / totalTq
                    val sampleError = abs(samplePointPermille - 800L)

                    if (
                        bitrateError < bestError ||
                        (bitrateError == bestError && sampleError < bestSampleError)
                    ) {
                        val propSeg = tseg1 / 2
                        val phaseSeg1 = tseg1 - propSeg
                        val sjw = minOf(1, sjwMax, tseg2)

                        best = GsUsbBitTiming(
                            propSeg = propSeg,
                            phaseSeg1 = phaseSeg1,
                            phaseSeg2 = tseg2,
                            sjw = sjw,
                            brp = brp,
                            actualBitrate = actual
                        )
                        bestError = bitrateError
                        bestSampleError = sampleError
                    }

                    if (bestError == 0L && bestSampleError == 0L) {
                        return best
                    }
                }
            }
        }

        return best
    }

    private fun Int.toUnsignedLong(): Long = this.toLong() and 0xFFFF_FFFFL

    companion object {
        private const val USB_DIR_IN = 0x80
        private const val USB_DIR_OUT = 0x00
        private const val USB_TYPE_VENDOR = 0x40
        private const val USB_RECIP_INTERFACE = 0x01

        private const val REQUEST_TYPE_VENDOR_IN =
            USB_DIR_IN or USB_TYPE_VENDOR or USB_RECIP_INTERFACE

        private const val REQUEST_TYPE_VENDOR_OUT =
            USB_DIR_OUT or USB_TYPE_VENDOR or USB_RECIP_INTERFACE

        private const val GS_USB_BREQ_HOST_FORMAT = 0
        private const val GS_USB_BREQ_BITTIMING = 1
        private const val GS_USB_BREQ_MODE = 2
        private const val GS_USB_BREQ_BT_CONST = 4
        private const val GS_USB_BREQ_DEVICE_CONFIG = 5

        private const val GS_CAN_MODE_RESET = 0
        private const val GS_CAN_MODE_START = 1

        private const val GS_HOST_BYTE_ORDER = 0x0000BEEF
        private const val USB_TIMEOUT_MS = 1000
    }
}
