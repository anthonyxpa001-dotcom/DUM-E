package com.robotarm.control

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var status: TextView? = null
    private var distanceLabel: TextView? = null
    private var radar: RadarView? = null
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(4, 17, 14)
        window.navigationBarColor = Color.rgb(4, 17, 14)
        buildUi()
        requestBtPermission()
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 42)
        }
    }

    private fun buildUi() {
        val bg = Color.rgb(5, 15, 17)
        val green = Color.rgb(60, 255, 145)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 14)
            setBackgroundColor(bg)
        }
        val title = TextView(this).apply {
            text = "ROBOT ARM  //  CONTROL"
            textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(green); gravity = Gravity.CENTER
        }
        page.addView(title, LinearParams(-1, 42))
        status = TextView(this).apply {
            text = "● DESCONECTADO  ·  HC-05"
            textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }
        page.addView(status, LinearParams(-1, 30))

        val connect = Button(this).apply {
            text = "CONECTAR HC-05"
            setOnClickListener { connectToPairedDevice() }
        }
        page.addView(connect, LinearParams(-1, 48))

        val radarTitle = TextView(this).apply {
            text = "RADAR ULTRASÓNICO  ·  HC-SR04"
            setTextColor(green); textSize = 13f; gravity = Gravity.CENTER
        }
        page.addView(radarTitle, LinearParams(-1, 32))
        radar = RadarView(this)
        page.addView(radar, LinearParams(-1, 0, 1f))
        distanceLabel = TextView(this).apply {
            text = "DISTANCIA: -- cm"
            setTextColor(green); textSize = 20f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        page.addView(distanceLabel, LinearParams(-1, 38))

        val baseLabel = section("BASE · GIRO")
        page.addView(baseLabel, LinearParams(-1, 28))
        val baseRow = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        baseRow.addView(controlButton("◀ IZQUIERDA") { send("BASE_LEFT") }, LinearParams(0, 54, 1f))
        baseRow.addView(space(8), LinearParams(8, 1))
        baseRow.addView(controlButton("DERECHA ▶") { send("BASE_RIGHT") }, LinearParams(0, 54, 1f))
        page.addView(baseRow, LinearParams(-1, 58))

        val armLabel = section("BRAZO · ELEVACIÓN")
        page.addView(armLabel, LinearParams(-1, 28))
        val armRow = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        armRow.addView(controlButton("▲ SUBIR") { send("ARM_UP") }, LinearParams(0, 54, 1f))
        armRow.addView(space(8), LinearParams(8, 1))
        armRow.addView(controlButton("▼ BAJAR") { send("ARM_DOWN") }, LinearParams(0, 54, 1f))
        page.addView(armRow, LinearParams(-1, 58))

        val gripLabel = section("PINZA")
        page.addView(gripLabel, LinearParams(-1, 28))
        val gripRow = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        gripRow.addView(controlButton("ABRIR") { send("GRIP_OPEN") }, LinearParams(0, 54, 1f))
        gripRow.addView(space(8), LinearParams(8, 1))
        gripRow.addView(controlButton("CERRAR") { send("GRIP_CLOSE") }, LinearParams(0, 54, 1f))
        page.addView(gripRow, LinearParams(-1, 58))

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.rgb(100, 220, 160)); textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun controlButton(t: String, action: () -> Unit) = Button(this).apply {
        text = t; textSize = 12f; setOnClickListener { action() }
    }
    private fun space(w: Int) = View(this)

    private fun connectToPairedDevice() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermission()
            status?.text = "PERMISO BLUETOOTH PENDIENTE"
            return
        }
        val bt = adapter
        if (bt == null) { status?.text = "ESTE CELULAR NO TIENE BLUETOOTH"; return }
        if (!bt.isEnabled) { status?.text = "ACTIVA BLUETOOTH DEL CELULAR"; return }
        val device: BluetoothDevice? = try {
            bt.bondedDevices.firstOrNull { it.name?.contains("HC-05", true) == true }
        } catch (_: SecurityException) { null }
        if (device == null) {
            status?.text = "EMPAREJA HC-05 EN AJUSTES DEL CELULAR"
            return
        }
        status?.text = "CONECTANDO A ${device.name}..."
        thread {
            try {
                bt.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(spp)
                s.connect()
                socket = s
                runOnUiThread { status?.text = "● CONECTADO · ${device.name}" }
                listen(s)
            } catch (e: Exception) {
                runOnUiThread { status?.text = "ERROR DE CONEXIÓN · REVISA HC-05" }
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
    }

    private fun listen(s: BluetoothSocket) {
        val buffer = ByteArray(128)
        val pending = StringBuilder()
        try {
            while (true) {
                val n = s.inputStream.read(buffer)
                if (n <= 0) break
                pending.append(String(buffer, 0, n, Charsets.UTF_8))
                while (pending.contains("\n")) {
                    val idx = pending.indexOf("\n")
                    val line = pending.substring(0, idx).trim()
                    pending.delete(0, idx + 1)
                    val cm = line.toFloatOrNull()
                    if (cm != null && cm in 0f..400f) {
                        runOnUiThread {
                            distanceLabel?.text = "DISTANCIA: ${cm.toInt()} cm"
                            radar?.setDistance(cm)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            runOnUiThread { status?.text = "● DESCONECTADO" }
        }
    }

    private fun send(command: String) {
        val s = socket
        if (s == null || !s.isConnected) {
            status?.text = "PRIMERO CONECTA EL HC-05"
            return
        }
        thread {
            try { s.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8)) }
            catch (_: Exception) { runOnUiThread { status?.text = "ERROR AL ENVIAR" } }
        }
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun LinearParams(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    inner class RadarView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var distance = -1f
        private val green = Color.rgb(40, 255, 130)
        fun setDistance(cm: Float) { distance = cm; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height * 0.88f
            val r = min(width * 0.43f, height * 0.82f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.rgb(20, 105, 67)
            for (f in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
                canvas.drawArc(cx-r*f, cy-r*f, cx+r*f, cy+r*f, 180f, 180f, false, paint)
            }
            canvas.drawLine(cx-r, cy, cx+r, cy, paint)
            canvas.drawLine(cx, cy, cx, cy-r, paint)
            canvas.drawLine(cx-r*0.707f, cy-r*0.707f, cx+r*0.707f, cy-r*0.707f, paint)
            paint.color = green
            paint.strokeWidth = 2.5f
            canvas.drawLine(cx, cy, cx, cy-r, paint)
            if (distance >= 0f) {
                val range = (distance / 200f).coerceIn(0.05f, 1f)
                val px = cx + r * range * 0.48f
                val py = cy - r * range * 0.87f
                paint.style = Paint.Style.FILL
                paint.color = Color.RED
                canvas.drawCircle(px, py, 8f, paint)
                paint.color = Color.argb(65, 255, 0, 0)
                canvas.drawCircle(px, py, 16f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = green
            canvas.drawCircle(cx, cy, 4f, paint)
        }
    }
}
