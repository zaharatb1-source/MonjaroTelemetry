package com.monjaro.telemetry

import android.content.Context
import android.graphics.*
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import java.io.DataInputStream
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
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            renderer = Monjaro3DRenderer(context)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        hudOverlay = TelemetryHudOverlayView(context)
        addView(hudOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun onHostResume() { glView.onResume() }
    fun onHostPause() { glView.onPause() }

    fun updateState(state: CarState) {
        renderer.carState = state
        hudOverlay.updateState(state)
    }

    fun resetCamera() {
        renderer.autoOrbit = true
        renderer.userYaw = 35f
        renderer.userPitch = 16f
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
                renderer.userPitch = (renderer.userPitch + dy * 0.25f).coerceIn(5f, 68f)
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
            canvas.drawText("GEELY MONJARO 3D  •  \${state.driveMode}", w * 0.5f, h * 0.08f, textPaint)

            subPaint.textSize = h * 0.022f
            subPaint.color = if (state.connectionStatus.contains("ОНЛАЙН")) Color.parseColor("#00E676") else Color.parseColor("#94A3B8")
            canvas.drawText("\${state.connectionStatus}  •  Свайп 360°", w * 0.5f, h * 0.12f, subPaint)

            drawArcGauge(
                canvas, w * 0.16f, h * 0.80f, min(w, h) * 0.16f,
                state.speedKmh / 240f, "\${state.speedKmh.toInt()}", "КМ/Ч • \${state.gear}", acc
            )
            drawArcGauge(
                canvas, w * 0.84f, h * 0.80f, min(w, h) * 0.16f,
                ((state.boostBar + 0.8f) / 2.6f).coerceIn(0f, 1f),
                String.format(Locale.US, "%+.2f", state.boostBar), "BAR • \${state.rpm.toInt()} RPM",
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

    private class Monjaro3DRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
        @Volatile var carState = CarState()
        @Volatile var autoOrbit = true
        @Volatile var userYaw = 35f
        @Volatile var userPitch = 16f

        private var program = 0
        private var posHandle = 0
        private var normHandle = 0
        private var colHandle = 0
        private var grpHandle = 0
        private var mvpHandle = 0
        private var modelHandle = 0
        private var camPosHandle = 0
        private var lightDirHandle = 0
        private var accentColorHandle = 0
        private var brakeBoostHandle = 0

        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        private var roadScroll = 0f
        private var lastTimeNs = System.nanoTime()

        private var vboId = 0
        private var iboId = 0
        private var indexCount = 0

        private lateinit var roadGridBuffer: FloatBuffer
        private var roadLineCount = 0

        private val vertexShaderCode = """
            #version 300 es
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            uniform float uBrakeBoost;
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in float aGroup;
            out vec3 vNormal;
            out vec3 vWorldPos;
            out vec3 vColor;
            out float vGroup;
            void main() {
                vec3 posScaled = aPosition / 10922.3333;
                gl_Position = uMVPMatrix * vec4(posScaled, 1.0);
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vWorldPos = vec3(uModelMatrix * vec4(posScaled, 1.0));
                vGroup = aGroup;
                if (aGroup > 4.5 && aGroup < 5.5) {
                    vColor = aColor * (1.0 + uBrakeBoost);
                } else {
                    vColor = aColor;
                }
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            uniform vec3 uCamPos;
            uniform vec3 uLightDir;
            uniform vec3 uAccentColor;
            in vec3 vNormal;
            in vec3 vWorldPos;
            in vec3 vColor;
            in float vGroup;
            out vec4 fragColor;
            void main() {
                if (vGroup < -0.5) {
                    fragColor = vec4(vColor, 0.55);
                    return;
                }
                vec3 n = normalize(vNormal);
                if (!gl_FrontFacing) n = -n;
                vec3 l = normalize(uLightDir);
                vec3 v = normalize(uCamPos - vWorldPos);
                float nDotL = max(dot(n, l), 0.0);
                float bounce = clamp(-n.y, 0.0, 1.0) * 0.14;
                float diff = 0.24 + 0.72 * nDotL + bounce;
                vec3 r = reflect(-v, n);
                float envBand = smoothstep(-0.3, 0.9, r.y);
                vec3 envColor = mix(vec3(0.08, 0.11, 0.18), vec3(0.72, 0.78, 0.86), envBand);
                // Studio softbox reflection strip across hood, roof & door flanks
                float softbox = smoothstep(0.86, 0.98, abs(r.x + r.y * 0.65));
                vec3 h = normalize(l + v);
                float spec = pow(max(dot(n, h), 0.0), 48.0);
                float specStrength = (vGroup < 0.5) ? 0.60 : ((vGroup > 3.5 && vGroup < 4.5) ? 0.80 : 0.14);
                float envStrength = (vGroup < 0.5) ? 0.32 : ((vGroup > 3.5 && vGroup < 4.5) ? 0.40 : 0.08);
                float fresnel = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                vec3 rgb = vColor * diff + envColor * envStrength + vec3(softbox * 0.24 * envStrength) + vec3(spec * specStrength) + uAccentColor * fresnel * 0.28;
                fragColor = vec4(rgb, 1.0);
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0.024f, 0.032f, 0.052f, 1.0f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
            val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, vs)
                GLES30.glAttachShader(it, fs)
                GLES30.glLinkProgram(it)
            }
            posHandle = 0
            normHandle = 1
            colHandle = 2
            grpHandle = 3
            mvpHandle = GLES30.glGetUniformLocation(program, "uMVPMatrix")
            modelHandle = GLES30.glGetUniformLocation(program, "uModelMatrix")
            camPosHandle = GLES30.glGetUniformLocation(program, "uCamPos")
            lightDirHandle = GLES30.glGetUniformLocation(program, "uLightDir")
            accentColorHandle = GLES30.glGetUniformLocation(program, "uAccentColor")
            brakeBoostHandle = GLES30.glGetUniformLocation(program, "uBrakeBoost")

            uploadQuantizedMonjaroToGpuVbo()
            buildRoadGrid()
        }

        private fun uploadQuantizedMonjaroToGpuVbo() {
            try {
                ctx.assets.open("monjaro_3d_q16.bin").use { raw ->
                    val dis = DataInputStream(raw)
                    val vCount = Integer.reverseBytes(dis.readInt())
                    val iCount = Integer.reverseBytes(dis.readInt())
                    val vBytesLen = vCount * 16
                    val iBytesLen = iCount * 4

                    val vBytes = ByteArray(vBytesLen)
                    var r1 = 0
                    while (r1 < vBytesLen) {
                        val n = dis.read(vBytes, r1, vBytesLen - r1)
                        if (n <= 0) break
                        r1 += n
                    }

                    val iBytes = ByteArray(iBytesLen)
                    var r2 = 0
                    while (r2 < iBytesLen) {
                        val n = dis.read(iBytes, r2, iBytesLen - r2)
                        if (n <= 0) break
                        r2 += n
                    }

                    val vBuf = ByteBuffer.allocateDirect(vBytesLen).order(ByteOrder.nativeOrder()).apply {
                        put(vBytes)
                        position(0)
                    }
                    val iBuf = ByteBuffer.allocateDirect(iBytesLen).order(ByteOrder.nativeOrder()).apply {
                        put(iBytes)
                        position(0)
                    }

                    val buffers = IntArray(2)
                    GLES30.glGenBuffers(2, buffers, 0)
                    vboId = buffers[0]
                    iboId = buffers[1]

                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vBytesLen, vBuf, GLES30.GL_STATIC_DRAW)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
                    GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iBytesLen, iBuf, GLES30.GL_STATIC_DRAW)
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

                    indexCount = iCount
                }
            } catch (e: Exception) {
                Log.e("Monjaro3D", "Failed to upload Q16 GPU VBO: \${e.message}")
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / max(1, height).toFloat()
            Matrix.perspectiveM(projMatrix, 0, 42f, ratio, 0.15f, 60f)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = ((now - lastTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastTimeNs = now

            val st = carState
            roadScroll = (roadScroll + (0.6f + st.speedKmh * 0.14f) * dt) % 2.0f

            if (autoOrbit) {
                if (st.gear == "R") {
                    userYaw += (155f - userYaw) * 3f * dt
                    userPitch += (18f - userPitch) * 3f * dt
                } else if (st.speedKmh > 10f) {
                    val targetYaw = 28f + st.steeringAngle * 0.45f
                    userYaw += (targetYaw - userYaw) * 3f * dt
                    userPitch += (16f - userPitch) * 3f * dt
                } else {
                    userYaw = (userYaw + 18f * dt) % 360f
                }
            }

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glUseProgram(program)

            val camDist = 4.5f
            val yawRad = Math.toRadians(userYaw.toDouble())
            val pitchRad = Math.toRadians(userPitch.toDouble())
            val camX = (camDist * cos(pitchRad) * sin(yawRad)).toFloat()
            val camY = (camDist * sin(pitchRad) + 0.55f).toFloat()
            val camZ = (camDist * cos(pitchRad) * cos(yawRad)).toFloat()

            GLES30.glUniform3f(camPosHandle, camX, camY, camZ)
            Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, 0f, 0.45f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            GLES30.glUniform3f(lightDirHandle, 0.45f, 0.85f, 0.55f)
            val (ar, ag, ab) = getModeRgb(st.driveMode)
            GLES30.glUniform3f(accentColorHandle, ar, ag, ab)
            GLES30.glUniform1f(brakeBoostHandle, if (st.brakePct > 5f) 0.85f else 0.0f)

            drawRoad(ar, ag, ab)
            drawQuantizedMonjaroVbo(st)
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

            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)
            GLES30.glDisableVertexAttribArray(colHandle)
            GLES30.glVertexAttrib3f(colHandle, ar * 0.65f, ag * 0.65f, ab * 0.65f)
            GLES30.glDisableVertexAttribArray(grpHandle)
            GLES30.glVertexAttrib1f(grpHandle, -1.0f)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            roadGridBuffer.position(0)
            GLES30.glEnableVertexAttribArray(posHandle)
            GLES30.glVertexAttribPointer(posHandle, 3, GLES30.GL_FLOAT, false, 24, roadGridBuffer)
            roadGridBuffer.position(3)
            GLES30.glEnableVertexAttribArray(normHandle)
            GLES30.glVertexAttribPointer(normHandle, 3, GLES30.GL_FLOAT, false, 24, roadGridBuffer)

            GLES30.glDrawArrays(GLES30.GL_LINES, 0, roadLineCount)
        }

        private fun drawQuantizedMonjaroVbo(st: CarState) {
            if (vboId == 0 || iboId == 0 || indexCount <= 0) return

            val roll = (-st.steeringAngle * 0.08f).coerceIn(-4f, 4f)
            val pitch = ((st.throttlePct - st.brakePct) * 0.02f).coerceIn(-2.5f, 2.5f)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.rotateM(modelMatrix, 0, roll, 0f, 0f, 1f)
            Matrix.rotateM(modelMatrix, 0, pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)

            GLES30.glEnableVertexAttribArray(posHandle)
            GLES30.glVertexAttribPointer(posHandle, 3, GLES30.GL_SHORT, false, 16, 0)

            GLES30.glEnableVertexAttribArray(normHandle)
            GLES30.glVertexAttribPointer(normHandle, 3, GLES30.GL_SHORT, true, 16, 6)

            GLES30.glEnableVertexAttribArray(colHandle)
            GLES30.glVertexAttribPointer(colHandle, 3, GLES30.GL_UNSIGNED_BYTE, true, 16, 12)

            GLES30.glEnableVertexAttribArray(grpHandle)
            GLES30.glVertexAttribPointer(grpHandle, 1, GLES30.GL_UNSIGNED_BYTE, false, 16, 15)

            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
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
            return GLES30.glCreateShader(type).also { shader ->
                GLES30.glShaderSource(shader, code)
                GLES30.glCompileShader(shader)
            }
        }
    }
}
