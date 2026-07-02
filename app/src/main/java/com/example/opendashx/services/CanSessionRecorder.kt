package com.example.opendashx.services

import android.content.Context
import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.RecorderSaveResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CanSessionRecorder(private val context: Context) {
    private val recording = AtomicBoolean(false)
    private val frames = ConcurrentLinkedQueue<CanLogFrame>()
    private val count = AtomicLong(0)
    private val stepIndex = AtomicLong(0)
    private var lastSaveResult: RecorderSaveResult = emptyResult("No save attempted yet")

    private val steps = listOf(
        "Idle for 5 seconds",
        "Blip throttle 2-3 times",
        "Hold around 2000 RPM",
        "Return to idle",
        "Optional: light throttle",
        "Stop and save"
    )

    fun start() {
        frames.clear()
        count.set(0)
        stepIndex.set(0)
        recording.set(true)
        lastSaveResult = emptyResult("Recording started. No save attempted yet.")
    }

    fun record(frame: CanLogFrame) {
        if (!recording.get()) return
        frames.add(frame)
        count.incrementAndGet()
    }

    fun stop(): File? {
        val result = stopWithDiagnostics()
        return result.file
    }

    fun stopWithDiagnostics(): RecorderSaveResult {
        recording.set(false)

        val snapshot = frames.toList()
        val dir = logDirectory()

        try {
            if (!dir.exists()) {
                val made = dir.mkdirs()
                if (!made && !dir.exists()) {
                    lastSaveResult = RecorderSaveResult(
                        ok = false,
                        file = null,
                        message = "Save failed: could not create log directory.",
                        directory = dir.absolutePath,
                        fileName = "-",
                        framesWritten = 0,
                        fileSizeBytes = 0,
                        exception = "mkdirs() returned false"
                    )
                    return lastSaveResult
                }
            }

            if (!dir.canWrite()) {
                lastSaveResult = RecorderSaveResult(
                    ok = false,
                    file = null,
                    message = "Save failed: log directory is not writable.",
                    directory = dir.absolutePath,
                    fileName = "-",
                    framesWritten = 0,
                    fileSizeBytes = 0,
                    exception = "dir.canWrite() == false"
                )
                return lastSaveResult
            }

            if (snapshot.isEmpty()) {
                lastSaveResult = RecorderSaveResult(
                    ok = false,
                    file = null,
                    message = "Save failed: no frames were recorded. Press Start Recording before Stop + Save.",
                    directory = dir.absolutePath,
                    fileName = "-",
                    framesWritten = 0,
                    fileSizeBytes = 0,
                    exception = null
                )
                return lastSaveResult
            }

            val fileName = "opendashx_canlog_${timestamp()}.csv"
            val file = File(dir, fileName)

            file.bufferedWriter().use { out ->
                out.write("timestampMs,id,isExtended,dlc,dataHex\n")
                for (frame in snapshot) {
                    val dataHex = frame.data.joinToString(" ") {
                        (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    }
                    out.write("${frame.timestampMs},${frame.id},${frame.isExtended},${frame.data.size},$dataHex\n")
                }
            }

            lastSaveResult = RecorderSaveResult(
                ok = file.exists() && file.length() > 0,
                file = file,
                message = "Saved ${snapshot.size} frames to ${file.name}",
                directory = dir.absolutePath,
                fileName = file.name,
                framesWritten = snapshot.size.toLong(),
                fileSizeBytes = file.length(),
                exception = null
            )
            return lastSaveResult
        } catch (t: Throwable) {
            lastSaveResult = RecorderSaveResult(
                ok = false,
                file = null,
                message = "Save failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                directory = dir.absolutePath,
                fileName = "-",
                framesWritten = snapshot.size.toLong(),
                fileSizeBytes = 0,
                exception = t.stackTraceToString().take(3000)
            )
            return lastSaveResult
        }
    }

    fun writeSaveTest(): RecorderSaveResult {
        val dir = logDirectory()
        return try {
            if (!dir.exists()) dir.mkdirs()
            val fileName = "opendashx_save_test_${timestamp()}.csv"
            val file = File(dir, fileName)
            file.writeText("timestampMs,id,isExtended,dlc,dataHex\n${System.currentTimeMillis()},123,true,8,01 02 03 04 05 06 07 08\n")
            RecorderSaveResult(
                ok = file.exists() && file.length() > 0,
                file = file,
                message = "Save test OK: ${file.name}",
                directory = dir.absolutePath,
                fileName = file.name,
                framesWritten = 1,
                fileSizeBytes = file.length(),
                exception = null
            )
        } catch (t: Throwable) {
            RecorderSaveResult(
                ok = false,
                file = null,
                message = "Save test failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                directory = dir.absolutePath,
                fileName = "-",
                framesWritten = 0,
                fileSizeBytes = 0,
                exception = t.stackTraceToString().take(3000)
            )
        }
    }

    fun nextStep() {
        val next = (stepIndex.get() + 1).coerceAtMost((steps.size - 1).toLong())
        stepIndex.set(next)
    }

    fun currentStep(): String {
        return steps[stepIndex.get().toInt().coerceIn(0, steps.size - 1)]
    }

    fun isRecording(): Boolean = recording.get()
    fun count(): Long = count.get()
    fun lastSaveResult(): RecorderSaveResult = lastSaveResult
    fun logDirectory(): File = File(context.getExternalFilesDir(null), "OpenDashXLogs")

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun emptyResult(message: String): RecorderSaveResult {
        val dir = logDirectory()
        return RecorderSaveResult(false, null, message, dir.absolutePath, "-", 0, 0, null)
    }
}
