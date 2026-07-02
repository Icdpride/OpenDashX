package com.example.opendashx.usb

import com.example.opendashx.models.CanLogFrame

import android.content.Context
import android.hardware.usb.*
import com.example.opendashx.can.CanFrame
import com.example.opendashx.can.CanTransport
import com.example.opendashx.models.LearnedSignal
import com.example.opendashx.services.CanSessionRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GsUsbTransport(context: Context, private val recorder: CanSessionRecorder? = null) : CanTransport {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    private val running = AtomicBoolean(false)
    private val totalFrames = AtomicLong(0)
    private val currentFps = AtomicLong(0)
    private val readAttempts = AtomicLong(0)
    private val readSuccesses = AtomicLong(0)
    private val readTimeouts = AtomicLong(0)
    private val readErrors = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val parseSuccesses = AtomicLong(0)
    private val parseFailures = AtomicLong(0)

    private val idStats = ConcurrentHashMap<String, MutableFrameStats>()

    @Volatile private var lastCanFrame: CanFrame? = null
    @Volatile private var receiveThread: Thread? = null
    @Volatile private var lastBytesRead: Int = 0
    @Volatile private var lastRawHex: String = "-"
    @Volatile private var endpointReport: String = "-"
    @Volatile private var canStarted: Boolean = false

    var connectionStatus: GsUsbConnectionStatus = GsUsbConnectionStatus()
        private set

    override fun isReady(): Boolean = canStarted
    override fun frameCount(): Long = totalFrames.get()
    override fun framesPerSecond(): Long = currentFps.get()
    override fun lastFrame(): CanFrame? = lastCanFrame

    override fun diagnostics(): GsUsbDiagnostics {
        val candidates = buildCandidates()
        val learned = listOfNotNull(
            candidates.firstOrNull { it.signal == "RPM" },
            candidates.firstOrNull { it.signal == "TPS" },
            candidates.firstOrNull { it.signal == "MAP" },
            candidates.firstOrNull { it.signal == "Battery" },
            candidates.firstOrNull { it.signal == "AFR" },
            candidates.firstOrNull { it.signal == "Coolant" }
        )

        return GsUsbDiagnostics(
            readAttempts = readAttempts.get(),
            readSuccesses = readSuccesses.get(),
            readTimeouts = readTimeouts.get(),
            readErrors = readErrors.get(),
            bytesReceived = bytesReceived.get(),
            parseSuccesses = parseSuccesses.get(),
            parseFailures = parseFailures.get(),
            lastBytesRead = lastBytesRead,
            lastRawHex = lastRawHex,
            endpointReport = endpointReport,
            receiveMode = "Smart Signal Learner @ 1000000 bps",
            uniqueIdCount = idStats.size,
            learnedSignals = learned,
            rpmCandidates = candidates.filter { it.signal == "RPM" }.take(8),
            tpsCandidates = candidates.filter { it.signal == "TPS" }.take(8),
            mapCandidates = candidates.filter { it.signal == "MAP" }.take(8),
            batteryCandidates = candidates.filter { it.signal == "Battery" }.take(8),
            afrCandidates = candidates.filter { it.signal == "AFR" }.take(8),
            coolantCandidates = candidates.filter { it.signal == "Coolant" }.take(8)
        )
    }

    override fun start() = startWithBitrate(DEFAULT_CAN_BITRATE)

    fun startWithBitrate(targetBitrate: Int) {
        try {
            stop(false)
            resetCounters()

            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x1D50 && it.productId == 0x606F }
            if (device == null) {
                connectionStatus = GsUsbConnectionStatus(message = "CANable gs_usb device not found")
                return
            }

            if (!usbManager.hasPermission(device)) {
                endpointReport = descriptorOnlyReport(device)
                connectionStatus = GsUsbConnectionStatus(deviceFound = true, permissionGranted = false, interfaceCount = device.interfaceCount, message = "USB permission not granted")
                return
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                endpointReport = descriptorOnlyReport(device)
                connectionStatus = GsUsbConnectionStatus(deviceFound = true, permissionGranted = true, interfaceCount = device.interfaceCount, message = "Unable to open USB device")
                return
            }
            connection = conn

            val choice = chooseBulkInterface(device, conn)
            if (choice == null) {
                endpointReport = descriptorOnlyReport(device)
                connectionStatus = GsUsbConnectionStatus(deviceFound = true, permissionGranted = true, connectionOpen = true, interfaceCount = device.interfaceCount, message = "No claimable BULK interface")
                return
            }

            claimedInterface = choice.usbInterface

            val report = StringBuilder()
            report.appendLine("Sprint 21 Smart Signal Learner startup")
            report.appendLine(descriptorOnlyReport(device))
            report.appendLine("Selected IF${choice.usbInterface.id} IN=0x${choice.bulkIn.address.toString(16)} OUT=0x${choice.bulkOut.address.toString(16)}")
            report.appendLine("Queued RX requests: $REQUEST_COUNT")
            report.appendLine()
            report.appendLine(sendHostFormat(conn))
            report.appendLine(sendMode(conn, "STOP before setup", MODE_RESET))
            Thread.sleep(50)
            report.appendLine(readBtConst(conn))
            report.appendLine(readDeviceConfig(conn))
            report.appendLine(readTimestamp(conn, "timestamp before start"))
            report.appendLine(sendBitTiming1Mbps(conn))
            Thread.sleep(50)
            val startResult = sendMode(conn, "START normal", MODE_START)
            report.appendLine(startResult)
            Thread.sleep(100)
            report.appendLine(readTimestamp(conn, "timestamp after start"))
            report.appendLine(readDeviceConfig(conn))
            report.appendLine("Timing: 1 Mbps, BRP=1")
            report.appendLine("Learner: sanity filters + confidence scoring + saved profile")
            endpointReport = report.toString()

            canStarted = startResult.contains("result=8")
            running.set(true)
            startReceiveLoop(conn, choice.bulkIn)

            connectionStatus = GsUsbConnectionStatus(
                deviceFound = true,
                permissionGranted = true,
                connectionOpen = true,
                interfaceClaimed = true,
                bulkInFound = true,
                bulkOutFound = true,
                receiveLoopRunning = true,
                interfaceCount = device.interfaceCount,
                endpointCount = choice.usbInterface.endpointCount,
                protocolStatus = GsUsbProtocolStatus(
                    canStarted = canStarted,
                    message = "Smart Signal Learner start complete",
                    bitTiming = GsUsbBitTiming(propSeg = 0, phaseSeg1 = 135, phaseSeg2 = 34, sjw = 1, brp = 1, actualBitrate = targetBitrate)
                ),
                message = "Smart Signal Learner at $targetBitrate bps"
            )
        } catch (e: Throwable) {
            readErrors.incrementAndGet()
            lastRawHex = "start crash: ${e.javaClass.simpleName}: ${e.message}"
            connectionStatus = GsUsbConnectionStatus(message = lastRawHex)
        }
    }

    override fun stop() = stop(true)

    private fun stop(resetStatus: Boolean) {
        running.set(false)
        try { receiveThread?.interrupt() } catch (_: Exception) {}
        receiveThread = null
        try { connection?.let { sendMode(it, "STOP on close", MODE_RESET) } } catch (_: Exception) {}
        claimedInterface?.let { try { connection?.releaseInterface(it) } catch (_: Exception) {} }
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        claimedInterface = null
        canStarted = false
        if (resetStatus) connectionStatus = GsUsbConnectionStatus(message = "Stopped")
    }

    private data class EndpointChoice(val usbInterface: UsbInterface, val bulkIn: UsbEndpoint, val bulkOut: UsbEndpoint)

    private class MutableFrameStats(val id: Int, val isExtended: Boolean) {
        val count = AtomicLong(0)
        val secondCount = AtomicLong(0)
        @Volatile var fps: Long = 0
        @Volatile var lastData: ByteArray = ByteArray(8)
        private val minBytes = IntArray(8) { 255 }
        private val maxBytes = IntArray(8) { 0 }
        private val changeCounts = LongArray(8)
        private var initialized = false

        fun observe(frame: CanFrame) {
            count.incrementAndGet()
            secondCount.incrementAndGet()
            val padded = ByteArray(8)
            for (i in 0 until min(frame.data.size, 8)) padded[i] = frame.data[i]

            if (initialized) {
                for (i in 0 until 8) {
                    val v = padded[i].toInt() and 0xFF
                    val old = lastData[i].toInt() and 0xFF
                    if (v != old) changeCounts[i]++
                    if (v < minBytes[i]) minBytes[i] = v
                    if (v > maxBytes[i]) maxBytes[i] = v
                }
            } else {
                for (i in 0 until 8) {
                    val v = padded[i].toInt() and 0xFF
                    minBytes[i] = v
                    maxBytes[i] = v
                }
                initialized = true
            }
            lastData = padded
        }

        fun updateFps() {
            fps = secondCount.getAndSet(0)
        }

        fun changedWidthScore(start: Int, width: Int): Double {
            var score = 0.0
            for (i in start until (start + width).coerceAtMost(8)) {
                score += (maxBytes[i] - minBytes[i]).toDouble()
                score += changeCounts[i] * 0.05
            }
            return score
        }

        fun rawRange(expr: String): Pair<Double, Double> {
            val minData = ByteArray(8)
            val maxData = ByteArray(8)
            for (i in 0 until 8) {
                minData[i] = minBytes[i].toByte()
                maxData[i] = maxBytes[i].toByte()
            }
            val a = rawValueLocal(expr, minData)
            val b = rawValueLocal(expr, maxData)
            return Pair(min(a, b), max(a, b))
        }

        private fun rawValueLocal(expr: String, data: ByteArray): Double {
            fun st(e: String): Int = e.substringAfter("[").substringBefore("]").toIntOrNull() ?: 0
            fun u8(i: Int): Int = data[i].toInt() and 0xFF
            fun s8(i: Int): Int = data[i].toInt()
            fun u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
            fun u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
            fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v
            fun u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
            fun u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
            val i = st(expr)
            return when {
                expr.startsWith("U8") -> u8(i).toDouble()
                expr.startsWith("S8") -> s8(i).toDouble()
                expr.startsWith("U16LE") -> u16le(i).toDouble()
                expr.startsWith("U16BE") -> u16be(i).toDouble()
                expr.startsWith("S16LE") -> s16(u16le(i)).toDouble()
                expr.startsWith("S16BE") -> s16(u16be(i)).toDouble()
                expr.startsWith("U24LE") -> u24le(i).toDouble()
                expr.startsWith("U24BE") -> u24be(i).toDouble()
                else -> 0.0
            }
        }
    }

    private fun buildCandidates(): List<LearnedSignal> {
        val out = mutableListOf<LearnedSignal>()
        val expressions = mutableListOf<String>().apply {
            for (i in 0..7) { add("U8[$i]"); add("S8[$i]") }
            for (i in 0..6) { add("U16LE[$i]"); add("U16BE[$i]"); add("S16LE[$i]"); add("S16BE[$i]") }
            for (i in 0..5) { add("U24LE[$i]"); add("U24BE[$i]") }
        }

        idStats.values.forEach { s ->
            if (s.count.get() < 100) return@forEach

            for (expr in expressions) {
                val raw = rawValue(expr, s.lastData)
                val rr = s.rawRange(expr)

                candidateScales().forEach { scale ->
                    val cur = applyScale(raw, scale)
                    val lo = applyScale(rr.first, scale)
                    val hi = applyScale(rr.second, scale)
                    val minVal = min(lo, hi)
                    val maxVal = max(lo, hi)
                    val range = abs(maxVal - minVal)
                    val width = exprWidth(expr)
                    val start = exprStart(expr)
                    val movement = s.changedWidthScore(start, width)
                    val fpsBonus = if (s.fps in 20..160) 20.0 else 0.0

                    addIfSane(out, "RPM", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                    addIfSane(out, "TPS", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                    addIfSane(out, "MAP", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                    addIfSane(out, "Battery", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                    addIfSane(out, "AFR", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                    addIfSane(out, "Coolant", s, expr, scale, cur, minVal, maxVal, movement + fpsBonus, raw, range)
                }
            }
        }

        return out.sortedWith(
            compareByDescending<LearnedSignal> { it.confidence }
                .thenBy { it.signal }
                .thenBy { it.id }
                .thenBy { it.expression }
        )
    }

    private fun addIfSane(
        out: MutableList<LearnedSignal>,
        signal: String,
        s: MutableFrameStats,
        expr: String,
        scale: String,
        cur: Double,
        minVal: Double,
        maxVal: Double,
        baseScore: Double,
        raw: Double,
        range: Double
    ) {
        val sane = when (signal) {
            "RPM" -> cur in 450.0..8500.0 && maxVal in 900.0..9000.0 && range > 200.0
            "TPS" -> cur in 0.0..100.0 && maxVal <= 110.0 && range > 2.0
            "MAP" -> cur in 15.0..300.0 && maxVal <= 320.0
            "Battery" -> cur in 10.0..18.5 && maxVal <= 20.0 && range < 8.0
            "AFR" -> cur in 8.0..22.5 && maxVal <= 25.0 && range < 12.0
            "Coolant" -> cur in 40.0..260.0 && maxVal <= 300.0
            else -> false
        }
        if (!sane) return

        val priority = when (signal) {
            "RPM" -> if (expr.startsWith("U16")) 80.0 else if (expr.startsWith("U24")) 35.0 else 10.0
            "TPS" -> if (expr.startsWith("U8")) 80.0 else 15.0
            "MAP" -> if (expr.startsWith("U16")) 55.0 else 20.0
            "Battery" -> if (scale == "/100" || scale == "/1000") 70.0 else 10.0
            "AFR" -> if (scale == "/100" || scale == "/1000") 70.0 else 10.0
            "Coolant" -> if (scale.contains("-40") || scale == "x1") 45.0 else 15.0
            else -> 0.0
        }

        val penalty = when {
            expr.startsWith("U24") && (signal == "TPS" || signal == "Battery" || signal == "AFR" || signal == "Coolant") -> 200.0
            raw > 200000.0 -> 100.0
            else -> 0.0
        }

        val confidence = baseScore + priority - penalty
        if (confidence < 25.0) return

        out.add(
            LearnedSignal(
                signal = signal,
                id = s.id,
                isExtended = s.isExtended,
                expression = expr,
                scale = scale,
                currentValue = cur,
                minValue = minVal,
                maxValue = maxVal,
                confidence = confidence,
                reason = "range=${"%.1f".format(range)} fps=${s.fps}",
                dataHex = s.lastData.toHex(8)
            )
        )
    }

    private fun candidateScales(): List<String> = listOf(
        "x1", "/2", "/4", "/8", "/10", "/16", "/32", "/100", "/1000",
        "pct255", "-40", "-50", "/10-40", "/10-50", "/100-40", "/100-50"
    )

    private fun applyScale(raw: Double, scale: String): Double {
        return when (scale) {
            "x1" -> raw
            "/2" -> raw / 2.0
            "/4" -> raw / 4.0
            "/8" -> raw / 8.0
            "/10" -> raw / 10.0
            "/16" -> raw / 16.0
            "/32" -> raw / 32.0
            "/100" -> raw / 100.0
            "/1000" -> raw / 1000.0
            "pct255" -> raw * 100.0 / 255.0
            "-40" -> raw - 40.0
            "-50" -> raw - 50.0
            "/10-40" -> raw / 10.0 - 40.0
            "/10-50" -> raw / 10.0 - 50.0
            "/100-40" -> raw / 100.0 - 40.0
            "/100-50" -> raw / 100.0 - 50.0
            else -> raw
        }
    }

    private fun exprStart(expr: String): Int = expr.substringAfter("[").substringBefore("]").toIntOrNull() ?: 0
    private fun exprWidth(expr: String): Int = when {
        expr.startsWith("U24") -> 3
        expr.startsWith("U16") || expr.startsWith("S16") -> 2
        else -> 1
    }

    private fun rawValue(expr: String, data: ByteArray): Double {
        fun u8(i: Int): Int = data[i].toInt() and 0xFF
        fun s8(i: Int): Int = data[i].toInt()
        fun u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
        fun u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
        fun s16(v: Int): Int = if (v >= 32768) v - 65536 else v
        fun u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        fun u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
        val i = exprStart(expr)
        return when {
            expr.startsWith("U8") -> u8(i).toDouble()
            expr.startsWith("S8") -> s8(i).toDouble()
            expr.startsWith("U16LE") -> u16le(i).toDouble()
            expr.startsWith("U16BE") -> u16be(i).toDouble()
            expr.startsWith("S16LE") -> s16(u16le(i)).toDouble()
            expr.startsWith("S16BE") -> s16(u16be(i)).toDouble()
            expr.startsWith("U24LE") -> u24le(i).toDouble()
            expr.startsWith("U24BE") -> u24be(i).toDouble()
            else -> 0.0
        }
    }

    private fun chooseBulkInterface(device: UsbDevice, conn: UsbDeviceConnection): EndpointChoice? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                }
            }
            if (inEp != null && outEp != null && conn.claimInterface(intf, true)) return EndpointChoice(intf, inEp, outEp)
        }
        return null
    }

    private fun startReceiveLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        receiveThread = Thread {
            val pending = ArrayList<Byte>(4096)
            var framesThisSecond = 0L
            var lastFpsTimestamp = System.currentTimeMillis()
            val buffers = HashMap<UsbRequest, ByteBuffer>()
            val requests = mutableListOf<UsbRequest>()

            fun queueFresh(request: UsbRequest): Boolean {
                val packetSize = inEp.maxPacketSize.coerceAtLeast(32)
                val buffer = ByteBuffer.allocateDirect(packetSize).order(ByteOrder.LITTLE_ENDIAN)
                buffers[request] = buffer
                readAttempts.incrementAndGet()
                return try {
                    @Suppress("DEPRECATION")
                    request.queue(buffer, packetSize)
                } catch (_: Throwable) {
                    request.queue(buffer)
                }
            }

            try {
                repeat(REQUEST_COUNT) {
                    val request = UsbRequest()
                    if (request.initialize(conn, inEp)) {
                        requests.add(request)
                        if (!queueFresh(request)) readErrors.incrementAndGet()
                    }
                }

                while (running.get()) {
                    val completed = try { conn.requestWait(500) } catch (e: Throwable) {
                        readErrors.incrementAndGet()
                        lastRawHex = "requestWait crash: ${e.javaClass.simpleName}: ${e.message}"
                        null
                    }

                    if (completed == null) {
                        readTimeouts.incrementAndGet()
                        continue
                    }

                    val buffer = buffers[completed]
                    if (buffer == null) {
                        readErrors.incrementAndGet()
                        continue
                    }

                    val bytesRead = buffer.position()
                    lastBytesRead = bytesRead

                    if (bytesRead > 0) {
                        readSuccesses.incrementAndGet()
                        bytesReceived.addAndGet(bytesRead.toLong())
                        val raw = ByteArray(bytesRead)
                        buffer.flip()
                        buffer.get(raw)
                        lastRawHex = "raw=${raw.toHex(raw.size.coerceAtMost(64))}"

                        for (b in raw) pending.add(b)

                        while (pending.size >= GS_HOST_FRAME_SIZE) {
                            val frameBytes = ByteArray(GS_HOST_FRAME_SIZE)
                            for (i in 0 until GS_HOST_FRAME_SIZE) frameBytes[i] = pending.removeAt(0)
                            val frame = parseFrame(frameBytes, 0)
                            if (frame != null) {
                                lastCanFrame = frame
                                totalFrames.incrementAndGet()
                                parseSuccesses.incrementAndGet()
                                framesThisSecond++
                                observeFrame(frame)
                            } else {
                                parseFailures.incrementAndGet()
                            }
                        }
                    }

                    queueFresh(completed)

                    val now = System.currentTimeMillis()
                    if (now - lastFpsTimestamp >= 1000L) {
                        currentFps.set(framesThisSecond)
                        framesThisSecond = 0
                        lastFpsTimestamp = now
                        idStats.values.forEach { it.updateFps() }
                    }
                }
            } catch (e: Throwable) {
                readErrors.incrementAndGet()
                lastRawHex = "Sprint21 RX crash: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                requests.forEach {
                    try { it.cancel() } catch (_: Exception) {}
                    try { it.close() } catch (_: Exception) {}
                }
            }
        }.apply {
            name = "OpenDashX-Sprint21-Smart-Signal-Learner"
            isDaemon = true
            start()
        }
    }

    private fun observeFrame(frame: CanFrame) {
        val key = "${frame.id}:${frame.isExtended}"
        val stat = idStats.getOrPut(key) { MutableFrameStats(frame.id, frame.isExtended) }
        stat.observe(frame)
        recorder?.record(CanLogFrame(timestampMs = System.currentTimeMillis(), id = frame.id, isExtended = frame.isExtended, dlc = frame.data.size, data = frame.data))
    }

    private fun sendHostFormat(conn: UsbDeviceConnection): String {
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(GS_USB_BYTE_ORDER).array()
        val result = conn.controlTransfer(0x41, REQ_HOST_FORMAT, 0, 0, data, data.size, 1000)
        return "HOST_FORMAT req=$REQ_HOST_FORMAT result=$result data=${data.toHex(data.size)}"
    }

    private fun sendBitTiming1Mbps(conn: UsbDeviceConnection): String {
        val data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(135).putInt(34).putInt(1).putInt(1).array()
        val result = conn.controlTransfer(0x41, REQ_BITTIMING, 0, 0, data, data.size, 1000)
        return "BITTIMING 1000000 req=$REQ_BITTIMING result=$result data=${data.toHex(data.size)}"
    }

    private fun sendMode(conn: UsbDeviceConnection, label: String, mode: Int): String {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(mode).putInt(0).array()
        val result = conn.controlTransfer(0x41, REQ_MODE, 0, 0, data, data.size, 1000)
        return "$label MODE=$mode result=$result data=${data.toHex(data.size)}"
    }

    private fun readBtConst(conn: UsbDeviceConnection): String = controlIn(conn, "BT_CONST", REQ_BT_CONST, 40)
    private fun readDeviceConfig(conn: UsbDeviceConnection): String = controlIn(conn, "DEVICE_CONFIG", REQ_DEVICE_CONFIG, 12)
    private fun readTimestamp(conn: UsbDeviceConnection, label: String): String = controlIn(conn, label, REQ_TIMESTAMP, 4)

    private fun controlIn(conn: UsbDeviceConnection, label: String, req: Int, len: Int): String {
        return try {
            val data = ByteArray(len)
            val result = conn.controlTransfer(0xC1, req, 0, 0, data, data.size, 1000)
            "$label req=$req result=$result data=${if (result > 0) data.copyOf(result).toHex(result) else "-"}"
        } catch (e: Throwable) {
            "$label req=$req exception=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun parseFrame(buffer: ByteArray, offset: Int): CanFrame? {
        if (offset + GS_HOST_FRAME_SIZE > buffer.size) return null
        val bb = ByteBuffer.wrap(buffer, offset, GS_HOST_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.int
        val rawCanId = bb.int
        val dlc = bb.get().toInt() and 0x0F
        bb.get(); bb.get(); bb.get()
        val safeDlc = dlc.coerceIn(0, 8)
        val data = ByteArray(safeDlc)
        for (i in 0 until safeDlc) data[i] = bb.get()
        val isExtended = (rawCanId and CAN_EFF_FLAG) != 0
        val isRemote = (rawCanId and CAN_RTR_FLAG) != 0
        val isError = (rawCanId and CAN_ERR_FLAG) != 0
        val cleanId = if (isExtended) rawCanId and CAN_EFF_MASK else rawCanId and CAN_SFF_MASK
        return CanFrame(id = cleanId, data = data, isExtended = isExtended, isRemoteFrame = isRemote, isErrorFrame = isError)
    }

    private fun descriptorOnlyReport(device: UsbDevice): String {
        val lines = mutableListOf<String>()
        lines.add("Descriptor Report")
        lines.add("VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} interfaces=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            lines.add("IF$i id:${intf.id} class:${intf.interfaceClass} subclass:${intf.interfaceSubclass} protocol:${intf.interfaceProtocol} endpoints:${intf.endpointCount}")
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "BULK" else "TYPE${ep.type}"
                lines.add("  EP$e addr:0x${ep.address.toString(16)} $dir $type max:${ep.maxPacketSize}")
            }
        }
        return lines.joinToString("\n")
    }

    private fun resetCounters() {
        totalFrames.set(0); currentFps.set(0)
        readAttempts.set(0); readSuccesses.set(0); readTimeouts.set(0); readErrors.set(0)
        bytesReceived.set(0); parseSuccesses.set(0); parseFailures.set(0)
        lastBytesRead = 0; lastRawHex = "-"; lastCanFrame = null
        idStats.clear()
    }

    private fun ByteArray.toHex(length: Int): String = take(length).joinToString(" ") {
        (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    }

    companion object {
        private const val DEFAULT_CAN_BITRATE = 1_000_000
        private const val REQUEST_COUNT = 16
        private const val GS_HOST_FRAME_SIZE = 20

        private const val REQ_HOST_FORMAT = 0
        private const val REQ_BITTIMING = 1
        private const val REQ_MODE = 2
        private const val REQ_BT_CONST = 4
        private const val REQ_DEVICE_CONFIG = 5
        private const val REQ_TIMESTAMP = 6

        private const val GS_USB_BYTE_ORDER = 0x0000BEEF
        private const val MODE_RESET = 0
        private const val MODE_START = 1

        private const val CAN_EFF_FLAG = -0x80000000
        private const val CAN_RTR_FLAG = 0x40000000
        private const val CAN_ERR_FLAG = 0x20000000
        private const val CAN_SFF_MASK = 0x000007FF
        private const val CAN_EFF_MASK = 0x1FFFFFFF
    }
}
