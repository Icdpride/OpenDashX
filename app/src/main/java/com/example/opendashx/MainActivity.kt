package com.example.opendashx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.LearnedSignalDefinition
import com.example.opendashx.models.ReplayStats
import com.example.opendashx.services.CanLogReplay
import com.example.opendashx.services.CanSessionRecorder
import com.example.opendashx.services.GroundTruthLearner
import com.example.opendashx.usb.GsUsbTransport
import com.example.opendashx.usb.UsbCanDeviceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()

                val recorder = remember { CanSessionRecorder(this) }
                val usbManager = remember { UsbCanDeviceManager(this) }
                val canTransport = remember { GsUsbTransport(this, recorder) }
                val replay = remember { CanLogReplay(this) }
                val learner = remember { GroundTruthLearner() }

                var usbStatus by remember { mutableStateOf(usbManager.detectCanable()) }
                var transportStatus by remember { mutableStateOf(canTransport.connectionStatus) }
                var recorderStatus by remember { mutableStateOf(recorder.lastSaveResult().message) }

                var replayStats by remember { mutableStateOf(ReplayStats()) }
                var replayStatusText by remember { mutableStateOf("No replay loaded") }
                var cursorIndex by remember { mutableStateOf(0) }
                var jumpCursorText by remember { mutableStateOf("") }

                var labelText by remember { mutableStateOf("idle") }
                var rpmText by remember { mutableStateOf("") }
                var tpsText by remember { mutableStateOf("") }
                var mapText by remember { mutableStateOf("") }
                var coolantText by remember { mutableStateOf("") }
                var afrText by remember { mutableStateOf("") }
                var batteryText by remember { mutableStateOf("") }

                var snapshots by remember { mutableStateOf(preloadedHolleySnapshots()) }
                var learnedSignals by remember { mutableStateOf(listOf<LearnedSignalDefinition>()) }
                var learnedJson by remember { mutableStateOf("") }
                var snapshotStatusText by remember { mutableStateOf("Preloaded ${preloadedHolleySnapshots().size} snapshots from 20260706_232632") }

                val liveDecodedStore = remember { ConcurrentHashMap<String, LiveDecodedReading>() }
                var liveDecodedValues by remember { mutableStateOf<Map<String, LiveDecodedReading>>(emptyMap()) }
                var smoothedRpm by remember { mutableStateOf<Double?>(null) }
                var smoothedTps by remember { mutableStateOf<Double?>(null) }
                var smoothedAfr by remember { mutableStateOf<Double?>(null) }
                var smoothedCoolant by remember { mutableStateOf<Double?>(null) }
                var latencyCompMs by remember { mutableStateOf(500) }
                var manualHolleyRpm by remember { mutableStateOf("") }
                var manualHolleyTps by remember { mutableStateOf("") }
                var manualHolleyAfr by remember { mutableStateOf("") }
                var manualHolleyCoolant by remember { mutableStateOf("") }
                var frozenRpm by remember { mutableStateOf<Double?>(null) }
                var frozenTps by remember { mutableStateOf<Double?>(null) }
                var frozenAfr by remember { mutableStateOf<Double?>(null) }
                var frozenCoolant by remember { mutableStateOf<Double?>(null) }
                var frozenTimestampText by remember { mutableStateOf("No freeze captured") }

                DisposableEffect(Unit) {
                    canTransport.setLiveFrameListener { frame ->
                        embeddedHolleyProfile().forEach { sig ->
                            if (frame.id == sig.id && frame.isExtended == sig.extended) {
                                val raw = rawExpressionValueSprint43(sig.expression, frame.data)
                                if (raw != null) {
                                    val value = raw * sig.scale + sig.offset
                                    if (value.isFinite()) {
                                        liveDecodedStore[sig.name] = LiveDecodedReading(
                                            signal = sig.name,
                                            value = value,
                                            raw = raw,
                                            idHex = sig.idHex,
                                            timestampMs = frame.timestampMs
                                        )
                                    }
                                }
                            }
                        }
                    }
                    canTransport.start()
                    onDispose {
                        canTransport.setLiveFrameListener(null)
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
                        liveDecodedValues = liveDecodedStore.toMap()

                        liveDecodedStore["RPM"]?.value?.let { raw ->
                            val calibrated = applyCalibration("RPM", raw)
                            smoothedRpm = smoothValue(smoothedRpm, calibrated, 0.35)
                        }
                        liveDecodedStore["TPS"]?.value?.let { raw ->
                            val calibrated = applyCalibration("TPS", raw)
                            smoothedTps = smoothValue(smoothedTps, calibrated, 0.45)
                        }
                        liveDecodedStore["AFR"]?.value?.let { raw ->
                            val calibrated = applyCalibration("AFR", raw)
                            smoothedAfr = smoothValue(smoothedAfr, calibrated, 0.40)
                        }
                        liveDecodedStore["Coolant"]?.value?.let { raw ->
                            val calibrated = applyCalibration("Coolant", raw)
                            smoothedCoolant = smoothValue(smoothedCoolant, calibrated, 0.20)
                        }

                        delay(100)
                    }
                }

                val frames = replay.getFrames()
                val safeLastFrame = (frames.size - 1).coerceAtLeast(0)
                cursorIndex = cursorIndex.coerceIn(0, safeLastFrame)

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("OpenDash X", style = MaterialTheme.typography.headlineLarge)
                        Text("Version 4.8 - Freeze Frame Calibration")
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("USB: ${if (usbStatus.connected) "Connected" else "Not detected"}")
                        Text("Device: ${usbStatus.deviceName}")
                        Text("Permission: ${if (usbStatus.permissionGranted) "Granted" else "Not granted"}")
                        Text("Status: ${transportStatus.message}")
                        Text("Frames/sec: ${canTransport.framesPerSecond()}  Total Frames: ${canTransport.frameCount()}")
                        Text("Unique IDs: ${canTransport.diagnostics().uniqueIdCount}")

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("1) Record CAN Session", style = MaterialTheme.typography.titleLarge)
                        Text("Recording: ${if (recorder.isRecording()) "YES" else "NO"}")
                        Text("Recorded Frames: ${recorder.count()}")
                        Text("Current Step: ${recorder.currentStep()}")

                        Row {
                            Button(onClick = {
                                recorder.start()
                                recorderStatus = recorder.lastSaveResult().message
                            }) { Text("Start Recording") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                recorder.nextStep()
                            }) { Text("Next Step") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                val result = recorder.stopWithDiagnostics()
                                recorderStatus = result.message
                            }) { Text("Stop + Save") }
                        }
                        Text(recorderStatus)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("2) Replay Timeline", style = MaterialTheme.typography.titleLarge)
                        Row {
                            Button(onClick = {
                                scope.launch {
                                    replayStatusText = "Loading latest log..."
                                    replayStats = replay.loadMostRecent { p ->
                                        replayStatusText = "${p.phase}: ${p.message}"
                                    }
                                    cursorIndex = 0
                                    jumpCursorText = "0"
                                    replayStatusText = replayStats.status
                                }
                            }) { Text("Load Latest Log") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                cursorIndex = 0
                                jumpCursorText = "0"
                            }) { Text("Start") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                cursorIndex = safeLastFrame
                                jumpCursorText = cursorIndex.toString()
                            }) { Text("End") }
                        }

                        Text("File: ${replayStats.fileName}")
                        Text("Replay: ${replayStats.frameCount} frames, ${replayStats.idCount} IDs, ${replayStats.durationMs} ms")
                        Text(replayStatusText)

                        if (frames.isNotEmpty()) {
                            Slider(
                                value = cursorIndex.toFloat(),
                                onValueChange = {
                                    cursorIndex = it.roundToInt().coerceIn(0, safeLastFrame)
                                    jumpCursorText = cursorIndex.toString()
                                },
                                valueRange = 0f..safeLastFrame.toFloat()
                            )
                            val f = frames[cursorIndex]
                            Text("Current Frame")
                            Text("Cursor: $cursorIndex / $safeLastFrame")
                            Text("Time: ${f.timestampMs}   ID: ${f.idHex()}   Ext: ${f.isExtended}   DLC: ${f.dlc}")
                            Text("Data: ${f.dataHex()}")
                            Text("Bytes: " + f.data.take(f.dlc.coerceIn(0, 8)).mapIndexed { i, b -> "B$i=${b.toInt() and 0xFF}" }.joinToString("  "))
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("3) Assisted Cursor Controls", style = MaterialTheme.typography.titleLarge)
                        Text("Type a cursor/frame number from your notes, jump directly to it, fine tune, then capture.")

                        Row {
                            OutlinedTextField(
                                value = jumpCursorText,
                                onValueChange = { jumpCursorText = it },
                                label = { Text("Cursor / Frame Index") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                val target = jumpCursorText.toIntOrNull()
                                if (target != null && frames.isNotEmpty()) {
                                    cursorIndex = target.coerceIn(0, safeLastFrame)
                                    jumpCursorText = cursorIndex.toString()
                                }
                            }) { Text("Jump") }
                        }

                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(onClick = { cursorIndex = (cursorIndex - 1000).coerceAtLeast(0); jumpCursorText = cursorIndex.toString() }) { Text("-1000") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { cursorIndex = (cursorIndex - 100).coerceAtLeast(0); jumpCursorText = cursorIndex.toString() }) { Text("-100") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { cursorIndex = (cursorIndex - 10).coerceAtLeast(0); jumpCursorText = cursorIndex.toString() }) { Text("-10") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { cursorIndex = (cursorIndex + 10).coerceAtMost(safeLastFrame); jumpCursorText = cursorIndex.toString() }) { Text("+10") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { cursorIndex = (cursorIndex + 100).coerceAtMost(safeLastFrame); jumpCursorText = cursorIndex.toString() }) { Text("+100") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { cursorIndex = (cursorIndex + 1000).coerceAtMost(safeLastFrame); jumpCursorText = cursorIndex.toString() }) { Text("+1000") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = {
                                val previous = snapshots.map { it.frameCount }.filter { it < cursorIndex }.maxOrNull()
                                if (previous != null) {
                                    cursorIndex = previous
                                    jumpCursorText = previous.toString()
                                }
                            }) { Text("Prev Snapshot") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = {
                                val next = snapshots.map { it.frameCount }.filter { it > cursorIndex }.minOrNull()
                                if (next != null) {
                                    cursorIndex = next
                                    jumpCursorText = next.toString()
                                }
                            }) { Text("Next Snapshot") }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("4) Capture Ground Truth At Cursor", style = MaterialTheme.typography.titleLarge)
                        Text("Enter the Holley handheld values for this exact cursor, then capture.")

                        OutlinedTextField(value = labelText, onValueChange = { labelText = it }, label = { Text("Label") })
                        Row {
                            OutlinedTextField(value = rpmText, onValueChange = { rpmText = it }, label = { Text("RPM") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(value = tpsText, onValueChange = { tpsText = it }, label = { Text("TPS %") }, modifier = Modifier.weight(1f))
                        }
                        Row {
                            OutlinedTextField(value = mapText, onValueChange = { mapText = it }, label = { Text("MAP kPa") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(value = coolantText, onValueChange = { coolantText = it }, label = { Text("Coolant F") }, modifier = Modifier.weight(1f))
                        }
                        Row {
                            OutlinedTextField(value = afrText, onValueChange = { afrText = it }, label = { Text("AFR") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(value = batteryText, onValueChange = { batteryText = it }, label = { Text("Battery V") }, modifier = Modifier.weight(1f))
                        }

                        Row {
                            Button(onClick = {
                                val requestedCursor = jumpCursorText.trim().toIntOrNull()
                                if (requestedCursor == null) {
                                    snapshotStatusText = "Enter a valid cursor/frame number before capturing"
                                } else {
                                    val captureCursor = if (frames.isNotEmpty()) {
                                        requestedCursor.coerceIn(0, safeLastFrame)
                                    } else {
                                        requestedCursor
                                    }

                                    cursorIndex = if (frames.isNotEmpty()) captureCursor else cursorIndex
                                    jumpCursorText = captureCursor.toString()

                                    val ts = frames.getOrNull(captureCursor)?.timestampMs ?: System.currentTimeMillis()
                                    snapshots = snapshots + GroundTruthSnapshot(
                                        timestampMs = ts,
                                        label = labelText,
                                        rpm = rpmText.toDoubleOrNull(),
                                        tps = tpsText.toDoubleOrNull(),
                                        mapKpa = mapText.toDoubleOrNull(),
                                        coolantF = coolantText.toDoubleOrNull(),
                                        afr = afrText.toDoubleOrNull(),
                                        batteryV = batteryText.toDoubleOrNull(),
                                        frameCount = captureCursor
                                    )
                                    snapshotStatusText = "Captured ${labelText.ifBlank { "snapshot" }} at cursor $captureCursor  framesLoaded=${frames.size}"
                                }
                            }) { Text("Capture Snapshot At Cursor") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                snapshots = emptyList()
                                learnedSignals = emptyList()
                                learnedJson = ""
                                snapshotStatusText = "Snapshots cleared"
                            }) { Text("Clear Snapshots") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                snapshots = preloadedHolleySnapshots()
                                snapshotStatusText = "Restored ${snapshots.size} preloaded snapshots"
                            }) { Text("Restore Preloaded Snapshots") }
                        }

                        Text("Snapshots: ${snapshots.size}")
                        Text("Debug: typedCursor=$jumpCursorText currentCursor=$cursorIndex framesLoaded=${frames.size}")
                        Text(snapshotStatusText)
                        SnapshotTable(snapshots)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("5) Learn Decoder Profile", style = MaterialTheme.typography.titleLarge)
                        Row {
                            Button(onClick = {
                                learnedSignals = learner.learn(frames, snapshots)
                                learnedJson = learner.exportProfile(learnedSignals)
                            }) { Text("Learn From Snapshots") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                snapshots = preloadedHolleySnapshots()
                                learnedSignals = learner.learn(frames, snapshots)
                                learnedJson = learner.exportProfile(learnedSignals)
                                snapshotStatusText = "Loaded ${snapshots.size} preloaded snapshots and learned decoder profile"
                            }) { Text("Load Preloaded + Learn") }
                        }

                        if (learnedSignals.isEmpty()) {
                            Text("No learned signals yet. Capture snapshots at different cursor positions, then learn.")
                        } else {
                            learnedSignals.forEach { s ->
                                Text("${s.signal}: ${s.idHex()} ${s.expression}  ${s.formula()}  rmse=${"%.3f".format(s.error)} conf=${"%.1f".format(s.confidence)}")
                            }
                        }


                        Spacer(modifier = Modifier.height(16.dp))
                        Text("6) Decoder Verification Dashboard", style = MaterialTheme.typography.titleLarge)
                        Text("Uses your preloaded 20260706_232632 snapshots and offline decoder profile. Move or jump the cursor, then compare decoded values to Holley/snapshot values.")

                        if (frames.isEmpty()) {
                            Text("Load a replay log first.")
                        } else {
                            embeddedHolleyProfile().forEach { sig ->
                                val result = decodeNearestSignal(frames, cursorIndex, sig, searchWindowFrames = 6000)
                                val truth = latestSnapshotTruthNearCursor(snapshots, cursorIndex, sig.name)
                                if (result == null) {
                                    Text("${sig.name}: no nearby ${sig.idHex} frame found")
                                } else {
                                    val truthText = truth?.let { "  Holley=${"%.2f".format(it)}  Err=${"%.2f".format(result.value - it)}" } ?: ""
                                    Text("${sig.name}: decoded=${"%.2f".format(result.value)}  raw=${"%.1f".format(result.raw)}  @cursor=${result.frameIndex}$truthText")
                                }
                            }

                            Text("Profile quality: RPM provisional, TPS usable, Coolant strong, AFR provisional. MAP/Battery need more varied snapshots.")
                        }



                        Spacer(modifier = Modifier.height(16.dp))
                        Text("7) Driver Dashboard Preview", style = MaterialTheme.typography.titleLarge)
                        Text("Calibrated, smoothed live values using the current Volvo 240 / Holley profile.")

                        val dashRpmRaw = liveDecodedValues["RPM"]?.value?.let { applyCalibration("RPM", it) }
                        val dashTpsRaw = liveDecodedValues["TPS"]?.value?.let { applyCalibration("TPS", it) }
                        val dashCoolantRaw = liveDecodedValues["Coolant"]?.value?.let { applyCalibration("Coolant", it) }
                        val dashAfrRaw = liveDecodedValues["AFR"]?.value?.let { applyCalibration("AFR", it) }

                        val rpmDisplay = smoothedRpm ?: dashRpmRaw
                        val tpsDisplay = smoothedTps ?: dashTpsRaw
                        val coolantDisplay = smoothedCoolant ?: dashCoolantRaw
                        val afrDisplay = smoothedAfr ?: dashAfrRaw

                        Text("RPM: ${rpmDisplay?.let { "%.0f".format(it) } ?: "--"} rpm", style = MaterialTheme.typography.headlineMedium)
                        Row {
                            Text("TPS: ${tpsDisplay?.let { "%.1f".format(it) } ?: "--"}%")
                            Spacer(modifier = Modifier.width(20.dp))
                            Text("AFR: ${afrDisplay?.let { "%.2f".format(it) } ?: "--"}")
                            Spacer(modifier = Modifier.width(20.dp))
                            Text("Coolant: ${coolantDisplay?.let { "%.0f".format(it) } ?: "--"}°F")
                        }

                        val latestAge = liveDecodedValues.values.minOfOrNull { System.currentTimeMillis() - it.timestampMs }
                        Text("Live frame age: ${latestAge?.toString() ?: "--"} ms")
                        Text("Visual lag compensation: ${latencyCompMs} ms")
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(onClick = { latencyCompMs = 0 }) { Text("0ms") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { latencyCompMs = 250 }) { Text("250ms") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { latencyCompMs = 500 }) { Text("500ms") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { latencyCompMs = 750 }) { Text("750ms") }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { latencyCompMs = 1000 }) { Text("1000ms") }
                        }

                        Text("Calibration: RPM = decoded × 1.04944 - 129.50 | Coolant = decoded + 2°F")

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Freeze Frame Calibration", style = MaterialTheme.typography.titleMedium)
                        Text("Tap Freeze OpenDash Values first, then enter Holley values afterward. Error is Frozen OpenDash minus Holley.")

                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(onClick = {
                                frozenRpm = rpmDisplay
                                frozenTps = tpsDisplay
                                frozenAfr = afrDisplay
                                frozenCoolant = coolantDisplay
                                frozenTimestampText = "Frozen at ${System.currentTimeMillis()}"
                            }) { Text("Freeze OpenDash Values") }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(onClick = {
                                manualHolleyRpm = ""
                                manualHolleyTps = ""
                                manualHolleyAfr = ""
                                manualHolleyCoolant = ""
                                frozenRpm = null
                                frozenTps = null
                                frozenAfr = null
                                frozenCoolant = null
                                frozenTimestampText = "Freeze cleared"
                            }) { Text("Clear Freeze") }
                        }

                        Text(frozenTimestampText)
                        Text("Frozen OpenDash RPM: ${frozenRpm?.let { "%.0f".format(it) } ?: "--"}")
                        Text("Frozen OpenDash TPS: ${frozenTps?.let { "%.1f".format(it) } ?: "--"}")
                        Text("Frozen OpenDash AFR: ${frozenAfr?.let { "%.2f".format(it) } ?: "--"}")
                        Text("Frozen OpenDash Coolant: ${frozenCoolant?.let { "%.0f".format(it) } ?: "--"}")

                        Row {
                            OutlinedTextField(value = manualHolleyRpm, onValueChange = { manualHolleyRpm = it }, label = { Text("Holley RPM") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Err: ${comparisonErrorText(frozenRpm, manualHolleyRpm, 0)}")
                        }
                        Row {
                            OutlinedTextField(value = manualHolleyTps, onValueChange = { manualHolleyTps = it }, label = { Text("Holley TPS") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Err: ${comparisonErrorText(frozenTps, manualHolleyTps, 1)}")
                        }
                        Row {
                            OutlinedTextField(value = manualHolleyAfr, onValueChange = { manualHolleyAfr = it }, label = { Text("Holley AFR") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Err: ${comparisonErrorText(frozenAfr, manualHolleyAfr, 2)}")
                        }
                        Row {
                            OutlinedTextField(value = manualHolleyCoolant, onValueChange = { manualHolleyCoolant = it }, label = { Text("Holley Coolant") }, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Err: ${comparisonErrorText(frozenCoolant, manualHolleyCoolant, 0)}")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Calibration Profile", style = MaterialTheme.typography.titleMedium)
                        Text("RPM scale: 1.04944")
                        Text("RPM offset: -129.50")
                        Text("Coolant offset: +2.0°F")
                        Text("TPS scale/offset: 1.000 / 0.0")
                        Text("AFR scale/offset: 1.000 / 0.0")

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("8) Live Decoder Prototype", style = MaterialTheme.typography.titleLarge)
                        Text("Uses the same embedded decoder profile against live incoming CAN frames from the GS_USB adapter.")
                        Text("Live frames/sec: ${canTransport.framesPerSecond()}  Total: ${canTransport.frameCount()}  Unique IDs: ${canTransport.diagnostics().uniqueIdCount}")

                        if (!canTransport.isReady()) {
                            Text("CAN transport not ready yet.")
                        } else if (liveDecodedValues.isEmpty()) {
                            Text("Waiting for matching Holley frames. Start/keep the Holley ECU powered and confirm frames/sec is greater than 0.")
                        } else {
                            embeddedHolleyProfile().forEach { sig ->
                                val reading = liveDecodedValues[sig.name]
                                if (reading == null) {
                                    Text("${sig.name}: waiting for ${sig.idHex}")
                                } else {
                                    val ageMs = System.currentTimeMillis() - reading.timestampMs
                                    Text("${sig.name}: ${"%.2f".format(applyCalibration(sig.name, reading.value))} ${displayUnit(sig.name)}  rawDecoded=${"%.2f".format(reading.value)}  raw=${"%.1f".format(reading.raw)}  ${reading.idHex}  age=${ageMs}ms")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saved Decoder Profile")
                        Text(learnedJson.ifBlank { "{ }" })
                    }
                }
            }
        }
    }
}



private fun preloadedHolleySnapshots(): List<GroundTruthSnapshot> = listOf(
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "idle",
            rpm = 755.000000,
            tps = 12.000000,
            mapKpa = 81.000000,
            coolantF = 166.000000,
            afr = 12.480000,
            batteryV = 13.600000,
            frameCount = 16167
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "idle",
            rpm = 857.000000,
            tps = 13.000000,
            mapKpa = 81.000000,
            coolantF = 166.000000,
            afr = 12.680000,
            batteryV = 13.600000,
            frameCount = 31800
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "1500 rpm",
            rpm = 1504.000000,
            tps = 16.000000,
            mapKpa = 81.000000,
            coolantF = 165.000000,
            afr = 12.460000,
            batteryV = 13.600000,
            frameCount = 137027
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "1500 rpm",
            rpm = 1552.000000,
            tps = 15.000000,
            mapKpa = 81.000000,
            coolantF = 166.000000,
            afr = 11.740000,
            batteryV = 13.600000,
            frameCount = 157701
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2000 rpm",
            rpm = 2035.000000,
            tps = 17.000000,
            mapKpa = 81.000000,
            coolantF = 167.000000,
            afr = 12.780000,
            batteryV = 13.600000,
            frameCount = 206679
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2000 rpm",
            rpm = 2046.000000,
            tps = 17.000000,
            mapKpa = 81.000000,
            coolantF = 167.000000,
            afr = 12.900000,
            batteryV = 13.600000,
            frameCount = 221410
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2000 rpm",
            rpm = 2055.000000,
            tps = 17.000000,
            mapKpa = 81.000000,
            coolantF = 167.000000,
            afr = 12.600000,
            batteryV = 13.600000,
            frameCount = 229191
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2500 rpm",
            rpm = 2520.000000,
            tps = 19.000000,
            mapKpa = 81.000000,
            coolantF = 169.000000,
            afr = 12.210000,
            batteryV = 13.600000,
            frameCount = 287869
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2500 rpm",
            rpm = 2579.000000,
            tps = 19.000000,
            mapKpa = 81.000000,
            coolantF = 171.000000,
            afr = 12.380000,
            batteryV = 13.700000,
            frameCount = 330158
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "2500 rpm",
            rpm = 2597.000000,
            tps = 20.000000,
            mapKpa = 81.000000,
            coolantF = 172.000000,
            afr = 13.010000,
            batteryV = 13.700000,
            frameCount = 351044
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Blip 1",
            rpm = 1571.000000,
            tps = 36.000000,
            mapKpa = 81.000000,
            coolantF = 174.000000,
            afr = 20.050000,
            batteryV = 13.700000,
            frameCount = 412831
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Blip 2",
            rpm = 2933.000000,
            tps = 32.000000,
            mapKpa = 81.000000,
            coolantF = 175.000000,
            afr = 15.490000,
            batteryV = 13.700000,
            frameCount = 440477
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Blip 3",
            rpm = 3097.000000,
            tps = 41.000000,
            mapKpa = 81.000000,
            coolantF = 176.000000,
            afr = 12.910000,
            batteryV = 13.700000,
            frameCount = 481805
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Blip 4",
            rpm = 2756.000000,
            tps = 33.000000,
            mapKpa = 81.000000,
            coolantF = 176.000000,
            afr = 25.890000,
            batteryV = 13.700000,
            frameCount = 523481
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Return to id",
            rpm = 756.000000,
            tps = 10.000000,
            mapKpa = 81.000000,
            coolantF = 176.000000,
            afr = 13.310000,
            batteryV = 13.700000,
            frameCount = 558174
        ),
        GroundTruthSnapshot(
            timestampMs = 0L,
            label = "Return to id",
            rpm = 804.000000,
            tps = 11.000000,
            mapKpa = 81.000000,
            coolantF = 176.000000,
            afr = 12.260000,
            batteryV = 13.700000,
            frameCount = 585888
        ),
)

private data class LiveDecodedReading(
    val signal: String,
    val value: Double,
    val raw: Double,
    val idHex: String,
    val timestampMs: Long
)



private fun smoothValue(previous: Double?, next: Double, alpha: Double): Double {
    return if (previous == null || !previous.isFinite()) next else previous + alpha * (next - previous)
}

private fun comparisonErrorText(openDashValue: Double?, holleyText: String, decimals: Int): String {
    val holley = holleyText.toDoubleOrNull()
    if (openDashValue == null || holley == null) return "--"
    val err = openDashValue - holley
    return "%.${decimals}f".format(err)
}

private data class SignalCalibration(
    val scale: Double = 1.0,
    val offset: Double = 0.0
)

private fun calibrationFor(signal: String): SignalCalibration {
    return when (signal) {
        "RPM" -> SignalCalibration(scale = 1.04944, offset = -129.50)
        "Coolant" -> SignalCalibration(scale = 1.0, offset = 2.0)
        else -> SignalCalibration()
    }
}

private fun applyCalibration(signal: String, value: Double): Double {
    val c = calibrationFor(signal)
    return value * c.scale + c.offset
}

private fun displayUnit(signal: String): String {
    return when (signal) {
        "RPM" -> "rpm"
        "TPS" -> "%"
        "Coolant" -> "°F"
        "AFR" -> "AFR"
        else -> ""
    }
}


private data class EmbeddedSignal(
    val name: String,
    val idHex: String,
    val extended: Boolean,
    val expression: String,
    val scale: Double,
    val offset: Double,
    val rmse: Double,
    val correlation: Double
) {
    val id: Int = idHex.removePrefix("0x").removePrefix("0X").toUInt(16).toInt()
}

private data class DecodedAtCursor(
    val value: Double,
    val raw: Double,
    val frameIndex: Int
)

private fun embeddedHolleyProfile(): List<EmbeddedSignal> = listOf(
        EmbeddedSignal("RPM", "0x1E0457BD", true, "BIT_BE[6,16]", 0.164822753974736, -2427.832008970354764, 178.867775, 0.973863),
        EmbeddedSignal("TPS", "0x1E3A17BD", true, "U8[1]", 0.188503102523250, -10.591230472428521, 1.954621, 0.977436),
        EmbeddedSignal("Coolant", "0x1E07D7BD", true, "U8[1]", 1.047400241837969, 126.768077388149933, 0.388856, 0.995760),
        EmbeddedSignal("AFR", "0x1E8417BD", true, "U8[1]", 0.097737822702779, 3.558950445532348, 2.360350, 0.755669),
)

private fun decodeNearestSignal(
    frames: List<com.example.opendashx.models.CanLogFrame>,
    cursorIndex: Int,
    sig: EmbeddedSignal,
    searchWindowFrames: Int
): DecodedAtCursor? {
    if (frames.isEmpty()) return null
    val start = (cursorIndex - searchWindowFrames).coerceAtLeast(0)
    val end = (cursorIndex + searchWindowFrames).coerceAtMost(frames.lastIndex)

    var bestIndex = -1
    var bestDistance = Int.MAX_VALUE

    for (i in start..end) {
        val f = frames[i]
        if (f.id == sig.id && f.isExtended == sig.extended) {
            val distance = kotlin.math.abs(i - cursorIndex)
            if (distance < bestDistance) {
                bestIndex = i
                bestDistance = distance
            }
        }
    }

    if (bestIndex < 0) return null
    val frame = frames[bestIndex]
    val raw = rawExpressionValueSprint43(sig.expression, frame.data) ?: return null
    val value = raw * sig.scale + sig.offset
    return if (value.isFinite()) DecodedAtCursor(value, raw, bestIndex) else null
}

private fun latestSnapshotTruthNearCursor(
    snapshots: List<GroundTruthSnapshot>,
    cursorIndex: Int,
    signal: String
): Double? {
    val nearest = snapshots.minByOrNull { kotlin.math.abs(it.frameCount - cursorIndex) } ?: return null
    if (kotlin.math.abs(nearest.frameCount - cursorIndex) > 2500) return null
    return when (signal) {
        "RPM" -> nearest.rpm
        "TPS" -> nearest.tps
        "MAP" -> nearest.mapKpa
        "Coolant" -> nearest.coolantF
        "AFR" -> nearest.afr
        "Battery" -> nearest.batteryV
        else -> null
    }
}

private fun rawExpressionValueSprint43(expr: String, data: ByteArray): Double? {
    fun u8(ix: Int): Int {
        if (ix !in data.indices) throw IndexOutOfBoundsException()
        return data[ix].toInt() and 0xFF
    }

    fun s8(ix: Int): Int = u8(ix).toByte().toInt()
    fun u16le(ix: Int): Int = u8(ix) or (u8(ix + 1) shl 8)
    fun u16be(ix: Int): Int = (u8(ix) shl 8) or u8(ix + 1)
    fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v

    return try {
        when {
            expr.startsWith("BIT_BE") -> {
                val inside = expr.substringAfter("[").substringBefore("]")
                val parts = inside.split(",")
                val startBit = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
                val length = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
                readBitsBigEndianSprint43(data, startBit, length)?.toDouble()
            }
            expr.startsWith("BIT_LE") -> {
                val inside = expr.substringAfter("[").substringBefore("]")
                val parts = inside.split(",")
                val startBit = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
                val length = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
                readBitsLittleEndianSprint43(data, startBit, length)?.toDouble()
            }
            else -> {
                val i = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: return null
                when {
                    expr.startsWith("U8") -> u8(i).toDouble()
                    expr.startsWith("S8") -> s8(i).toDouble()
                    expr.startsWith("U16LE") -> u16le(i).toDouble()
                    expr.startsWith("U16BE") -> u16be(i).toDouble()
                    expr.startsWith("S16LE") -> s16(u16le(i)).toDouble()
                    expr.startsWith("S16BE") -> s16(u16be(i)).toDouble()
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun readBitsBigEndianSprint43(data: ByteArray, startBit: Int, length: Int): Long? {
    if (length <= 0 || length > 32) return null
    var value = 0L
    for (i in 0 until length) {
        val bitIndex = startBit + i
        val byteIndex = bitIndex / 8
        val bitInByte = 7 - (bitIndex % 8)
        if (byteIndex !in data.indices) return null
        val bit = ((data[byteIndex].toInt() and 0xFF) shr bitInByte) and 1
        value = (value shl 1) or bit.toLong()
    }
    return value
}

private fun readBitsLittleEndianSprint43(data: ByteArray, startBit: Int, length: Int): Long? {
    if (length <= 0 || length > 32) return null
    var value = 0L
    for (i in 0 until length) {
        val bitIndex = startBit + i
        val byteIndex = bitIndex / 8
        val bitInByte = bitIndex % 8
        if (byteIndex !in data.indices) return null
        val bit = ((data[byteIndex].toInt() and 0xFF) shr bitInByte) and 1
        value = value or (bit.toLong() shl i)
    }
    return value
}


@Composable
private fun SnapshotTable(snapshots: List<GroundTruthSnapshot>) {
    Column {
        Text("Label        Cursor     RPM     TPS     MAP     Coolant     AFR     Battery")
        snapshots.forEach { s ->
            Text(
                "${s.label.take(12).padEnd(12)} " +
                    "${s.frameCount.toString().padEnd(9)} " +
                    "${s.rpm?.toString() ?: "-"}     " +
                    "${s.tps?.toString() ?: "-"}     " +
                    "${s.mapKpa?.toString() ?: "-"}     " +
                    "${s.coolantF?.toString() ?: "-"}     " +
                    "${s.afr?.toString() ?: "-"}     " +
                    "${s.batteryV?.toString() ?: "-"}"
            )
        }
    }
}
