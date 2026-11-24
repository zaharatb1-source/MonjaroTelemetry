package com.monjaro.telemetry

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var infoText: TextView? = null
    private var canProvider: CanDataProvider? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        createOverlayWindow()

        canProvider = CanDataProvider(this) { state ->
            updateOverlayText(state)
        }
        canProvider?.start()
    }

    private fun startForegroundNotification() {
        val channelId = "monjaro_telemetry_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Monjaro Telemetry Overlay", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Monjaro Telemetry")
            .setContentText("Оверлей телеметрии активен")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(101, notification)
    }

    private fun createOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#DD0B0F19"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#00D4FF"))
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "ОЖ --°  |  АКПП --°  |  --.-V"
        }
        overlayView?.addView(infoText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 120
            y = 24
        }

        // Draggable support
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun updateOverlayText(s: CarState) {
        val parts = mutableListOf<String>()
        if (TelemetryPrefs.isItemEnabled(this, "coolant")) {
            parts.add("ОЖ ${s.coolantTemp.toInt()}°C")
        }
        if (TelemetryPrefs.isItemEnabled(this, "trans")) {
            parts.add("АКПП ${s.transOilTemp.toInt()}°C")
        }
        if (TelemetryPrefs.isItemEnabled(this, "oil")) {
            parts.add("Масло ${s.engineOilTemp.toInt()}°C")
        }
        if (TelemetryPrefs.isItemEnabled(this, "boost")) {
            parts.add(String.format("%+.2f Bar", s.boostBar))
        }
        if (TelemetryPrefs.isItemEnabled(this, "volt")) {
            parts.add(String.format("%.1fV", s.batteryVolt))
        }
        infoText?.text = if (parts.isEmpty()) "Monjaro Telemetry" else parts.joinToString("  |  ")
    }

    override fun onDestroy() {
        super.onDestroy()
        canProvider?.stop()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
