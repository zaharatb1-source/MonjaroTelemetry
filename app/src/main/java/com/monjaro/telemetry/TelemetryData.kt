package com.monjaro.telemetry

import android.content.Context

data class TelemetryItem(
    val id: String,
    val title: String,
    val unit: String,
    var value: String,
    var rawFloat: Float = 0f,
    val minWarn: Float? = null,
    val maxWarn: Float? = null,
    var enabled: Boolean = true
)

data class CarState(
    var speedKmh: Float = 0f,
    var rpm: Float = 0f,
    var gear: String = "P",
    var driveMode: String = "COMFORT",
    var coolantTemp: Float = 0f,
    var transOilTemp: Float = 0f,
    var engineOilTemp: Float = 0f,
    var intakeAirTemp: Float = 0f,
    var ambientTemp: Float = 0f,
    var boostBar: Float = 0f,
    var batteryVolt: Float = 0f,
    var throttlePct: Float = 0f,
    var brakePct: Float = 0f,
    var steeringAngle: Float = 0f,
    var tpmsFL: Float = 0f,
    var tpmsFR: Float = 0f,
    var tpmsRL: Float = 0f,
    var tpmsRR: Float = 0f,
    var tpmsTempFL: Float = 0f,
    var tpmsTempFR: Float = 0f,
    var tpmsTempRL: Float = 0f,
    var tpmsTempRR: Float = 0f,
    var fuelLevelPct: Float = 0f,
    var isDemoMode: Boolean = false,
    var connectionStatus: String = "Поиск Root / VHAL..."
)

object TelemetryPrefs {
    private const val PREF_NAME = "monjaro_telemetry_prefs"

    fun isItemEnabled(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("enabled_$id", true)
    }

    fun setItemEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun isDemoEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("demo_mode", false)
    }

    fun setDemoEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("demo_mode", enabled).apply()
    }
}
