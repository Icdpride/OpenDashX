package com.example.opendashx.core.dashboard

import com.example.opendashx.core.signals.SignalSnapshot

data class DashboardState(
    val rpm: Double? = null,
    val tps: Double? = null,
    val mapKpa: Double? = null,
    val coolantF: Double? = null,
    val afr: Double? = null,
    val batteryV: Double? = null,
    val oilPressurePsi: Double? = null,
    val fuelPressurePsi: Double? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSnapshot(snapshot: SignalSnapshot, previous: DashboardState = DashboardState()): DashboardState {
            return previous.copy(
                rpm = snapshot.value("RPM") ?: previous.rpm,
                tps = snapshot.value("TPS") ?: previous.tps,
                mapKpa = snapshot.value("MAP") ?: previous.mapKpa,
                coolantF = snapshot.value("Coolant") ?: previous.coolantF,
                afr = snapshot.value("AFR") ?: previous.afr,
                batteryV = snapshot.value("Battery") ?: previous.batteryV,
                oilPressurePsi = snapshot.value("Oil Pressure") ?: previous.oilPressurePsi,
                fuelPressurePsi = snapshot.value("Fuel Pressure") ?: previous.fuelPressurePsi,
                updatedAtMs = snapshot.createdAtMs
            )
        }
    }
}
