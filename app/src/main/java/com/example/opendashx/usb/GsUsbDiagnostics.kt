package com.example.opendashx.usb

import com.example.opendashx.models.LearnedSignal

data class GsUsbDiagnostics(
    val readAttempts: Long = 0,
    val readSuccesses: Long = 0,
    val readTimeouts: Long = 0,
    val readErrors: Long = 0,
    val bytesReceived: Long = 0,
    val parseSuccesses: Long = 0,
    val parseFailures: Long = 0,
    val lastBytesRead: Int = 0,
    val lastRawHex: String = "-",
    val endpointReport: String = "-",
    val receiveMode: String = "Smart Signal Learner",
    val uniqueIdCount: Int = 0,
    val learnedSignals: List<LearnedSignal> = emptyList(),
    val rpmCandidates: List<LearnedSignal> = emptyList(),
    val tpsCandidates: List<LearnedSignal> = emptyList(),
    val mapCandidates: List<LearnedSignal> = emptyList(),
    val batteryCandidates: List<LearnedSignal> = emptyList(),
    val afrCandidates: List<LearnedSignal> = emptyList(),
    val coolantCandidates: List<LearnedSignal> = emptyList()
)
