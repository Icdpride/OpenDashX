package com.example.opendashx.core.signals

import com.example.opendashx.core.decoder.DecodedSignal
import java.util.concurrent.ConcurrentHashMap

class SignalStore {
    private val values = ConcurrentHashMap<String, DecodedSignal>()

    fun update(signal: DecodedSignal) {
        values[signal.name.lowercase()] = signal
    }

    fun updateAll(signals: List<DecodedSignal>) {
        for (signal in signals) update(signal)
    }

    fun get(name: String): DecodedSignal? = values[name.lowercase()]

    fun value(name: String): Double? = get(name)?.value

    fun snapshot(): SignalSnapshot {
        return SignalSnapshot(values.values.sortedBy { it.name })
    }

    fun clear() {
        values.clear()
    }
}

data class SignalSnapshot(
    val signals: List<DecodedSignal>,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun value(name: String): Double? {
        return signals.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }
}
