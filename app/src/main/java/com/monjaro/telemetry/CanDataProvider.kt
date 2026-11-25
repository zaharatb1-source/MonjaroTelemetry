package com.monjaro.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.cos
import kotlin.math.sin

class CanDataProvider(
    private val context: Context,
    private val onUpdate: (CarState) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val state = CarState()
    @Volatile private var running = false
    private var tick = 0

    @Volatile var lastRawVhalDump: String = "Ожидание опроса через Root..."

    private var suProcess: Process? = null
    private var suOut: DataOutputStream? = null
    private var suIn: BufferedReader? = null
    private var rootGranted = false

    companion object {
        private const val TAG = "MonjaroRootVhal"
        private val PROP_LINE_PATTERN = Pattern.compile(
            "(?:propId|property|id)\\s*[:=]\\s*(0x[0-9a-fA-F]+|\\d+).*?(?:floatValues|int32Values|values|value)\\s*[:=]\\s*\\[?([+-]?[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE
        )
        private val NAMED_PROP_PATTERN = Pattern.compile(
            "([A-Z0-9_]{6,})\\s*[:=]\\s*\\[?([+-]?[0-9]+(?:\\.[0-9]+)?)"
        )
    }

    fun start() {
        if (running) return
        running = true
        ioExecutor.execute {
            initRootShell()
            while (running) {
                tick++
                state.isDemoMode = TelemetryPrefs.isDemoEnabled(context)
                if (state.isDemoMode) {
                    simulateNeedForSpeedTelemetry(tick)
                    state.connectionStatus = "ДЕМО-РЕЖИМ"
                } else {
                    pollViaRootShell()
                }
                val snapshot = state.copy()
                mainHandler.post { onUpdate(snapshot) }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            suOut?.writeBytes("exit\n")
            suOut?.flush()
            suProcess?.destroy()
        } catch (_: Exception) {}
        suProcess = null
        suOut = null
        suIn = null
    }

    private fun initRootShell() {
        try {
            val p = Runtime.getRuntime().exec("su")
            val out = DataOutputStream(p.outputStream)
            val inp = BufferedReader(InputStreamReader(p.inputStream))
            out.writeBytes("id\n")
            out.flush()
            val idLine = inp.readLine() ?: ""
            if (idLine.contains("uid=0")) {
                rootGranted = true
                suProcess = p
                suOut = out
                suIn = inp
                state.connectionStatus = "ROOT CAN: АКТИВЕН"

                val pkg = context.packageName
                val perms = listOf(
                    "android.car.permission.CAR_INFO",
                    "android.car.permission.CAR_SPEED",
                    "android.car.permission.CAR_ENGINE_DETAILED",
                    "android.car.permission.CAR_TIRES",
                    "android.car.permission.CAR_POWERTRAIN",
                    "android.car.permission.READ_CAR_DISPLAY_UNITS",
                    "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
                    "android.car.permission.CAR_ENERGY",
                    "android.car.permission.CAR_DYNAMICS_STATE"
                )
                for (perm in perms) {
                    out.writeBytes("pm grant \$pkg \$perm 2>/dev/null\n")
                }
                out.flush()
            } else {
                state.connectionStatus = "НЕТ ROOT ДОСТУПА"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root init error: \${e.message}")
            state.connectionStatus = "ОШИБКА ROOT: \${e.message}"
        }
    }

    private fun execSuCommand(cmd: String): String {
        val out = suOut ?: return ""
        val inp = suIn ?: return ""
        val marker = "___END_VHAL_DUMP___"
        return try {
            out.writeBytes("\$cmd\necho \$marker\n")
            out.flush()
            val sb = StringBuilder()
            var line: String?
            while (inp.readLine().also { line = it } != null) {
                if (line == marker) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "su exec failed: \${e.message}")
            ""
        }
    }

    private fun pollViaRootShell() {
        if (!rootGranted) {
            initRootShell()
            return
        }

        val dumpAosp = execSuCommand("dumpsys car_service --services CarPropertyService 2>/dev/null | grep -iE 'propId|property|0x11|0x21|COOLANT|SPEED|RPM|TIRE|GEAR|BATTERY|BOOST|OIL|PEDAL|STEER|DRVMOD'")
        val dumpEcarx = execSuCommand("dumpsys activity service com.ecarx.car 2>/dev/null | grep -iE 'VEH_|CLUSTER_|0x21|0x11|COOLT|BOOST|TIRE|BATT|GEAR|DRVMOD'")

        val combined = (dumpAosp + "\n" + dumpEcarx).trim()
        if (combined.isNotEmpty()) {
            lastRawVhalDump = combined.take(8000)
            state.connectionStatus = "CAN ШИНА: ОНЛАЙН"
            parseVhalText(combined)
        } else {
            val fallback = execSuCommand("dumpsys car_service 2>/dev/null | head -n 250")
            lastRawVhalDump = if (fallback.isNotEmpty()) fallback else "Пустой ответ от car_service"
            state.connectionStatus = "ПОИСК СВОЙСТВ CAN..."
            parseVhalText(fallback)
        }
    }

    private fun parseVhalText(text: String) {
        val lines = text.split('\n')
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val m1 = PROP_LINE_PATTERN.matcher(line)
            if (m1.find()) {
                val idToken = m1.group(1) ?: continue
                val valToken = m1.group(2) ?: continue
                val propId = parsePropId(idToken)
                val v = valToken.toFloatOrNull() ?: continue
                applyPropertyById(propId, line, v)
            }

            val m2 = NAMED_PROP_PATTERN.matcher(line)
            while (m2.find()) {
                val name = m2.group(1) ?: continue
                val v = m2.group(2)?.toFloatOrNull() ?: continue
                applyPropertyByName(name, v)
            }
        }
    }

    private fun parsePropId(token: String): Int {
        return try {
            if (token.startsWith("0x", ignoreCase = true)) {
                java.lang.Long.decode(token).toInt()
            } else {
                token.toInt()
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun applyPropertyById(propId: Int, line: String, v: Float) {
        when (propId) {
            0x11600207, 0x11600208 -> {
                state.speedKmh = if (v > 120f) v else v * 3.6f
            }
            0x11600305 -> state.rpm = v
            0x11600301 -> state.coolantTemp = v
            0x11600304 -> state.engineOilTemp = v
            0x11600703 -> state.ambientTemp = v
            0x11600209 -> state.steeringAngle = v
            0x1160030A -> state.batteryVolt = if (v > 100f) v / 1000f else v
            0x11600307 -> {
                state.fuelLevelPct = if (v > 100f) (v / 55000f * 100f).coerceIn(0f, 100f) else v
            }
            0x11400400, 0x11400401 -> {
                state.gear = decodeAospGear(v.toInt())
            }
            0x11700309 -> {
                val bar = normalizeBar(v)
                when {
                    line.contains("0x1") || line.contains("areaId: 1") || line.contains("areaId=1") -> state.tpmsFL = bar
                    line.contains("0x2") || line.contains("areaId: 2") || line.contains("areaId=2") -> state.tpmsFR = bar
                    line.contains("0x4") || line.contains("areaId: 4") || line.contains("areaId=4") -> state.tpmsRL = bar
                    line.contains("0x8") || line.contains("areaId: 8") || line.contains("areaId=8") -> state.tpmsRR = bar
                }
            }
        }
    }

    private fun applyPropertyByName(name: String, v: Float) {
        when {
            name.contains("COOLT") || name.contains("COOLANT") -> {
                if (v in -40f..160f) state.coolantTemp = v
            }
            name.contains("ENGINE_OIL_TEMP") || name.contains("ENGOILT") -> {
                if (v in -40f..180f) state.engineOilTemp = v
            }
            name.contains("TRANSOILT") || name.contains("TRSMOILT") -> {
                if (v in -40f..180f) state.transOilTemp = v
            }
            name.contains("BOOSTACTP") || name.contains("BOOST_PRESSURE") -> {
                state.boostBar = if (v > 10f) (v - 101.3f) / 100f else v
            }
            name.contains("VEHBATTUSYSU") || name.contains("MODULE_VOLTAGE") -> {
                state.batteryVolt = if (v > 100f) v / 1000f else v
            }
            name.contains("ENGNSAFEENGN") || name.contains("ENGINE_RPM") -> {
                if (v in 0f..9000f) state.rpm = v
            }
            name.contains("VEHSPD") || name.contains("VEHICLE_SPEED") -> {
                state.speedKmh = if (v > 120f) v else v * 3.6f
            }
            name.contains("ACCRPEDLRAT") || name.contains("ACCELERATOR_PEDAL") -> {
                state.throttlePct = v.coerceIn(0f, 100f)
            }
            name.contains("BRKPEDLRRAT") -> {
                state.brakePct = v.coerceIn(0f, 100f)
            }
            name.contains("PINIONSTEERAG") || name.contains("STEERING_ANGLE") -> {
                state.steeringAngle = v
            }
            name.contains("LEFRNTTIREMSGP") -> state.tpmsFL = normalizeBar(v)
            name.contains("RIFRNTTIREMSGP") -> state.tpmsFR = normalizeBar(v)
            name.contains("LERETIREMSGP") -> state.tpmsRL = normalizeBar(v)
            name.contains("RIRETIREMSGP") -> state.tpmsRR = normalizeBar(v)
            name.contains("LEFRNTTIREMSGT") -> state.tpmsTempFL = v
            name.contains("RIFRNTTIREMSGT") -> state.tpmsTempFR = v
            name.contains("LERETIREMSGT") -> state.tpmsTempRL = v
            name.contains("RIRETIREMSGT") -> state.tpmsTempRR = v
            name.contains("AMBTESTIMD") || name.contains("OUTSIDE_TEMP") -> state.ambientTemp = v
            name.contains("DRVMODDISPD") || name.contains("PTMODACT") -> {
                state.driveMode = when (v.toInt()) {
                    1 -> "ECO"
                    2 -> "SPORT"
                    3, 4 -> "OFFROAD"
                    else -> "COMFORT"
                }
            }
            name.contains("GEARINDCN") || name.contains("PTGEARACT") -> {
                state.gear = when (v.toInt()) {
                    0, 4 -> "P"
                    1, 2 -> "R"
                    3 -> "N"
                    else -> "D"
                }
            }
        }
    }

    private fun decodeAospGear(g: Int): String = when (g) {
        4 -> "P"
        2 -> "R"
        1 -> "N"
        8 -> "D"
        else -> if (g > 8) "D\${g - 8}" else "P"
    }

    private fun normalizeBar(raw: Float): Float = when {
        raw > 100f -> raw / 100f
        raw > 15f -> raw * 0.06895f
        else -> raw
    }

    private fun simulateNeedForSpeedTelemetry(t: Int) {
        val phase = t * 0.08
        val accelCycle = (sin(phase) + 1.0) / 2.0
        state.speedKmh = (25 + accelCycle * 160).toFloat()
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
        state.steeringAngle = (sin(phase * 0.7) * 32.0).toFloat()
        state.coolantTemp = (89 + sin(phase * 0.1) * 4).toFloat()
        state.transOilTemp = (82 + sin(phase * 0.08) * 6).toFloat()
        state.engineOilTemp = (98 + sin(phase * 0.09) * 7).toFloat()
        state.intakeAirTemp = (31 + accelCycle * 9).toFloat()
        state.ambientTemp = 15f
        state.batteryVolt = 14.3f
        state.tpmsFL = 2.4f
        state.tpmsFR = 2.4f
        state.tpmsRL = 2.3f
        state.tpmsRR = 2.3f
        state.tpmsTempFL = 28f
        state.tpmsTempFR = 28f
        state.tpmsTempRL = 27f
        state.tpmsTempRR = 27f
        state.fuelLevelPct = 72f
        state.driveMode = when ((t / 80) % 4) {
            0 -> "SPORT"
            1 -> "COMFORT"
            2 -> "ECO"
            else -> "OFFROAD"
        }
    }
}
