package id.skyspy.controller

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var countText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var serviceButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private val refreshLoop = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        requestNotificationPermission()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildScreen() {
        window.statusBarColor = Color.rgb(245, 247, 251)
        window.navigationBarColor = Color.rgb(245, 247, 251)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.setBackgroundColor(Color.rgb(245, 247, 251))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(30))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(text("SKYSPY  /  ANDROID CONTROL", 11f, Color.rgb(37, 99, 235), true))
        content.addView(text("Device overview", 28f, Color.rgb(15, 23, 42), true, 18), params(4))
        content.addView(
            text("Owner-approved Android diagnostics on your trusted local Wi-Fi.", 14f, Color.rgb(100, 116, 139), false),
            params(8)
        )

        val serviceCard = card()
        serviceCard.addView(text("LOCAL PANEL SERVICE", 10f, Color.rgb(100, 116, 139), true))
        statusText = text("Checking panel service…", 14f, Color.rgb(15, 23, 42), true, 8)
        serviceCard.addView(statusText)
        addressText = text("No private Wi-Fi address detected.", 13f, Color.rgb(37, 99, 235), true, 8)
        serviceCard.addView(addressText)
        serviceCard.addView(
            text("Keep this app's foreground notification visible while another phone is connected. Both devices must use the same trusted Wi-Fi network.", 12f, Color.rgb(100, 116, 139), false, 8)
        )
        serviceButton = button("Start local panel")
        serviceButton.setOnClickListener { toggleService() }
        serviceCard.addView(serviceButton, params(14))
        val copyButton = button("Copy connection address")
        copyButton.setBackgroundColor(Color.rgb(226, 232, 240))
        copyButton.setTextColor(Color.rgb(30, 41, 59))
        copyButton.setOnClickListener { copyAddress() }
        serviceCard.addView(copyButton, params(8))
        content.addView(serviceCard, params(24))

        val summaryCard = card()
        summaryCard.addView(text("ENROLLED DEVICES", 10f, Color.rgb(100, 116, 139), true))
        countText = text("0 devices · 0 online", 23f, Color.rgb(15, 23, 42), true, 8)
        summaryCard.addView(countText)
        content.addView(summaryCard, params(12))

        val listCard = card()
        listCard.addView(text("Connected devices", 18f, Color.rgb(15, 23, 42), true))
        listCard.addView(text("Only the diagnostics explicitly approved on each Companion are listed.", 12f, Color.rgb(100, 116, 139), false, 6))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(deviceList, params(12))
        content.addView(listCard, params(12))

        content.addView(
            text("Controls in this build: view diagnostics, refresh heartbeat status, and revoke a device's enrollment. Remote screen viewing or touch input is not enabled.", 11f, Color.rgb(100, 116, 139), false, 16),
            params(16)
        )
        content.addView(
            text("SkySpy does not collect SMS, contacts, calls, notifications, passwords, location, camera, microphone, or screen contents.", 11f, Color.rgb(148, 163, 184), false, 8)
        )
    }

    private fun toggleService() {
        val running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
        if (running) {
            stopService(Intent(this, ControllerService::class.java))
        } else {
            val intent = Intent(this, ControllerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        statusText.text = if (running) "Stopping local panel…" else "Starting local panel…"
        handler.postDelayed({ render() }, 600)
    }

    private fun render() {
        val running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
        val address = privateIpv4Address()
        addressText.text = address?.let { "http://$it:${LocalApiServer.PORT}" }
            ?: "Connect this phone to a private Wi-Fi network."
        statusText.text = if (running) "●  Panel service is running" else "○  Panel service is stopped"
        statusText.setTextColor(if (running) Color.rgb(22, 163, 74) else Color.rgb(185, 28, 28))
        serviceButton.text = if (running) "Stop local panel" else "Start local panel"

        val devices = DeviceRepository.all(this)
        val online = devices.count { System.currentTimeMillis() - it.optLong("lastSeenAt", 0L) <= 90_000L }
        countText.text = "${devices.size} devices · $online online"
        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(text("No devices enrolled yet. Install SkySpy Companion on another Android phone, enter the address above, and approve the diagnostics notice.", 13f, Color.rgb(100, 116, 139), false))
        } else {
            devices.forEach { record -> deviceList.addView(deviceCard(record), params(10)) }
        }
    }

    private fun deviceCard(record: org.json.JSONObject): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val name = record.optString("name", "Android device")
        val model = record.optString("model", "Unknown model")
        val online = System.currentTimeMillis() - record.optLong("lastSeenAt", 0L) <= 90_000L
        card.addView(text(name, 16f, Color.rgb(15, 23, 42), true))
        card.addView(
            text("$model  ·  ${if (online) "Online" else "Offline"}", 12f,
                if (online) Color.rgb(22, 163, 74) else Color.rgb(100, 116, 139), true, 5)
        )
        val battery = if (record.isNull("batteryPercent")) "—" else "${record.optInt("batteryPercent")}%" 
        val androidVersion = record.optString("androidVersion", "—")
        val manufacturer = record.optString("manufacturer", "")
        card.addView(text("$manufacturer  ·  $androidVersion  ·  Battery $battery", 12f, Color.rgb(71, 85, 105), false, 5))
        val seen = record.optLong("lastSeenAt", 0L)
        card.addView(text("Last heartbeat: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seen))}", 11f, Color.rgb(148, 163, 184), false, 5))
        val revoke = button("Revoke enrollment")
        revoke.setBackgroundColor(Color.rgb(254, 226, 226))
        revoke.setTextColor(Color.rgb(185, 28, 28))
        revoke.setOnClickListener {
            DeviceRepository.revokeById(this, record.optString("id"))
            render()
        }
        card.addView(revoke, params(10))
        return card
    }

    private fun copyAddress() {
        val address = privateIpv4Address()
        if (address == null) {
            statusText.text = "No private IPv4 address is available."
            return
        }
        val value = "http://$address:${LocalApiServer.PORT}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SkySpy connection address", value))
        statusText.text = "Connection address copied."
    }

    private fun privateIpv4Address(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    val b = address.address.map { it.toInt() and 0xff }
                    b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
                }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5201)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minHeight = dp(46)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(37, 99, 235))
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        top: Int = 0
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
        setLineSpacing(dp(3).toFloat(), 1f)
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "skyspy_controller_data"
        private const val KEY_RUNNING = "api_running"
    }
}
