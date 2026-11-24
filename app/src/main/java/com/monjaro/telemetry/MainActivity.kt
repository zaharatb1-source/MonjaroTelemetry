package com.monjaro.telemetry

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var carScene3D: Monjaro3DSceneView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SensorCardAdapter
    private var canProvider: CanDataProvider? = null
    private var overlayActive = false

    private val allSensorSpecs = listOf(
        SensorSpec("coolant", "Температура ОЖ", "°C", 50f, 105f),
        SensorSpec("trans", "Масло АКПП Aisin", "°C", 40f, 110f),
        SensorSpec("oil", "Масло двигателя", "°C", 50f, 120f),
        SensorSpec("boost", "Наддув турбины", "Bar", -1.0f, 1.8f),
        SensorSpec("volt", "Напряжение сети", "V", 12.2f, 15.0f),
        SensorSpec("intake", "Темп. на впуске", "°C", -30f, 65f),
        SensorSpec("ambient", "Уличная темп.", "°C", -45f, 50f),
        SensorSpec("throttle", "Педаль газа", "%", 0f, 100f),
        SensorSpec("brake", "Давление тормоза", "%", 0f, 100f),
        SensorSpec("steer", "Угол руля", "°", -540f, 540f),
        SensorSpec("tpms_fl", "Колесо Перед Лев", "Bar", 2.0f, 2.8f),
        SensorSpec("tpms_fr", "Колесо Перед Прав", "Bar", 2.0f, 2.8f),
        SensorSpec("tpms_rl", "Колесо Зад Лев", "Bar", 2.0f, 2.8f),
        SensorSpec("tpms_rr", "Колесо Зад Прав", "Bar", 2.0f, 2.8f),
        SensorSpec("fuel", "Остаток топлива", "%", 12f, 100f)
    )

    data class SensorSpec(
        val id: String,
        val title: String,
        val unit: String,
        val minNormal: Float,
        val maxNormal: Float
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#06080D"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        carScene3D = Monjaro3DSceneView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.22f)
        }
        root.addView(carScene3D)

        val rightPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val btnCam = createNeonButton("Камера 360°") { carScene3D.resetCamera() }
        val btnOverlay = createNeonButton("Мини-оверлей") { toggleOverlay() }
        val btnDemo = createNeonButton(if (TelemetryPrefs.isDemoEnabled(this)) "Демо: ВКЛ" else "Демо: ВЫКЛ") { btn ->
            val next = !TelemetryPrefs.isDemoEnabled(this)
            TelemetryPrefs.setDemoEnabled(this, next)
            btn.text = if (next) "Демо: ВКЛ" else "Демо: ВЫКЛ"
        }
        val btnFilter = createNeonButton("Датчики ⚙") { showSensorToggleDialog() }

        topBar.addView(btnCam)
        topBar.addView(btnOverlay)
        topBar.addView(btnDemo)
        topBar.addView(btnFilter)
        rightPane.addView(topBar)

        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        adapter = SensorCardAdapter()
        recyclerView.adapter = adapter
        rightPane.addView(recyclerView)

        root.addView(rightPane)
        setContentView(root)

        canProvider = CanDataProvider(this) { state ->
            carScene3D.updateState(state)
            updateCards(state)
        }
    }

    override fun onResume() {
        super.onResume()
        carScene3D.onHostResume()
        canProvider?.start()
    }

    override fun onPause() {
        super.onPause()
        carScene3D.onHostPause()
        canProvider?.stop()
    }

    private fun createNeonButton(label: String, onClick: (Button) -> Unit): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#111827"))
            cornerRadius = 16f
            setStroke(2, Color.parseColor("#334155"))
        }
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isAllCaps = false
            background = bg
            setPadding(14, 8, 14, 8)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            layoutParams = lp
            setOnClickListener { onClick(this) }
        }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Разреши показ поверх других окон", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (!overlayActive) {
            startForegroundService(intent)
            overlayActive = true
            Toast.makeText(this, "Мини-виджет запущен поверх экранов", Toast.LENGTH_SHORT).show()
        } else {
            stopService(intent)
            overlayActive = false
            Toast.makeText(this, "Мини-виджет выключен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSensorToggleDialog() {
        val titles = allSensorSpecs.map { "${it.title} (${it.unit})" }.toTypedArray()
        val checked = allSensorSpecs.map { TelemetryPrefs.isItemEnabled(this, it.id) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Выбор параметров телеметрии")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                TelemetryPrefs.setItemEnabled(this, allSensorSpecs[which].id, isChecked)
            }
            .setPositiveButton("Готово") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun updateCards(s: CarState) {
        val activeItems = mutableListOf<TelemetryItem>()
        for (spec in allSensorSpecs) {
            if (!TelemetryPrefs.isItemEnabled(this, spec.id)) continue
            val (valStr, raw) = when (spec.id) {
                "coolant" -> "${s.coolantTemp.toInt()}" to s.coolantTemp
                "trans" -> "${s.transOilTemp.toInt()}" to s.transOilTemp
                "oil" -> "${s.engineOilTemp.toInt()}" to s.engineOilTemp
                "boost" -> String.format(Locale.US, "%+.2f", s.boostBar) to s.boostBar
                "volt" -> String.format(Locale.US, "%.1f", s.batteryVolt) to s.batteryVolt
                "intake" -> "${s.intakeAirTemp.toInt()}" to s.intakeAirTemp
                "ambient" -> "${s.ambientTemp.toInt()}" to s.ambientTemp
                "throttle" -> "${s.throttlePct.toInt()}" to s.throttlePct
                "brake" -> "${s.brakePct.toInt()}" to s.brakePct
                "steer" -> "${s.steeringAngle.toInt()}" to s.steeringAngle
                "tpms_fl" -> String.format(Locale.US, "%.1f", s.tpmsFL) to s.tpmsFL
                "tpms_fr" -> String.format(Locale.US, "%.1f", s.tpmsFR) to s.tpmsFR
                "tpms_rl" -> String.format(Locale.US, "%.1f", s.tpmsRL) to s.tpmsRL
                "tpms_rr" -> String.format(Locale.US, "%.1f", s.tpmsRR) to s.tpmsRR
                "fuel" -> "${s.fuelLevelPct.toInt()}" to s.fuelLevelPct
                else -> "--" to 0f
            }
            activeItems.add(
                TelemetryItem(
                    id = spec.id,
                    title = spec.title,
                    unit = spec.unit,
                    value = valStr,
                    rawFloat = raw,
                    minWarn = spec.minNormal,
                    maxWarn = spec.maxNormal,
                    enabled = true
                )
            )
        }
        adapter.submitList(activeItems)
    }

    inner class SensorCardAdapter : RecyclerView.Adapter<SensorCardAdapter.VH>() {
        private var items: List<TelemetryItem> = emptyList()

        fun submitList(newItems: List<TelemetryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(val root: LinearLayout, val titleTv: TextView, val valTv: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#0F1522"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#1E293B"))
            }
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = bg
                setPadding(20, 14, 20, 14)
                val lp = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(6, 6, 6, 6)
                layoutParams = lp
            }
            val titleTv = TextView(parent.context).apply {
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
            }
            val valTv = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 26f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            }
            card.addView(titleTv)
            card.addView(valTv)
            return VH(card, titleTv, valTv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.titleTv.text = item.title
            holder.valTv.text = "${item.value} ${item.unit}"

            val color = when {
                item.maxWarn != null && item.rawFloat > item.maxWarn -> Color.parseColor("#FF2A54")
                item.minWarn != null && item.rawFloat < item.minWarn -> Color.parseColor("#38BDF8")
                else -> Color.WHITE
            }
            holder.valTv.setTextColor(color)
        }
    }
}
