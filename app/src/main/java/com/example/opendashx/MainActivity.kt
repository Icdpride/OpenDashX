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

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("OpenDash X", style = MaterialTheme.typography.headlineLarge)
                        Spacer(modifier = Modifier.height(24.dp))

                        Text("Version 0.3 - Sprint 3")
                        Spacer(modifier = Modifier.height(24.dp))

                        Text("USB Adapter: ${if (usbStatus.connected) "Connected" else "Not detected"}")
                        Text("Device: ${usbStatus.deviceName}")
                        Text("VID: ${usbStatus.vendorId}")
                        Text("PID: ${usbStatus.productId}")
                        Text("Permission: ${if (usbStatus.permissionGranted) "Granted" else "Not granted"}")

                        Spacer(modifier = Modifier.height(24.dp))

                        Text("GS_USB Device: ${if (transportStatus.deviceFound) "Found" else "Missing"}")
                        Text("USB Open: ${if (transportStatus.connectionOpen) "Yes" else "No"}")
                        Text("Interface Claimed: ${if (transportStatus.interfaceClaimed) "Yes" else "No"}")
                        Text("Bulk IN Endpoint: ${if (transportStatus.bulkInFound) "Found" else "Missing"}")
                        Text("Bulk OUT Endpoint: ${if (transportStatus.bulkOutFound) "Found" else "Missing"}")
                        Text("Interfaces: ${transportStatus.interfaceCount}")
                        Text("Endpoints: ${transportStatus.endpointCount}")

                        Spacer(modifier = Modifier.height(24.dp))

                        Text("CAN Transport: ${if (transportStatus.ready) "Ready" else "Not ready"}")
                        Text("Frames: ${canTransport.frameCount()}")
                        Text("Status: ${transportStatus.message}")
                        Text("ECU: Waiting")
                    }
                }
            }
        }
    }
}
