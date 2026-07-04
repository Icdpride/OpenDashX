package com.example.opendashx.core.plugins

class PluginRegistry(
    private val plugins: List<EcuPlugin> = listOf(HolleyTerminatorPlugin())
) {
    fun all(): List<EcuPlugin> = plugins

    fun autoDetect(deviceName: String?): EcuPlugin? {
        return plugins.firstOrNull { it.canAutoDetect(deviceName) }
    }
}
