package com.monjaro.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.sin

class CanDataProvider(
    private val context: Context,
    private val onUpdate: (CarState) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = CarState()
    private var running = false
    private var tick = 0

    // Android Car API objects via Reflection (works without compile-time android.car.jar)
    private var carObj: Any? = null
    private var propManager: Any? = null
    private var getIntPropMethod: java.lang.reflect.Method? = null
    private var getFloatPropMethod: java.lang.reflect.Method? = null

    // ECARX AdaptAPI objects via Reflection
    private var ecarxCar: Any? = null
    private var ecarxSensor: Any? = null
    private var getSensorValueMethod: java.lang.reflect.Method? = null

    // Standard Android VehiclePropertyIds
    companion object {
        private const val TAG = "MonjaroCanProvider"
        const val PERF_VEHICLE_SPEED = 0x11600207
        const val ENGINE_RPM = 0x11600305
        const val ENGINE_COOLANT_TEMP = 0x11600301
        const val ENGINE_OIL_TEMP = 0x11600304
        const val GEAR_SELECTION = 0x11400400
        const val CURRENT_GEAR = 0x11400401
        const val ENV_OUTSIDE_TEMPERATURE = 0x11600703
        const val TIRE_PRESSURE = 0x11700309
        const val IGNITION_STATE = 0x11400409
        const val FUEL_LEVEL = 0x11600307
        const val STEERING_ANGLE = 0x11600209
        const val BATTERY_VOLTAGE = 0x1160030A
        // Geely / ECARX extended VHAL property IDs
        const val GEELY_TRANS_OIL_TEMP = 0x21600310
        const val GEELY_TURBO_BOOST = 0x21600312
        const val GEELY_DRIVE_MODE = 0x21400501
        const val GEELY_THROTTLE_POS = 0x21600315
        const val GEELY_BRAKE_POS = 0x21600316
    }

    init {
        initAndroidCarApi()
        initEcarxAdaptApi()
    }

    private fun initAndroidCarApi() {
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            carObj = createCar.invoke(null, context)
            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            propManager = getCarManager.invoke(carObj, "property")

            propManager?.let { pm ->
                val pmClass = pm.javaClass
                getIntPropMethod = pmClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                getFloatPropMethod = pmClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                Log.i(TAG, "Android CarPropertyManager connected successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "CarPropertyManager init fallback: ${e.message}")
        }
    }

    private fun initEcarxAdaptApi() {
        try {
            val glyCarClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val createMethod = glyCarClass.getMethod("create", Context::class.java)
            ecarxCar = createMethod.invoke(null, context)
            ecarxCar?.let { car ->
                val getSensorMgr = car.javaClass.getMethod("getSensorManager")
                ecarxSensor = getSensorMgr.invoke(car)
                ecarxSensor?.let { sm ->
                    getSensorValueMethod = sm.javaClass.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
                    Log.i(TAG, "ECARX AdaptAPI SensorManager connected")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ECARX AdaptAPI fallback: ${e.message}")
        }
    }

    private fun readFloatProp(propId: Int, areaId: Int = 0): Float? {
        try {
            val res = getFloatPropMethod?.invoke(propManager, propId, areaId) as? Float
            if (res != null && !res.isNaN()) return res
        } catch (_: Exception) {}
        try {
            val res = getSensorValueMethod?.invoke(ecarxSensor, propId) as? Float
            if (res != null && !res.isNaN()) return res
        } catch (_: Exception) {}
        return null
    }

    private fun readIntProp(propId: Int, areaId: Int = 0): Int? {
        try {
            return getIntPropMethod?.invoke(propManager, propId, areaId) as? Int
        } catch (_: Exception) {}
        return null
    }

    fun start() {
        if (running) return
        running = true
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick++
            state.isDemoMode = TelemetryPrefs.isDemoEnabled(context)

            if (state.isDemoMode) {
                simulateNeedForSpeedTelemetry(tick)
            } else {
                pollRealHardware()
            }

            onUpdate(state.copy())
            handler.postDelayed(this, 60) // ~16-20 FPS smooth telemetry & car animation
        }
    }

    private fun pollRealHardware() {
        // Speed (m/s -> km/h)
        readFloatProp(PERF_VEHICLE_SPEED)?.let { speedMs ->
            state.speedKmh = if (speedMs > 120f) speedMs else speedMs * 3.6f
        }
        // RPM
        readFloatProp(ENGINE_RPM)?.let { state.rpm = it }
        // Coolant Temp
        readFloatProp(ENGINE_COOLANT_TEMP)?.let { state.coolantTemp = it }
        // Engine Oil Temp
        readFloatProp(ENGINE_OIL_TEMP)?.let { state.engineOilTemp = it }
        // Transmission Oil Temp
        readFloatProp(GEELY_TRANS_OIL_TEMP)?.let { state.transOilTemp = it }
        // Ambient Temp
        readFloatProp(ENV_OUTSIDE_TEMPERATURE)?.let { state.ambientTemp = it }
        // Turbo Boost (kPa -> Bar relative)
        readFloatProp(GEELY_TURBO_BOOST)?.let { kpa ->
            state.boostBar = if (kpa > 10f) (kpa - 101.3f) / 100f else kpa
        }
        // Battery Voltage
        readFloatProp(BATTERY_VOLTAGE)?.let { v ->
            state.batteryVolt = if (v > 100f) v / 1000f else v
        }
        // Steering Angle
        readFloatProp(STEERING_ANGLE)?.let { state.steeringAngle = it }
        // Throttle & Brake
        readFloatProp(GEELY_THROTTLE_POS)?.let { state.throttlePct = it }
        readFloatProp(GEELY_BRAKE_POS)?.let { state.brakePct = it }

        // Tires (Area IDs: FL=1, FR=2, RL=4, RR=8)
        readFloatProp(TIRE_PRESSURE, 1)?.let { state.tpmsFL = normalizeBar(it) }
        readFloatProp(TIRE_PRESSURE, 2)?.let { state.tpmsFR = normalizeBar(it) }
        readFloatProp(TIRE_PRESSURE, 4)?.let { state.tpmsRL = normalizeBar(it) }
        readFloatProp(TIRE_PRESSURE, 8)?.let { state.tpmsRR = normalizeBar(it) }

        // Gear
        readIntProp(CURRENT_GEAR)?.let { g ->
            state.gear = when (g) {
                4 -> "P"
                2 -> "R"
                1 -> "N"
                8 -> "D"
                else -> if (g > 8) "D${g - 8}" else "D"
            }
        }

        // Drive Mode
        readIntProp(GEELY_DRIVE_MODE)?.let { m ->
            state.driveMode = when (m) {
                1 -> "ECO"
                2 -> "SPORT"
                3 -> "OFFROAD"
                else -> "COMFORT"
            }
        }
    }

    private fun normalizeBar(raw: Float): Float {
        return when {
            raw > 100f -> raw / 100f // kPa to Bar
            raw > 15f -> raw * 0.06895f // PSI to Bar
            else -> raw
        }
    }

    private fun simulateNeedForSpeedTelemetry(t: Int) {
        val phase = t * 0.04
        val accelCycle = (sin(phase) + 1.0) / 2.0 // 0..1
        state.speedKmh = (25 + accelCycle * 155).toFloat()
        state.rpm = (1400 + ((state.speedKmh % 38f) / 38f) * 4800f)
        state.gear = when {
            state.speedKmh < 35 -> "D1"
            state.speedKmh < 65 -> "D2"
            state.speedKmh < 95 -> "D3"
            state.speedKmh < 130 -> "D4"
            state.speedKmh < 160 -> "D5"
            else -> "D6"
        }
        state.boostBar = (-0.4 + accelCycle * 1.65).toFloat()
        state.throttlePct = (accelCycle * 100).toFloat()
        state.brakePct = if (cos(phase) < -0.5) ((-cos(phase) - 0.5) * 160).toFloat() else 0f
        state.steeringAngle = (sin(phase * 0.7) * 28.0).toFloat()
        state.coolantTemp = (89 + sin(phase * 0.1) * 4).toFloat()
        state.transOilTemp = (82 + sin(phase * 0.08) * 6).toFloat()
        state.engineOilTemp = (98 + sin(phase * 0.09) * 7).toFloat()
        state.intakeAirTemp = (31 + accelCycle * 9).toFloat()
        state.batteryVolt = 14.3f
        state.tpmsFL = 2.4f
        state.tpmsFR = 2.4f
        state.tpmsRL = 2.3f
        state.tpmsRR = 2.3f
        state.driveMode = when ((t / 180) % 4) {
            0 -> "SPORT"
            1 -> "COMFORT"
            2 -> "ECO"
            else -> "OFFROAD"
        }
    }
}
