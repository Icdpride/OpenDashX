package com.example.opendashx.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbCanStatus(
    val connected: Boolean = false,
    val deviceName: String = "Not detected",
    val vendorId: String = "",
    val productId: String = "",
    val permissionGranted: Boolean = false
)

class UsbCanDeviceManager(private val context: Context) {

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun detectCanable(): UsbCanStatus {
        val devices = usbManager.deviceList.values

        val device = devices.firstOrNull { usbDevice ->
            usbDevice.vendorId == 0x1D50 && usbDevice.productId == 0x606F
        }

        return if (device != null) {
            UsbCanStatus(
                connected = true,
                deviceName = getDeviceLabel(device),
                vendorId = "0x${device.vendorId.toString(16).uppercase()}",
                productId = "0x${device.productId.toString(16).uppercase()}",
                permissionGranted = usbManager.hasPermission(device)
            )
        } else {
            UsbCanStatus()
        }
    }

    private fun getDeviceLabel(device: UsbDevice): String {
        return device.productName ?: device.deviceName ?: "CANable gs_usb"
    }
}