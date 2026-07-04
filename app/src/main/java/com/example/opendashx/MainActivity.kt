package com.example.opendashx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.GroundTruthSnapshot
import com.example.opendashx.models.LearnedSignalDefinition
import com.example.opendashx.models.LiveDashboardSnapshot
import com.example.opendashx.models.ReplayProgress
import com.example.opendashx.models.RecorderSaveResult
import com.example.opendashx.models.ReplayCursorState
import com.example.opendashx.services.CanLogReplay
import com.example.opendashx.services.CanSessionRecorder
import com.example.opendashx.services.GroundTruthLearner
import com.example.opendashx.services.ReplaySignalDecoder
import com.example.opendashx.services.LiveDecoderSmoother
import com.example.opendashx.services.LiveDecodedSignalStore
import com.example.opendashx.services.HolleyReverseEngineeringAnalyzer
import com.example.opendashx.services.CanSignalDiscoveryEngine
import com.example.opendashx.usb.GsUsbTransport
import com.example.opendashx.usb.UsbCanDeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val recorder = remember { CanSessionRecorder(this) }
                val replay = remember { CanLogReplay(this) }
                val learner = remember { GroundTruthLearner() }
                val replayDecoder = remember { ReplaySignalDecoder() }
                val liveSmoother = remember { LiveDecoderSmoother() }
                val liveStore = remember { LiveDecodedSignalStore() }
                val reverseAnalyzer = remember { HolleyReverseEngineeringAnalyzer() }
                val discoveryEngine = remember { CanSignalDiscoveryEngine() }
                var reverseEngineeringText by remember { mutableStateOf("No reverse engineering analysis yet.") }
                var discoveryText by remember { mutableStateOf("No CAN discovery run yet.") }
                val usbManager = remember { UsbCanDeviceManager(this) }
                val canTransport = remember { GsUsbTransport(this, recorder) }

                var usbStatus by remember { mutableStateOf(usbManager.detectCanable()) }
                var transportStatus by remember { mutableStateOf(canTransport.connectionStatus) }
                var diagnostics by remember { mutableStateOf(canTransport.diagnostics()) }
                var frameCount by remember { mutableLongStateOf(0L) }
                var framesPerSecond by remember { mutableLongStateOf(0L) }
                var recording by remember { mutableStateOf(false) }
                var recordedCount by remember { mutableLongStateOf(0L) }
                var lastSaveResult by remember { mutableStateOf<RecorderSaveResult?>(null) }

                var replayProgress by remember { mutableStateOf(ReplayProgress()) }
                var replayStatusText by remember { mutableStateOf("No replay loaded") }
                var cursor by remember { mutableStateOf(ReplayCursorState()) }
                var playing by remember { mutableStateOf(false) }
                var replayStep by remember { mutableStateOf(25) }
                var replayDelayMs by remember { mutableStateOf(100L) }
                var dashboardSnapshot by remember { mutableStateOf(LiveDashboardSnapshot()) }
                var liveDashboardSnapshot by remember { mutableStateOf(LiveDashboardSnapshot()) }

                var snapshotLabel by remember { mutableStateOf("idle") }
                var rpmText by remember { mutableStateOf("") }
                var tpsText by remember { mutableStateOf("") }
                var mapText by remember { mutableStateOf("") }
                var coolantText by remember { mutableStateOf("") }
                var afrText by remember { mutableStateOf("") }
                var batteryText by remember { mutableStateOf("") }

                var snapshots by remember { mutableStateOf(loadSnapshots()) }
                var learnedSignals by remember { mutableStateOf(emptyList<LearnedSignalDefinition>()) }
                var exportText by remember { mutableStateOf("No learned profile yet") }

                val currentLearnedSignals = rememberUpdatedState(learnedSignals)

                DisposableEffect(Unit) {
                    canTransport.setLiveFrameListener { frame ->
                        val defs = currentLearnedSignals.value
                        if (defs.isNotEmpty()) {
                            val decoded = replayDecoder.decode(frame, defs)
                            if (decoded.decodedCount > 0) {
                                scope.launch {
                                    liveDashboardSnapshot = decoded
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
                        transportStatus = canTransport.connectionStatus
                        diagnostics = canTransport.diagnostics()
                        frameCount = canTransport.frameCount()
                        framesPerSecond = canTransport.framesPerSecond()
                        recording = recorder.isRecording()
                        recordedCount = recorder.count()
                        delay(300)
                    }
                }

                LaunchedEffect(playing, cursor.totalFrames, replayStep, replayDelayMs) {
                    while (playing && cursor.totalFrames > 1) {
                        val next = min(cursor.cursorIndex + replayStep, cursor.totalFrames - 1)
                        cursor = cursorFromFrames(replay.getFrames(), next, replayProgress.fileName)
                        if (next >= cursor.totalFrames - 1) playing = false
                        delay(replayDelayMs)
                    }
                }

                LaunchedEffect(cursor.cursorIndex, cursor.totalFrames, learnedSignals) {
                    dashboardSnapshot = replayDecoder.decode(replay.getFrames().getOrNull(cursor.cursorIndex), learnedSignals)
                }

                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll)
                            .padding(12.dp)
                    ) {
                        Text("OpenDash X", style = MaterialTheme.typography.headlineLarge)
                        Text("Version 3.7 - CAN Signal Discovery Engine")
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("USB: ${if (usbStatus.connected) "Connected" else "Not detected"}")
                        Text("Device: ${usbStatus.deviceName}")
                        Text("Permission: ${if (usbStatus.permissionGranted) "Granted" else "Not granted"}")
                        Text("Status: ${transportStatus.message}")
                        Text("Frames: $frameCount")
                        Text("Frames/sec: $framesPerSecond")
                        Text("Unique IDs: ${diagnostics.uniqueIdCount}")

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("1) Record CAN Session", style = MaterialTheme.typography.titleLarge)
                        Text("Recording: ${if (recording) "YES" else "NO"}")
                        Text("Recorded Frames: $recordedCount")
                        Text("Current Step: ${recorder.currentStep()}")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                recorder.start()
                                lastSaveResult = recorder.lastSaveResult()
                            }) { Text("Start Recording") }

                            Button(onClick = { recorder.nextStep() }) { Text("Next Step") }

                            Button(onClick = {
                                lastSaveResult = recorder.stopWithDiagnostics()
                            }) { Text("Stop + Save") }
                        }
                        SaveDiagnosticsView(lastSaveResult ?: recorder.lastSaveResult())

Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = {
        lastSaveResult = recorder.writeSaveTest()
    }) { Text("Save Test") }
}

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("2) Load Latest Log", style = MaterialTheme.typography.titleLarge)
                        Text("Status: $replayStatusText")
                        Text("Phase: ${replayProgress.phase}")
                        Text("Frames Loaded: ${cursor.totalFrames}")
                        Text("Progress: ${replayProgress.percent}%")
                        LinearProgressIndicator(
                            progress = { replayProgress.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            enabled = !replayProgress.busy,
                            onClick = {
                                scope.launch {
                                    replayProgress = ReplayProgress(busy = true, phase = "Loading", message = "Starting...")
                                    val stats = withContext(Dispatchers.IO) {
                                        replay.loadMostRecent(maxFrames = Int.MAX_VALUE) { p -> replayProgress = p }
                                    }
                                    replayStatusText = stats.status
                                    cursor = cursorFromFrames(replay.getFrames(), 0, replayProgress.fileName)
                                    dashboardSnapshot = replayDecoder.decode(replay.getFrames().firstOrNull(), learnedSignals)
                                    replayProgress = replayProgress.copy(busy = false, phase = "Loaded", percent = 100)
                                }
                            }
                        ) { Text("Load Latest Log") }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("3) Replay Timeline", style = MaterialTheme.typography.titleLarge)
                        if (cursor.loaded) {
                            Text("File: ${cursor.fileName}")
                            Text("Cursor: ${cursor.cursorIndex}/${cursor.totalFrames - 1}   ${"%.2f".format(cursor.elapsedSec)} sec   ${cursor.percent}%")
                            Slider(
                                value = cursor.cursorIndex.toFloat(),
                                onValueChange = { v ->
                                    cursor = cursorFromFrames(replay.getFrames(), v.toInt(), replayProgress.fileName)
                                },
                                valueRange = 0f..max(1, cursor.totalFrames - 1).toFloat()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { cursor = cursorFromFrames(replay.getFrames(), max(0, cursor.cursorIndex - 250), replayProgress.fileName) }) { Text("-250") }
                                Button(onClick = { cursor = cursorFromFrames(replay.getFrames(), max(0, cursor.cursorIndex - 25), replayProgress.fileName) }) { Text("-25") }
                                Button(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
                                Button(onClick = { cursor = cursorFromFrames(replay.getFrames(), min(cursor.totalFrames - 1, cursor.cursorIndex + 25), replayProgress.fileName) }) { Text("+25") }
                                Button(onClick = { cursor = cursorFromFrames(replay.getFrames(), min(cursor.totalFrames - 1, cursor.cursorIndex + 250), replayProgress.fileName) }) { Text("+250") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { replayStep = 5; replayDelayMs = 120L }) { Text("0.25x") }
                                Button(onClick = { replayStep = 25; replayDelayMs = 100L }) { Text("1x") }
                                Button(onClick = { replayStep = 50; replayDelayMs = 100L }) { Text("2x") }
                                Button(onClick = { replayStep = 125; replayDelayMs = 100L }) { Text("5x") }
                            }

                            CurrentFrameView(replay.getFrames().getOrNull(cursor.cursorIndex))
                            ReplayDashboardPreview(dashboardSnapshot, learnedSignals.size)
                        } else {
                            Text("Load a log first.")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("4) Add Ground Truth At Cursor", style = MaterialTheme.typography.titleLarge)
                        Text("Move the timeline to the moment that matches your Holley display, enter the known values, then press Add Snapshot.")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(snapshotLabel, { snapshotLabel = it }, label = { Text("Label") }, modifier = Modifier.width(170.dp))
                            OutlinedTextField(rpmText, { rpmText = it }, label = { Text("RPM") }, modifier = Modifier.width(120.dp))
                            OutlinedTextField(tpsText, { tpsText = it }, label = { Text("TPS %") }, modifier = Modifier.width(120.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(mapText, { mapText = it }, label = { Text("MAP kPa") }, modifier = Modifier.width(120.dp))
                            OutlinedTextField(coolantText, { coolantText = it }, label = { Text("Coolant F") }, modifier = Modifier.width(130.dp))
                            OutlinedTextField(afrText, { afrText = it }, label = { Text("AFR") }, modifier = Modifier.width(110.dp))
                            OutlinedTextField(batteryText, { batteryText = it }, label = { Text("Battery V") }, modifier = Modifier.width(130.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val snap = GroundTruthSnapshot(
                                    timestampMs = cursor.cursorTimeMs,
                                    label = snapshotLabel,
                                    rpm = rpmText.toDoubleOrNull(),
                                    tps = tpsText.toDoubleOrNull(),
                                    mapKpa = mapText.toDoubleOrNull(),
                                    coolantF = coolantText.toDoubleOrNull(),
                                    afr = afrText.toDoubleOrNull(),
                                    batteryV = batteryText.toDoubleOrNull(),
                                    frameCount = cursor.cursorIndex
                                )
                                snapshots = snapshots + snap
                                saveSnapshots(snapshots)
                            }) { Text("Add Snapshot At Cursor") }

                            Button(onClick = {
                                snapshots = emptyList()
                                learnedSignals = emptyList()
                                exportText = "No learned profile yet"
                                saveSnapshots(snapshots)
                            }) { Text("Clear Snapshots") }
                        }

                        SnapshotTable(snapshots)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("5) Quick Reverse Engineering Analysis", style = MaterialTheme.typography.titleLarge)
                        Text("Discrete search: tests common ECU scales plus fixed offsets like 0, -40, +10, +12.5. No free regression.")
                        Button(onClick = {
                            val report = reverseAnalyzer.analyze(replay.getFrames(), snapshots)
                            val lines = mutableListOf<String>()
                            lines.add("Frames: ${report.totalFrames}   Snapshots: ${report.totalSnapshots}")
                            report.candidatesBySignal.forEach { (signal, list) ->
                                lines.add("")
                                lines.add("$signal candidates")
                                if (list.isEmpty()) {
                                    lines.add("  No strong candidates")
                                } else {
                                    list.take(8).forEachIndexed { i, c ->
                                        lines.add("  ${i + 1}) conf=${"%.1f".format(c.confidence)} corr=${"%.3f".format(c.correlation)} rmse=${"%.3f".format(c.rmse)} mae=${"%.3f".format(c.mae)} ${c.idHex()} ${c.expression}")
                                        lines.add("     formula: ${signal} = ${c.expression} * ${"%.9f".format(c.scale)} + ${"%.9f".format(c.offset)}")
                                    }
                                }
                            }
                            lines.add("")
                            lines.add("Best decoder JSON:")
                            lines.add(reverseAnalyzer.exportBestJson(report))
                            reverseEngineeringText = lines.joinToString("\n")
                        }) {
                            Text("Analyze Reverse Engineering Candidates")
                        }
                        Text(reverseEngineeringText)

                        Text("7) Learn Decoder Profile", style = MaterialTheme.typography.titleLarge)
                        Button(
                            enabled = !replayProgress.busy,
                            onClick = {
                                scope.launch {
                                    replayProgress = ReplayProgress(busy = true, phase = "Learning", percent = 10, message = "Learning from timestamped snapshots...")
                                    val result = withContext(Dispatchers.Default) {
                                        learner.learn(replay.getFrames(), snapshots)
                                    }
                                    learnedSignals = result
                                    dashboardSnapshot = replayDecoder.decode(replay.getFrames().getOrNull(cursor.cursorIndex), result)
                                    liveSmoother.reset()
                                    liveStore.reset()
                                    liveDashboardSnapshot = LiveDashboardSnapshot()
                                    exportText = learner.exportProfile(result)
                                    saveProfile(exportText)
                                    replayProgress = replayProgress.copy(busy = false, phase = "Learned", percent = 100, message = "Profile learned")
                                }
                            }
                        ) { Text("Learn From Timestamped Snapshots") }

                        Column(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                            LearnedSignalTable(learnedSignals)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Saved Decoder Profile", style = MaterialTheme.typography.titleLarge)
                        Text(exportText)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("8) Reverse Engineering Suite - Advanced", style = MaterialTheme.typography.titleLarge)
                        Text("Ranks the top CAN layouts for each signal using timestamped snapshots. Use this before trusting live preview.")
                        Button(onClick = {
                            val report = reverseAnalyzer.analyze(replay.getFrames(), snapshots)
                            val lines = mutableListOf<String>()
                            lines.add("Frames: ${report.totalFrames}   Snapshots: ${report.totalSnapshots}")
                            report.candidatesBySignal.forEach { (signal, list) ->
                                lines.add("")
                                lines.add("$signal candidates")
                                if (list.isEmpty()) {
                                    lines.add("  No strong candidates")
                                } else {
                                    list.take(8).forEachIndexed { i, c ->
                                        lines.add("  ${i + 1}) conf=${"%.1f".format(c.confidence)} corr=${"%.3f".format(c.correlation)} rmse=${"%.3f".format(c.rmse)} mae=${"%.3f".format(c.mae)} ${c.idHex()} ${c.expression}  value=${"%.1f".format(c.minValue)}..${"%.1f".format(c.maxValue)} raw=${"%.1f".format(c.minRaw)}..${"%.1f".format(c.maxRaw)}")
                                        lines.add("     formula: ${signal} = ${c.expression} * ${"%.9f".format(c.scale)} + ${"%.9f".format(c.offset)}")
                                    }
                                }
                            }
                            lines.add("")
                            lines.add("Best decoder JSON:")
                            lines.add(reverseAnalyzer.exportBestJson(report))
                            reverseEngineeringText = lines.joinToString("\n")
                        }) {
                            Text("Analyze Reverse Engineering Candidates")
                        }
                        Text(reverseEngineeringText)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("9) Live Decoder Preview", style = MaterialTheme.typography.titleLarge)
                        Text("Uses the learned profile against live CAN frames. Now ignores irrelevant frames, aggregates signals, and filters jumps.")
                        LiveDecoderPreview(liveDashboardSnapshot, learnedSignals.size)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("USB / Parser")
                        Text("Read Successes: ${diagnostics.readSuccesses}")
                        Text("Read Errors: ${diagnostics.readErrors}")
                        Text("Bytes Received: ${diagnostics.bytesReceived}")
                        Text("Parse OK: ${diagnostics.parseSuccesses}")
                        Text("Parse Failed: ${diagnostics.parseFailures}")
                        Text("Last Raw: ${diagnostics.lastRawHex}")
                    }
                }
            }
        }
    }

    private fun cursorFromFrames(frames: List<CanLogFrame>, index: Int, fileName: String): ReplayCursorState {
        if (frames.isEmpty()) return ReplayCursorState()
        val safe = index.coerceIn(0, frames.size - 1)
        return ReplayCursorState(
            loaded = true,
            fileName = fileName,
            totalFrames = frames.size,
            cursorIndex = safe,
            cursorTimeMs = frames[safe].timestampMs,
            startTimeMs = frames.first().timestampMs,
            endTimeMs = frames.last().timestampMs
        )
    }

    private fun snapshotsFile(): File = File(getExternalFilesDir(null), "OpenDashX_ground_truth_snapshots.csv")
    private fun profileFile(): File = File(getExternalFilesDir(null), "OpenDashX_learned_decoder_profile.json")
    private fun saveProfile(text: String) { profileFile().writeText(text) }

    private fun saveSnapshots(items: List<GroundTruthSnapshot>) {
        val lines = mutableListOf("timestampMs,label,rpm,tps,mapKpa,coolantF,afr,batteryV,frameCount")
        items.forEach { s ->
            lines.add(listOf(
                s.timestampMs.toString(),
                s.label.replace(",", " "),
                s.rpm?.toString() ?: "",
                s.tps?.toString() ?: "",
                s.mapKpa?.toString() ?: "",
                s.coolantF?.toString() ?: "",
                s.afr?.toString() ?: "",
                s.batteryV?.toString() ?: "",
                s.frameCount.toString()
            ).joinToString(","))
        }
        snapshotsFile().writeText(lines.joinToString("\n"))
    }

    private fun loadSnapshots(): List<GroundTruthSnapshot> {
        val file = snapshotsFile()
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 9) return@mapNotNull null
            GroundTruthSnapshot(
                timestampMs = p[0].toLongOrNull() ?: return@mapNotNull null,
                label = p[1],
                rpm = p[2].toDoubleOrNull(),
                tps = p[3].toDoubleOrNull(),
                mapKpa = p[4].toDoubleOrNull(),
                coolantF = p[5].toDoubleOrNull(),
                afr = p[6].toDoubleOrNull(),
                batteryV = p[7].toDoubleOrNull(),
                frameCount = p[8].toIntOrNull() ?: 0
            )
        }
    }
}


@Composable
private fun LiveDecoderPreview(snapshot: LiveDashboardSnapshot, learnedSignalCount: Int) {
    if (learnedSignalCount == 0) {
        Text("No learned profile loaded yet.")
        return
    }
    Text("Live decoded signals: ${snapshot.decodedCount}   Source: ${snapshot.sourceFrameId}   Time: ${snapshot.sourceTimeMs}")
    Text("Stable mode: irrelevant CAN IDs are ignored; values are aggregated and jump-filtered.")
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("RPM", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.rpm, 0), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("TPS %", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.tps, 1), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("MAP kPa", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.mapKpa, 1), style = MaterialTheme.typography.headlineMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("Coolant F", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.coolantF, 1), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("AFR", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.afr, 2), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("Battery V", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.batteryV, 1), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ReplayDashboardPreview(snapshot: LiveDashboardSnapshot, learnedSignalCount: Int) {
    Spacer(modifier = Modifier.height(10.dp))
    Text("Replay Dashboard Preview", style = MaterialTheme.typography.titleLarge)
    if (learnedSignalCount == 0) {
        Text("No learned decoder profile yet. Add snapshots, then press Learn From Timestamped Snapshots.")
        return
    }
    Text("Decoded signals on current frame: ${snapshot.decodedCount}   Source: ${snapshot.sourceFrameId}")
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("RPM", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.rpm, 0), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("TPS %", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.tps, 1), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("MAP kPa", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.mapKpa, 1), style = MaterialTheme.typography.headlineMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("Coolant F", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.coolantF, 1), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("AFR", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.afr, 2), style = MaterialTheme.typography.headlineMedium)
        }
        Column {
            Text("Battery V", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.display(snapshot.batteryV, 1), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun CurrentFrameView(frame: CanLogFrame?) {
    Text("Current Frame", style = MaterialTheme.typography.titleMedium)
    if (frame == null) {
        Text("-")
        return
    }
    val idHex = if (frame.isExtended) {
        "0x" + frame.id.toUInt().toString(16).uppercase().padStart(8, '0')
    } else {
        "0x" + frame.id.toUInt().toString(16).uppercase().padStart(3, '0')
    }
    val dataHex = frame.data.joinToString(" ") { ((it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')) }
    Text("Time: ${frame.timestampMs}   ID: $idHex   Ext: ${frame.isExtended}   DLC: ${frame.data.size}")
    Text("Data: $dataHex")
    Text("Bytes: " + frame.data.mapIndexed { i, b -> "B$i=${b.toInt() and 0xFF}" }.joinToString("  "))
}

@Composable
private fun SnapshotTable(rows: List<GroundTruthSnapshot>) {
    Text("Snapshots: ${rows.size}")
    Text("Label        TimeMs      RPM     TPS     MAP     Coolant AFR    Battery  Cursor")
    Divider()
    rows.forEach { s ->
        Text(
            "${s.label.take(12).padEnd(12)} " +
                "${s.timestampMs.toString().padEnd(10)} " +
                "${(s.rpm?.toString() ?: "-").padEnd(7)} " +
                "${(s.tps?.toString() ?: "-").padEnd(7)} " +
                "${(s.mapKpa?.toString() ?: "-").padEnd(7)} " +
                "${(s.coolantF?.toString() ?: "-").padEnd(8)} " +
                "${(s.afr?.toString() ?: "-").padEnd(6)} " +
                "${(s.batteryV?.toString() ?: "-").padEnd(8)} " +
                s.frameCount
        )
    }
}

@Composable
private fun LearnedSignalTable(rows: List<LearnedSignalDefinition>) {
    Text("Learned Signals", style = MaterialTheme.typography.titleLarge)
    Text("Signal   Conf     Error    ID           Expr        Formula")
    Divider()
    if (rows.isEmpty()) {
        Text("No learned signals yet. Add snapshots at different timeline positions, then learn.")
    } else {
        rows.forEach { r ->
            Text(
                "${r.signal.padEnd(8)} " +
                    "${"%.1f".format(r.confidence).padEnd(8)} " +
                    "${"%.3f".format(r.error).padEnd(8)} " +
                    "${r.idHex().padEnd(12)} " +
                    "${r.expression.padEnd(11)} " +
                    r.formula()
            )
        }
    }
}


@Composable
private fun SaveDiagnosticsView(result: RecorderSaveResult) {
    Spacer(modifier = Modifier.height(6.dp))
    Text("Save Diagnostics", style = MaterialTheme.typography.titleMedium)
    Text("Result: ${if (result.ok) "OK" else "NOT SAVED"}")
    Text("Message: ${result.message}")
    Text("Directory: ${result.directory}")
    Text("File: ${result.fileName}")
    Text("Frames Written: ${result.framesWritten}")
    Text("File Size: ${result.fileSizeBytes} bytes")
    if (result.exception != null) {
        Text("Exception:")
        Text(result.exception)
    }
}
