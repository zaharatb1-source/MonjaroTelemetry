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
    var rpm: Float = 800f,
    var gear: String = "P",
    var driveMode: String = "COMFORT",
    var coolantTemp: Float = 88f,
    var transOilTemp: Float = 76f,
    var engineOilTemp: Float = 94f,
    var intakeAirTemp: Float = 28f,
    var ambientTemp: Float = 15f,
    var boostBar: Float = -0.55f,
    var batteryVolt: Float = 14.1f,
    var throttlePct: Float = 0f,
    var brakePct: Float = 0f,
    var steeringAngle: Float = 0f,
    var tpmsFL: Float = 2.4f,
    var tpmsFR: Float = 2.4f,
    var tpmsRL: Float = 2.3f,
    var tpmsRR: Float = 2.3f,
    var tpmsTempFL: Float = 26f,
    var tpmsTempFR: Float = 26f,
    var tpmsTempRL: Float = 25f,
    var tpmsTempRR: Float = 25f,
    var fuelLevelPct: Float = 68f,
    var isDemoMode: Boolean = false
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
