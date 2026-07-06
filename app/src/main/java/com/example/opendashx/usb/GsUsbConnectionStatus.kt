package com.example.opendashx.usb

data class GsUsbConnectionStatus(
    val deviceFound: Boolean = false,
    val permissionGranted: Boolean = false,
    val connectionOpen: Boolean = false,
    val interfaceClaimed: Boolean = false,
    val bulkInFound: Boolean = false,
    val bulkOutFound: Boolean = false,
    val interfaceCount: Int = 0,
    val endpointCount: Int = 0,
    val receiveLoopRunning: Boolean = false,
    val protocolStatus: GsUsbProtocolStatus = GsUsbProtocolStatus(),
    val message: String = "Not started"
) {
    val ready: Boolean
        get() = deviceFound &&
            permissionGranted &&
            connectionOpen &&
            interfaceClaimed &&
            bulkInFound &&
            bulkOutFound &&
            protocolStatus.initialized &&
            receiveLoopRunning
}
