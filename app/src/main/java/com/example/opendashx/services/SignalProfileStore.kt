package com.example.opendashx.services

import android.content.Context
import com.example.opendashx.models.LearnedSignal

class SignalProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("opendashx_signal_profile", Context.MODE_PRIVATE)

    fun save(signals: List<LearnedSignal>) {
        val lines = signals.joinToString("\n") {
            listOf(
                it.signal,
                it.id.toString(),
                it.isExtended.toString(),
                it.expression,
                it.scale,
                "%.3f".format(it.confidence)
            ).joinToString("|")
        }
        prefs.edit().putString("signals_v1", lines).apply()
    }

    fun loadSummary(): String {
        val data = prefs.getString("signals_v1", "") ?: ""
        return if (data.isBlank()) {
            "No saved signal profile yet"
        } else {
            "Saved signal profile:\n" + data.lines().joinToString("\n")
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
