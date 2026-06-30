package com.example.opendashx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.opendashx.usb.GsUsbTransport
import com.example.opendashx.usb.UsbCanDeviceManager
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val usbManager = remember { UsbCanDeviceManager(this) }
                val canTransport = remember { GsUsbTransport(this) }

                var usbStatus by remember { mutableStateOf(usbManager.detectCanable()) }
                var transportStatus by remember { mutableStateOf(canTransport.connectionStatus) }

                DisposableEffect(Unit) {
                    canTransport.start()
                    onDispose {
                        canTransport.stop()
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        usbStatus = usbManager.detectCanable()
                        if (!canTransport.isReady()) {
                            canTransport.start()
                        }
                        transportStatus = canTransport.connectionStatus
                        delay(1000)
                    }
                }

                val protocol = transportStatus.protocolStatus
                val deviceConfig = protocol.deviceConfig
                val bitTiming = protocol.bitTimingConstants

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("OpenDash X", style = MaterialTheme.typography.headlineLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Version 0.4 - Sprint 4")
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("USB Adapter: ${if (usbStatus.connected) "Connected" else "Not detected"}")
                        Text("Device: ${usbStatus.deviceName}")
                        Text("VID: ${usbStatus.vendorId}  PID: ${usbStatus.productId}")
                        Text("Permission: ${if (usbStatus.permissionGranted) "Granted" else "Not granted"}")

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("USB Open: ${if (transportStatus.connectionOpen) "Yes" else "No"}")
                        Text("Interface Claimed: ${if (transportStatus.interfaceClaimed) "Yes" else "No"}")
                        Text("Bulk IN: ${if (transportStatus.bulkInFound) "Found" else "Missing"}")
                        Text("Bulk OUT: ${if (transportStatus.bulkOutFound) "Found" else "Missing"}")

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Host Format: ${if (protocol.hostFormatSent) "Sent" else "Failed"}")
                        Text("Device Config: ${if (protocol.deviceConfigRead) "Read" else "Failed"}")
                        Text("BT Constants: ${if (protocol.bitTimingConstantsRead) "Read" else "Failed"}")
                        Text("CAN Interfaces: ${deviceConfig?.interfaceCount ?: "-"}")
                        Text("CAN Clock: ${bitTiming?.clockHz?.let { "$it Hz" } ?: "-"}")

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("CAN Transport: ${if (transportStatus.ready) "Protocol Ready" else "Not ready"}")
                        Text("Frames: ${canTransport.frameCount()}")
                        Text("Status: ${transportStatus.message}")
                        Text("ECU: Waiting")
                    }
                }
            }
        }
    }
}
