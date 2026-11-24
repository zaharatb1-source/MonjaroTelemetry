package com.monjaro.telemetry

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class NfsCarCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state = CarState()
    private var roadOffset = 0f
    private var wheelSpin = 0f
    private var lastFrameTime = System.nanoTime()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val roadEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val carBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val carStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0A0E17")
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun updateState(newState: CarState) {
        state = newState
        invalidate()
    }

    private fun getAccentColor(): Int {
        return when (state.driveMode) {
            "SPORT" -> Color.parseColor("#FF2A54")   // Neon Crimson Red
            "ECO" -> Color.parseColor("#00E676")     // Emerald Neon
            "OFFROAD" -> Color.parseColor("#FF9100") // Amber Gold
            else -> Color.parseColor("#00D4FF")      // Cyber Cyan (Comfort / 2nd rest style)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTime = now

        val accent = getAccentColor()
        val speedFactor = (state.speedKmh / 180f).coerceIn(0f, 1.5f)
        roadOffset = (roadOffset + (40f + state.speedKmh * 6.5f) * dt) % 120f
        wheelSpin = (wheelSpin + (state.speedKmh * 12f) * dt) % 360f

        // 1. Deep dark background with radial horizon glow (2nd restyling style)
        val radial = RadialGradient(
            w * 0.5f, h * 0.36f, max(w, h) * 0.65f,
            intArrayOf(
                Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.parseColor("#0B0F19"),
                Color.parseColor("#05070B")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = radial
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Need For Speed Perspective Synthwave / Telemetry Road Grid
        val horizonY = h * 0.28f
        val vanishX = w * 0.5f - (state.steeringAngle * 1.8f)

        gridPaint.color = Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))
        // Perspective longitudinal lines
        val numCols = 10
        for (i in -numCols..numCols) {
            val bottomX = w * 0.5f + i * (w * 0.13f) - (state.steeringAngle * 4.5f)
            val topX = vanishX + i * (w * 0.018f)
            canvas.drawLine(topX, horizonY, bottomX, h, gridPaint)
        }

        // Moving horizontal speed lines (perspective acceleration)
        val numRows = 12
        for (r in 0 until numRows) {
            val progress = ((r * 120f + roadOffset) / (numRows * 120f))
            val eased = progress * progress // perspective compression near horizon
            val y = horizonY + eased * (h - horizonY)
            gridPaint.alpha = (eased * 130).toInt().coerceIn(10, 140)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Glowing road boundaries
        roadEdgePaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            Color.TRANSPARENT, accent, Shader.TileMode.CLAMP
        )
        canvas.drawLine(vanishX - w * 0.06f, horizonY, w * 0.12f - state.steeringAngle * 3f, h, roadEdgePaint)
        canvas.drawLine(vanishX + w * 0.06f, horizonY, w * 0.88f - state.steeringAngle * 3f, h, roadEdgePaint)

        // 3. Speed & Boost Arc Gauges flanking the car (2nd rest + NFS fusion)
        drawSideArcGauge(
            canvas, w * 0.17f, h * 0.52f, min(w, h) * 0.21f,
            state.speedKmh / 240f,
            String.format("%.0f", state.speedKmh),
            "КМ/Ч  •  ${state.gear}",
            accent
        )

        val boostNorm = ((state.boostBar + 0.8f) / 2.6f).coerceIn(0f, 1f)
        drawSideArcGauge(
            canvas, w * 0.83f, h * 0.52f, min(w, h) * 0.21f,
            boostNorm,
            String.format("%+.2f", state.boostBar),
            "BAR  •  ${state.rpm.toInt()} RPM",
            if (state.boostBar > 1.0f) Color.parseColor("#FF2A54") else accent
        )

        // 4. Stylized Geely Monjaro Silhouette (Top-Rear 3/4 Dynamic View)
        canvas.save()
        val carCenterX = w * 0.5f
        val carCenterY = h * 0.56f
        val bodyRoll = (state.steeringAngle * 0.12f).coerceIn(-6f, 6f)
        val squatScale = 1f + (state.throttlePct / 100f) * 0.025f - (state.brakePct / 100f) * 0.025f
        canvas.translate(carCenterX, carCenterY)
        canvas.rotate(bodyRoll)
        canvas.scale(squatScale, squatScale)

        val carW = min(w * 0.24f, 190f)
        val carH = carW * 1.95f
        drawMonjaroCar(canvas, carW, carH, accent, speedFactor)
        canvas.restore()

        // 5. TPMS Tire Pressure & Temp Callouts around the 4 wheels
        drawWheelCallout(canvas, carCenterX - carW * 0.88f, carCenterY - carH * 0.24f, "FL", state.tpmsFL, state.tpmsTempFL, accent, true)
        drawWheelCallout(canvas, carCenterX + carW * 0.88f, carCenterY - carH * 0.24f, "FR", state.tpmsFR, state.tpmsTempFR, accent, false)
        drawWheelCallout(canvas, carCenterX - carW * 0.88f, carCenterY + carH * 0.28f, "RL", state.tpmsRL, state.tpmsTempRL, accent, true)
        drawWheelCallout(canvas, carCenterX + carW * 0.88f, carCenterY + carH * 0.28f, "RR", state.tpmsRR, state.tpmsTempRR, accent, false)

        // 6. Top Drive Mode & Coolant Temp Header Pill
        textPaint.textSize = h * 0.042f
        textPaint.color = accent
        canvas.drawText("MODE: ${state.driveMode}", w * 0.5f, h * 0.09f, textPaint)

        // Speed wind / aero streaks in Sport mode or high speed
        if (state.speedKmh > 40f || state.driveMode == "SPORT") {
            drawAeroStreaks(canvas, carCenterX, carCenterY, carW, carH, accent, speedFactor)
        }
    }

    private fun drawMonjaroCar(canvas: Canvas, cw: Float, ch: Float, accent: Int, speedFactor: Float) {
        // Underglow (NFS style)
        val underglowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = (75 + speedFactor * 90).toInt().coerceIn(50, 180)
            maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
        }
        val glowRect = RectF(-cw * 0.56f, -ch * 0.48f, cw * 0.56f, ch * 0.48f)
        canvas.drawRoundRect(glowRect, 45f, 45f, underglowPaint)

        // 4 Wheels (front wheels turn with steeringAngle)
        val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111622") }
        val wheelRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val wWidth = cw * 0.14f
        val wHeight = ch * 0.17f

        val positions = listOf(
            Triple(-cw * 0.51f, -ch * 0.27f, state.steeringAngle * 0.65f),
            Triple(cw * 0.51f, -ch * 0.27f, state.steeringAngle * 0.65f),
            Triple(-cw * 0.52f, ch * 0.28f, 0f),
            Triple(cw * 0.52f, ch * 0.28f, 0f)
        )
        for ((wx, wy, steer) in positions) {
            canvas.save()
            canvas.translate(wx, wy)
            canvas.rotate(steer)
            val wr = RectF(-wWidth / 2f, -wHeight / 2f, wWidth / 2f, wHeight / 2f)
            canvas.drawRoundRect(wr, 8f, 8f, wheelPaint)
            canvas.drawRoundRect(wr, 8f, 8f, wheelRimPaint)
            canvas.restore()
        }

        // Main SUV Body Silhouette (Geely Monjaro muscular proportions)
        val bodyGrad = LinearGradient(
            -cw * 0.5f, -ch * 0.5f, cw * 0.5f, ch * 0.5f,
            intArrayOf(Color.parseColor("#2A3447"), Color.parseColor("#161D2B"), Color.parseColor("#0E131D")),
            null, Shader.TileMode.CLAMP
        )
        carBodyPaint.shader = bodyGrad
        val bodyRect = RectF(-cw * 0.47f, -ch * 0.48f, cw * 0.47f, ch * 0.48f)
        canvas.drawRoundRect(bodyRect, cw * 0.22f, ch * 0.12f, carBodyPaint)

        carStrokePaint.color = accent
        carStrokePaint.alpha = 200
        canvas.drawRoundRect(bodyRect, cw * 0.22f, ch * 0.12f, carStrokePaint)

        // Hood sculptural lines (Monjaro signature ribs)
        val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(-cw * 0.24f, -ch * 0.43f, -cw * 0.20f, -ch * 0.18f, ribPaint)
        canvas.drawLine(cw * 0.24f, -ch * 0.43f, cw * 0.20f, -ch * 0.18f, ribPaint)

        // Panoramic Sunroof & Windshield
        val windshield = Path().apply {
            moveTo(-cw * 0.34f, -ch * 0.16f)
            lineTo(cw * 0.34f, -ch * 0.16f)
            lineTo(cw * 0.29f, -ch * 0.02f)
            lineTo(-cw * 0.29f, -ch * 0.02f)
            close()
        }
        canvas.drawPath(windshield, glassPaint)

        val panoRoof = RectF(-cw * 0.28f, ch * 0.02f, cw * 0.28f, ch * 0.30f)
        canvas.drawRoundRect(panoRoof, 14f, 14f, glassPaint)
        ribPaint.color = Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(panoRoof, 14f, 14f, ribPaint)

        // Headlights (LED DRLs)
        val drlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(-cw * 0.40f, -ch * 0.45f, -cw * 0.22f, -ch * 0.46f, drlPaint)
        canvas.drawLine(cw * 0.22f, -ch * 0.46f, cw * 0.40f, -ch * 0.45f, drlPaint)

        // Signature Monjaro Full-Width Rear LED Taillight Bar (glows brighter when braking!)
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (state.brakePct > 5f) Color.parseColor("#FF1744") else Color.parseColor("#D50000")
            strokeWidth = if (state.brakePct > 5f) 8f else 5f
            style = Paint.Style.STROKE
            maskFilter = BlurMaskFilter(if (state.brakePct > 5f) 14f else 6f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawLine(-cw * 0.41f, ch * 0.455f, cw * 0.41f, ch * 0.455f, tailPaint)
    }

    private fun drawAeroStreaks(canvas: Canvas, cx: Float, cy: Float, cw: Float, ch: Float, accent: Int, speedFactor: Float) {
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeWidth = 2.5f
            alpha = (80 + speedFactor * 120).toInt().coerceIn(60, 220)
        }
        val offset = (roadOffset * 2.2f) % (ch * 1.3f)
        for (side in listOf(-1f, 1f)) {
            val x1 = cx + side * (cw * 0.65f)
            val y1 = cy - ch * 0.55f + offset
            canvas.drawLine(x1, y1, x1 + side * 6f, y1 + 42f, streakPaint)
        }
    }

    private fun drawWheelCallout(
        canvas: Canvas, x: Float, y: Float, label: String,
        bar: Float, temp: Float, accent: Int, isLeft: Boolean
    ) {
        val warn = bar < 2.0f || bar > 2.8f
        val pColor = if (warn) Color.parseColor("#FF2A54") else Color.WHITE

        textPaint.textSize = 24f
        textPaint.color = pColor
        textPaint.textAlign = if (isLeft) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(String.format("%.1f BAR", bar), x, y, textPaint)

        subTextPaint.textSize = 18f
        subTextPaint.color = accent
        subTextPaint.textAlign = if (isLeft) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText("$label • ${temp.toInt()}°C", x, y + 22f, subTextPaint)

        textPaint.textAlign = Paint.Align.CENTER
        subTextPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawSideArcGauge(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        progress: Float, mainValue: String, subLabel: String, accent: Int
    ) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rect, 135f, 270f * progress.coerceIn(0f, 1f), false, activePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = radius * 0.48f
        canvas.drawText(mainValue, cx, cy + radius * 0.14f, textPaint)

        subTextPaint.color = Color.parseColor("#94A3B8")
        subTextPaint.textSize = radius * 0.16f
        canvas.drawText(subLabel, cx, cy + radius * 0.42f, subTextPaint)
    }
}
