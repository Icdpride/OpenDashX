package com.example.opendashx.services

import android.content.Context
import com.example.opendashx.models.CanLogFrame
import com.example.opendashx.models.ReplayProgress
import com.example.opendashx.models.ReplayStats
import java.io.File

class CanLogReplay(private val context: Context) {
    private var frames: List<CanLogFrame> = emptyList()
    private var lastFile: File? = null

    fun findMostRecentLog(): File? {
        val dir = File(context.getExternalFilesDir(null), "OpenDashXLogs")
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
            ?: return null

        // Prefer real CAN logs over small Save Test files.
        return files
            .filter { it.name.startsWith("opendashx_canlog_", ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
            ?: files.maxByOrNull { it.lastModified() }
    }

    fun loadMostRecent(
        onProgress: (ReplayProgress) -> Unit = {}
    ): ReplayStats {
        val file = findMostRecentLog()
        if (file == null) {
            frames = emptyList()
            lastFile = null
            return ReplayStats(status = "No CSV logs found")
        }
        return load(file, maxFrames, onProgress)
    }

    fun load(
        file: File,
        onProgress: (ReplayProgress) -> Unit = {}
    ): ReplayStats {
        return try {
            lastFile = file
            frames = parseBounded(file, maxFrames, onProgress)
            stats("Loaded ${frames.size} frames from ${file.name}")
        } catch (e: Throwable) {
            frames = emptyList()
            ReplayStats(
                loaded = false,
                fileName = file.name,
                status = "Load failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun getFrames(): List<CanLogFrame> = frames

    fun stats(status: String = "Ready"): ReplayStats {
        val duration = if (frames.isEmpty()) 0L else {
            (frames.last().timestampMs - frames.first().timestampMs).coerceAtLeast(0L)
        }
        return ReplayStats(
            loaded = frames.isNotEmpty(),
            fileName = lastFile?.name ?: "-",
            frameCount = frames.size,
            idCount = frames.map { "${it.id}:${it.isExtended}" }.toSet().size,
            durationMs = duration,
            status = status
        )
    }

    private fun parseBounded(
        file: File,
        maxFrames: Int,
        onProgress: (ReplayProgress) -> Unit
    ): List<CanLogFrame> {
        val estimatedLines = estimateLines(file)
        var lineCount = 0
        var parsedCount = 0
        var rejectedCount = 0
        var header: List<String> = emptyList()

        onProgress(
            ReplayProgress(
                busy = true,
                phase = "Loading",
                fileName = file.name,
                totalLinesEstimate = estimatedLines,
                message = "Opening ${file.name}"
            )
        )

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@forEach

                if (lineCount == 0 && line.contains("timestamp", ignoreCase = true)) {
                    header = splitCsv(line).map { it.trim() }
                    lineCount++
                    return@forEach
                }

                lineCount++

                val parsed = parseAnyFrameLine(line, header)
                if (parsed != null) {
                        out.add(parsed)
                        parsedCount++
                } else {
                    rejectedCount++
                }

                if (lineCount % 5000 == 0) {
                    val pct = if (estimatedLines > 0) ((lineCount * 100L) / estimatedLines).toInt().coerceIn(0, 99) else 0
                    onProgress(
                        ReplayProgress(
                            busy = true,
                            phase = "Loading",
                            fileName = file.name,
                            framesProcessed = parsedCount,
                            totalLinesEstimate = estimatedLines,
                            percent = pct,
                        )
                    )
                }
            }
        }

        onProgress(
            ReplayProgress(
                busy = false,
                phase = "Loaded",
                fileName = file.name,
                framesProcessed = out.size,
                totalLinesEstimate = estimatedLines,
                percent = 100,
            )
        )
        return out
    }

    private fun estimateLines(file: File): Int {
        val bytes = file.length().coerceAtLeast(1L)
        return (bytes / 55L).toInt().coerceAtLeast(1)
    }

    private fun parseAnyFrameLine(line: String, header: List<String>): CanLogFrame? {
        return when {
            line.startsWith("FRAME,") -> parseLegacyFrameLine(line)
            header.any { it.equals("dataHex", ignoreCase = true) } -> parseCurrentRecorderLine(line, header)
            else -> parseCurrentRecorderLine(line, emptyList())
        }
    }

    // Current recorder format:
    // timestampMs,id,isExtended,dlc,dataHex
    // 1712345678,503338941,true,8,44 61 80 C6 00 00 00 21
    private fun parseCurrentRecorderLine(line: String, header: List<String>): CanLogFrame? {
        val parts = splitCsv(line)
        if (parts.size < 5) return null

        fun indexOf(name: String, fallback: Int): Int {
            val idx = header.indexOfFirst { it.equals(name, ignoreCase = true) }
            return if (idx >= 0) idx else fallback
        }

        val ts = parts.getOrNull(indexOf("timestampMs", 0))?.trim()?.toLongOrNull() ?: return null
        val idText = parts.getOrNull(indexOf("id", 1))?.trim() ?: return null
        val id = parseId(idText) ?: return null
        val ext = parseBool(parts.getOrNull(indexOf("isExtended", 2))?.trim())
        val dlc = parts.getOrNull(indexOf("dlc", 3))?.trim()?.toIntOrNull()?.coerceIn(0, 8) ?: 8
        val dataText = parts.getOrNull(indexOf("dataHex", 4))?.trim().orEmpty()
        val data = parseDataHex(dataText, dlc)
        return CanLogFrame(ts, id, ext, dlc, data)
    }

    // Older sprint diagnostic/replay format beginning with FRAME,...
    private fun parseLegacyFrameLine(line: String): CanLogFrame? {
        val parts = splitCsv(line)
        if (parts.size < 14) return null
        val ts = parts[1].toLongOrNull() ?: return null
        val id = parseId(parts[3].trim()) ?: return null
        val ext = parseBool(parts.getOrNull(4)?.trim())
        val dlc = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 8) ?: 8
        val data = ByteArray(8)
        for (i in 0 until 8) {
            val v = parts.getOrNull(6 + i)?.toIntOrNull() ?: 0
            data[i] = (v and 0xFF).toByte()
        }
        return CanLogFrame(ts, id, ext, dlc, data)
    }

    private fun parseId(text: String): Int? {
        val clean = text.removePrefix("0x").removePrefix("0X")
        return if (text.startsWith("0x", true)) clean.toUIntOrNull(16)?.toInt()
        else text.toIntOrNull() ?: clean.toUIntOrNull(16)?.toInt()
    }

    private fun parseBool(text: String?): Boolean {
        return when (text?.lowercase()) {
            "true", "1", "y", "yes" -> true
            "false", "0", "n", "no" -> false
            else -> true
        }
    }

    private fun parseDataHex(text: String, dlc: Int): ByteArray {
        val data = ByteArray(8)
        val tokens = text
            .replace("0x", "", ignoreCase = true)
            .replace(";", " ")
            .replace("|", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        for (i in 0 until dlc.coerceIn(0, 8)) {
            val token = tokens.getOrNull(i) ?: "00"
            val value = token.toIntOrNull(16) ?: token.toIntOrNull() ?: 0
            data[i] = (value and 0xFF).toByte()
        }
        return data
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }
}
