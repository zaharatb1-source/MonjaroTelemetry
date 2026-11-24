package com.monjaro.telemetry

import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class Monjaro3DSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val glView: GLSurfaceView
    private val renderer: Monjaro3DRenderer
    private val hudOverlay: TelemetryHudOverlayView

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            renderer = Monjaro3DRenderer()
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        hudOverlay = TelemetryHudOverlayView(context)
        addView(hudOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun onHostResume() {
        glView.onResume()
    }

    fun onHostPause() {
        glView.onPause()
    }

    fun updateState(state: CarState) {
        renderer.carState = state
        hudOverlay.updateState(state)
    }

    fun resetCamera() {
        renderer.autoOrbit = true
        renderer.userYaw = 28f
        renderer.userPitch = 18f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                renderer.autoOrbit = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                renderer.userYaw = (renderer.userYaw + dx * 0.45f) % 360f
                renderer.userPitch = (renderer.userPitch + dy * 0.25f).coerceIn(6f, 65f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private class TelemetryHudOverlayView(context: Context) : android.view.View(context) {
        private var state = CarState()
        private val arcRect = RectF()
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#991E293B")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }
        private val pFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }

        fun updateState(s: CarState) {
            state = s
            invalidate()
        }

        private fun accent(): Int = when (state.driveMode) {
            "SPORT" -> Color.parseColor("#FF2A54")
            "ECO" -> Color.parseColor("#00E676")
            "OFFROAD" -> Color.parseColor("#FF9100")
            else -> Color.parseColor("#00D4FF")
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val acc = accent()

            textPaint.textSize = h * 0.042f
            textPaint.color = acc
            canvas.drawText("3D MONJARO  •  ${state.driveMode}", w * 0.5f, h * 0.08f, textPaint)

            subPaint.textSize = h * 0.022f
            subPaint.color = Color.parseColor("#64748B")
            canvas.drawText("Свайп по машине — вращение 360°", w * 0.5f, h * 0.12f, subPaint)

            drawArcGauge(
                canvas, w * 0.16f, h * 0.80f, min(w, h) * 0.16f,
                state.speedKmh / 240f, "${state.speedKmh.toInt()}", "КМ/Ч • ${state.gear}", acc
            )
            drawArcGauge(
                canvas, w * 0.84f, h * 0.80f, min(w, h) * 0.16f,
                ((state.boostBar + 0.8f) / 2.6f).coerceIn(0f, 1f),
                String.format(Locale.US, "%+.2f", state.boostBar), "BAR • ${state.rpm.toInt()} RPM",
                if (state.boostBar > 1.0f) Color.parseColor("#FF2A54") else acc
            )

            textPaint.textSize = 21f
            textPaint.color = Color.WHITE
            val tpmsStr = String.format(
                Locale.US,
                "FL %.1f  |  FR %.1f  |  RL %.1f  |  RR %.1f BAR",
                state.tpmsFL, state.tpmsFR, state.tpmsRL, state.tpmsRR
            )
            canvas.drawText(tpmsStr, w * 0.5f, h * 0.93f, textPaint)
        }

        private fun drawArcGauge(
            canvas: Canvas, cx: Float, cy: Float, radius: Float,
            progress: Float, value: String, label: String, acc: Int
        ) {
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arcRect, 135f, 270f, false, pBg)

            pFg.color = acc
            canvas.drawArc(arcRect, 135f, 270f * progress.coerceIn(0f, 1f), false, pFg)

            textPaint.color = Color.WHITE
            textPaint.textSize = radius * 0.48f
            canvas.drawText(value, cx, cy + radius * 0.14f, textPaint)

            subPaint.color = Color.parseColor("#CBD5E1")
            subPaint.textSize = radius * 0.17f
            canvas.drawText(label, cx, cy + radius * 0.44f, subPaint)
        }
    }

    private class Monjaro3DRenderer : GLSurfaceView.Renderer {
        @Volatile var carState = CarState()
        @Volatile var autoOrbit = true
        @Volatile var userYaw = 32f
        @Volatile var userPitch = 18f

        private var program = 0
        private var posHandle = 0
        private var normHandle = 0
        private var mvpHandle = 0
        private var modelHandle = 0
        private var colorHandle = 0
        private var lightDirHandle = 0
        private var metallicHandle = 0

        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        private var wheelSpinAngle = 0f
        private var roadScroll = 0f
        private var lastTimeNs = System.nanoTime()

        private lateinit var cubeBuffer: FloatBuffer
        private lateinit var wheelBuffer: FloatBuffer
        private var wheelVertexCount = 0
        private lateinit var roadGridBuffer: FloatBuffer
        private var roadLineCount = 0

        private val vertexShaderCode = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vWorldPos = vec3(uModelMatrix * vec4(aPosition, 1.0));
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uLightDir;
            uniform float uMetallic;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                float diff = max(dot(n, l), 0.18);
                vec3 viewDir = normalize(vec3(0.0, 2.5, 5.0) - vWorldPos);
                vec3 halfDir = normalize(l + viewDir);
                float spec = pow(max(dot(n, halfDir), 0.0), 32.0) * uMetallic;
                float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0) * 0.35 * uMetallic;
                vec3 finalRgb = uColor.rgb * diff + vec3(spec + rim);
                gl_FragColor = vec4(finalRgb, uColor.a);
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.024f, 0.032f, 0.052f, 1.0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }
            posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            normHandle = GLES20.glGetAttribLocation(program, "aNormal")
            mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            modelHandle = GLES20.glGetUniformLocation(program, "uModelMatrix")
            colorHandle = GLES20.glGetUniformLocation(program, "uColor")
            lightDirHandle = GLES20.glGetUniformLocation(program, "uLightDir")
            metallicHandle = GLES20.glGetUniformLocation(program, "uMetallic")

            buildUnitCubeWithNormals()
            build3DWheelCylinder(24)
            buildRoadGrid()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / max(1, height).toFloat()
            Matrix.perspectiveM(projMatrix, 0, 42f, ratio, 0.2f, 60f)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = ((now - lastTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastTimeNs = now

            val st = carState
            wheelSpinAngle = (wheelSpinAngle + st.speedKmh * 18f * dt) % 360f
            roadScroll = (roadScroll + (0.6f + st.speedKmh * 0.14f) * dt) % 2.0f

            if (autoOrbit) {
                if (st.gear == "R") {
                    userYaw += (155f - userYaw) * 3f * dt
                    userPitch += (18f - userPitch) * 3f * dt
                } else if (st.speedKmh > 10f) {
                    val targetYaw = 24f + st.steeringAngle * 0.45f
                    userYaw += (targetYaw - userYaw) * 3f * dt
                    userPitch += (16f - userPitch) * 3f * dt
                } else {
                    userYaw = (userYaw + 18f * dt) % 360f
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val camDist = 5.3f
            val yawRad = Math.toRadians(userYaw.toDouble())
            val pitchRad = Math.toRadians(userPitch.toDouble())
            val camX = (camDist * cos(pitchRad) * sin(yawRad)).toFloat()
            val camY = (camDist * sin(pitchRad) + 0.35f).toFloat()
            val camZ = (camDist * cos(pitchRad) * cos(yawRad)).toFloat()

            Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, 0f, 0.48f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            GLES20.glUniform3f(lightDirHandle, 0.45f, 0.85f, 0.55f)

            val (ar, ag, ab) = getModeRgb(st.driveMode)

            drawRoad(ar, ag, ab)
            draw3DMonjaroBody(st, ar, ag, ab)
            draw3DWheels(st, ar, ag, ab)
        }

        private fun getModeRgb(mode: String): Triple<Float, Float, Float> = when (mode) {
            "SPORT" -> Triple(1.0f, 0.16f, 0.33f)
            "ECO" -> Triple(0.0f, 0.90f, 0.46f)
            "OFFROAD" -> Triple(1.0f, 0.57f, 0.0f)
            else -> Triple(0.0f, 0.83f, 1.0f)
        }

        private fun drawRoad(ar: Float, ag: Float, ab: Float) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, 0f, roadScroll)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)
            GLES20.glUniform4f(colorHandle, ar * 0.45f, ag * 0.45f, ab * 0.45f, 0.65f)
            GLES20.glUniform1f(metallicHandle, 0.0f)

            roadGridBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 24, roadGridBuffer)
            roadGridBuffer.position(3)
            GLES20.glEnableVertexAttribArray(normHandle)
            GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 24, roadGridBuffer)

            GLES20.glDrawArrays(GLES20.GL_LINES, 0, roadLineCount)
        }

        private fun draw3DMonjaroBody(st: CarState, ar: Float, ag: Float, ab: Float) {
            val roll = (-st.steeringAngle * 0.08f).coerceIn(-4f, 4f)
            val pitch = ((st.throttlePct - st.brakePct) * 0.022f).coerceIn(-2.5f, 2.5f)

            val greyMain = floatArrayOf(0.36f, 0.39f, 0.44f, 1.0f)
            val greyDark = floatArrayOf(0.22f, 0.25f, 0.29f, 1.0f)
            val glassTint = floatArrayOf(0.06f, 0.09f, 0.13f, 0.94f)
            val chromeTrim = floatArrayOf(0.75f, 0.78f, 0.82f, 1.0f)

            // Neon Underglow plane under chassis
            drawBox(0f, 0.14f, 0f, 1.55f, 0.02f, 3.65f, roll, pitch, floatArrayOf(ar, ag, ab, 0.42f), 0f)

            // Lower Chassis & Side Skirts
            drawBox(0f, 0.34f, 0f, 1.62f, 0.22f, 3.95f, roll, pitch, greyDark, 0.5f)

            // Main Muscular SUV Body Hull
            drawBox(0f, 0.58f, 0f, 1.72f, 0.36f, 4.12f, roll, pitch, greyMain, 0.85f)

            // Hood Sculpted Bulge (+Z)
            drawBox(0f, 0.78f, 1.18f, 1.56f, 0.12f, 1.48f, roll, pitch, greyMain, 0.90f)

            // Cabin Greenhouse (Glass)
            drawBox(0f, 1.02f, -0.25f, 1.44f, 0.38f, 2.15f, roll, pitch, glassTint, 0.95f)

            // Metallic Grey Roof Panel & Spoiler
            drawBox(0f, 1.23f, -0.30f, 1.38f, 0.06f, 1.98f, roll, pitch, greyMain, 0.85f)
            drawBox(0f, 1.24f, -1.36f, 1.36f, 0.05f, 0.24f, roll, pitch, greyDark, 0.7f)

            // Roof Rails (Chrome)
            drawBox(-0.62f, 1.27f, -0.30f, 0.04f, 0.04f, 1.75f, roll, pitch, chromeTrim, 1.0f)
            drawBox(0.62f, 1.27f, -0.30f, 0.04f, 0.04f, 1.75f, roll, pitch, chromeTrim, 1.0f)

            // Signature Geely Shield Front Grille (+Z)
            drawBox(0f, 0.54f, 2.07f, 0.94f, 0.28f, 0.05f, roll, pitch, floatArrayOf(0.10f, 0.12f, 0.15f, 1f), 0.9f)
            for (gx in listOf(-0.32f, -0.16f, 0f, 0.16f, 0.32f)) {
                drawBox(gx, 0.54f, 2.09f, 0.025f, 0.24f, 0.03f, roll, pitch, chromeTrim, 1.0f)
            }

            // LED Headlights & DRLs (+Z)
            val drlColor = floatArrayOf(0.88f, 0.96f, 1.0f, 1.0f)
            drawBox(-0.62f, 0.64f, 2.06f, 0.34f, 0.08f, 0.06f, roll, pitch, drlColor, 0.2f)
            drawBox(0.62f, 0.64f, 2.06f, 0.34f, 0.08f, 0.06f, roll, pitch, drlColor, 0.2f)

            // Signature Monjaro Full-Width Rear LED Taillight Bar (-Z)
            val brakeBoost = if (st.brakePct > 5f) 1.0f else 0.72f
            val tailColor = floatArrayOf(brakeBoost, 0.04f, 0.08f, 1.0f)
            drawBox(0f, 0.68f, -2.07f, 1.56f, 0.08f, 0.06f, roll, pitch, tailColor, 0.1f)
            drawBox(-0.68f, 0.64f, -2.07f, 0.24f, 0.12f, 0.06f, roll, pitch, tailColor, 0.1f)
            drawBox(0.68f, 0.64f, -2.07f, 0.24f, 0.12f, 0.06f, roll, pitch, tailColor, 0.1f)

            // Side Mirrors
            drawBox(-0.94f, 0.82f, 0.68f, 0.18f, 0.09f, 0.14f, roll, pitch, greyMain, 0.8f)
            drawBox(0.94f, 0.82f, 0.68f, 0.18f, 0.09f, 0.14f, roll, pitch, greyMain, 0.8f)
        }

        private fun draw3DWheels(st: CarState, ar: Float, ag: Float, ab: Float) {
            val steer = (-st.steeringAngle * 0.55f).coerceIn(-32f, 32f)
            val wheelPositions = listOf(
                Triple(-0.84f, 1.32f, steer),
                Triple(0.84f, 1.32f, steer),
                Triple(-0.84f, -1.32f, 0f),
                Triple(0.84f, -1.32f, 0f)
            )

            for ((wx, wz, steerDeg) in wheelPositions) {
                drawCylinderWheel(wx, 0.36f, wz, steerDeg, wheelSpinAngle, 0.36f, 0.24f, floatArrayOf(0.08f, 0.09f, 0.11f, 1f), 0.2f)
                drawCylinderWheel(wx * 1.01f, 0.36f, wz, steerDeg, wheelSpinAngle, 0.25f, 0.25f, floatArrayOf(0.72f, 0.76f, 0.82f, 1f), 1.0f)
                drawCylinderWheel(wx * 1.015f, 0.36f, wz, steerDeg, wheelSpinAngle, 0.14f, 0.255f, floatArrayOf(ar, ag, ab, 1f), 0.5f)
            }
        }

        private fun drawBox(
            tx: Float, ty: Float, tz: Float,
            sx: Float, sy: Float, sz: Float,
            rollDeg: Float, pitchDeg: Float,
            rgba: FloatArray, metallic: Float
        ) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.rotateM(modelMatrix, 0, rollDeg, 0f, 0f, 1f)
            Matrix.rotateM(modelMatrix, 0, pitchDeg, 1f, 0f, 0f)
            Matrix.translateM(modelMatrix, 0, tx, ty, tz)
            Matrix.scaleM(modelMatrix, 0, sx, sy, sz)

            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)
            GLES20.glUniform4f(colorHandle, rgba[0], rgba[1], rgba[2], rgba[3])
            GLES20.glUniform1f(metallicHandle, metallic)

            cubeBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 24, cubeBuffer)
            cubeBuffer.position(3)
            GLES20.glEnableVertexAttribArray(normHandle)
            GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 24, cubeBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        }

        private fun drawCylinderWheel(
            tx: Float, ty: Float, tz: Float,
            steerDeg: Float, spinDeg: Float,
            radius: Float, width: Float,
            rgba: FloatArray, metallic: Float
        ) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, tx, ty, tz)
            Matrix.rotateM(modelMatrix, 0, steerDeg, 0f, 1f, 0f)
            Matrix.rotateM(modelMatrix, 0, spinDeg, 1f, 0f, 0f)
            Matrix.scaleM(modelMatrix, 0, width, radius, radius)

            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)
            GLES20.glUniform4f(colorHandle, rgba[0], rgba[1], rgba[2], rgba[3])
            GLES20.glUniform1f(metallicHandle, metallic)

            wheelBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 24, wheelBuffer)
            wheelBuffer.position(3)
            GLES20.glEnableVertexAttribArray(normHandle)
            GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 24, wheelBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, wheelVertexCount)
        }

        private fun buildUnitCubeWithNormals() {
            val v = floatArrayOf(
                -0.5f,-0.5f, 0.5f, 0f,0f,1f,  0.5f,-0.5f, 0.5f, 0f,0f,1f,  0.5f, 0.5f, 0.5f, 0f,0f,1f,
                -0.5f,-0.5f, 0.5f, 0f,0f,1f,  0.5f, 0.5f, 0.5f, 0f,0f,1f, -0.5f, 0.5f, 0.5f, 0f,0f,1f,
                 0.5f,-0.5f,-0.5f, 0f,0f,-1f, -0.5f,-0.5f,-0.5f, 0f,0f,-1f, -0.5f, 0.5f,-0.5f, 0f,0f,-1f,
                 0.5f,-0.5f,-0.5f, 0f,0f,-1f, -0.5f, 0.5f,-0.5f, 0f,0f,-1f,  0.5f, 0.5f,-0.5f, 0f,0f,-1f,
                -0.5f,-0.5f,-0.5f,-1f,0f,0f, -0.5f,-0.5f, 0.5f,-1f,0f,0f, -0.5f, 0.5f, 0.5f,-1f,0f,0f,
                -0.5f,-0.5f,-0.5f,-1f,0f,0f, -0.5f, 0.5f, 0.5f,-1f,0f,0f, -0.5f, 0.5f,-0.5f,-1f,0f,0f,
                 0.5f,-0.5f, 0.5f, 1f,0f,0f,  0.5f,-0.5f,-0.5f, 1f,0f,0f,  0.5f, 0.5f,-0.5f, 1f,0f,0f,
                 0.5f,-0.5f, 0.5f, 1f,0f,0f,  0.5f, 0.5f,-0.5f, 1f,0f,0f,  0.5f, 0.5f, 0.5f, 1f,0f,0f,
                -0.5f, 0.5f, 0.5f, 0f,1f,0f,  0.5f, 0.5f, 0.5f, 0f,1f,0f,  0.5f, 0.5f,-0.5f, 0f,1f,0f,
                -0.5f, 0.5f, 0.5f, 0f,1f,0f,  0.5f, 0.5f,-0.5f, 0f,1f,0f, -0.5f, 0.5f,-0.5f, 0f,1f,0f,
                -0.5f,-0.5f,-0.5f, 0f,-1f,0f,  0.5f,-0.5f,-0.5f, 0f,-1f,0f,  0.5f,-0.5f, 0.5f, 0f,-1f,0f,
                -0.5f,-0.5f,-0.5f, 0f,-1f,0f,  0.5f,-0.5f, 0.5f, 0f,-1f,0f, -0.5f,-0.5f, 0.5f, 0f,-1f,0f
            )
            cubeBuffer = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            cubeBuffer.put(v).position(0)
        }

        private fun build3DWheelCylinder(segments: Int) {
            val list = ArrayList<Float>()
            for (i in 0 until segments) {
                val a1 = (2.0 * PI * i / segments).toFloat()
                val a2 = (2.0 * PI * (i + 1) / segments).toFloat()
                val y1 = cos(a1); val z1 = sin(a1)
                val y2 = cos(a2); val z2 = sin(a2)

                list.addAll(listOf(-0.5f, y1, z1, 0f, y1, z1,  0.5f, y1, z1, 0f, y1, z1,  0.5f, y2, z2, 0f, y2, z2))
                list.addAll(listOf(-0.5f, y1, z1, 0f, y1, z1,  0.5f, y2, z2, 0f, y2, z2, -0.5f, y2, z2, 0f, y2, z2))
                list.addAll(listOf(-0.5f, 0f, 0f, -1f, 0f, 0f, -0.5f, y2, z2, -1f, 0f, 0f, -0.5f, y1, z1, -1f, 0f, 0f))
                list.addAll(listOf(0.5f, 0f, 0f, 1f, 0f, 0f, 0.5f, y1, z1, 1f, 0f, 0f, 0.5f, y2, z2, 1f, 0f, 0f))
            }
            wheelVertexCount = list.size / 6
            wheelBuffer = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            wheelBuffer.put(list.toFloatArray()).position(0)
        }

        private fun buildRoadGrid() {
            val lines = ArrayList<Float>()
            for (x in -10..10) {
                val xf = x * 1.2f
                lines.addAll(listOf(xf, 0f, -24f, 0f, 1f, 0f, xf, 0f, 24f, 0f, 1f, 0f))
            }
            for (z in -12..12) {
                val zf = z * 2.0f
                lines.addAll(listOf(-12f, 0f, zf, 0f, 1f, 0f, 12f, 0f, zf, 0f, 1f, 0f))
            }
            roadLineCount = lines.size / 6
            roadGridBuffer = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            roadGridBuffer.put(lines.toFloatArray()).position(0)
        }

        private fun loadShader(type: Int, code: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, code)
                GLES20.glCompileShader(shader)
            }
        }
    }
}
