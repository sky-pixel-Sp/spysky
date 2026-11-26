package id.skyspy.companion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("skyspy_companion", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var serverInput: EditText
    private lateinit var consentCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var registeredText: TextView
    private lateinit var connectButton: Button

    private val heartbeatTask = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderRegistrationState()
    }

    override fun onResume() {
        super.onResume()
        if (preferences.getString(KEY_DEVICE_TOKEN, null) != null) {
            handler.removeCallbacks(heartbeatTask)
            handler.post(heartbeatTask)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(heartbeatTask)
        super.onPause()
    }

    private fun buildScreen() {
        window.statusBarColor = Color.rgb(13, 18, 27)
        window.navigationBarColor = Color.rgb(13, 18, 27)
        window.decorView.setBackgroundColor(Color.rgb(13, 18, 27))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(34))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "SKYSPY  /  COMPANION"
            setTextColor(Color.rgb(139, 177, 255))
            textSize = 11f
            letterSpacing = 0.14f
        })

        content.addView(text("Connect your device.", 27f, Color.WHITE, true), params(top = 18))
        content.addView(
            text(
                "Your device appears in SkySpy Control only after you choose to connect and approve sharing the diagnostics below.",
                14f,
                Color.rgb(155, 169, 190),
                false
            ),
            params(top = 10)
        )

        val consentCard = card()
        consentCard.addView(text("INFORMATION SHARED", 10f, Color.rgb(139, 177, 255), true))
        consentCard.addView(
            text(
                "• Manufacturer and model\n• Android version and security patch\n• Battery level and charging state\n• App version and connection status",
                13f,
                Color.rgb(204, 216, 232),
                false
            ),
            params(top = 10)
        )
        consentCard.addView(
            text(
                "No SMS, contacts, call history, notifications, passwords, camera, microphone, location, or screen contents are collected.",
                12f,
                Color.rgb(238, 190, 145),
                false
            ),
            params(top = 12)
        )
        content.addView(consentCard, params(top = 25))

        content.addView(text("SKYSPY SERVER ADDRESS", 10f, Color.rgb(130, 148, 173), true), params(top = 25))
        serverInput = editText("http://192.168.1.10:8787")
        serverInput.setText(preferences.getString(KEY_SERVER_URL, ""))
        content.addView(serverInput, params(top = 8))

        consentCheck = CheckBox(this).apply {
            text = "I understand and agree to share only the diagnostics listed above with this SkySpy server."
            setTextColor(Color.rgb(201, 215, 233))
            textSize = 12f
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(111, 157, 255))
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(consentCheck, params(top = 16))

        connectButton = button("Connect this device")
        connectButton.isEnabled = false
        connectButton.setOnClickListener { registerDevice() }
        content.addView(connectButton, params(top = 12))

        registeredText = text("", 12f, Color.rgb(160, 190, 235), false)
        content.addView(registeredText, params(top = 18))

        val unpairButton = button("Disconnect and remove device")
        unpairButton.setBackgroundColor(Color.rgb(46, 56, 71))
        unpairButton.setOnClickListener { unpairDevice() }
        content.addView(unpairButton, params(top = 12))

        statusText = text("Not connected", 12f, Color.rgb(151, 165, 185), false)
        content.addView(statusText, params(top = 22))
        content.addView(
            text(
                "The app sends a heartbeat while this screen is open. Disconnect here or uninstall Companion at any time. No pairing code or operator password is required on this device.",
                11f,
                Color.rgb(107, 122, 143),
                false
            ),
            params(top = 10)
        )

        consentCheck.setOnCheckedChangeListener { _, checked ->
            connectButton.isEnabled = checked
        }
    }

    private fun installationId(): String {
        val existing = preferences.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    private fun registerDevice() {
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        if (!ApiClient.isAllowedServerUrl(baseUrl)) {
            statusText.text = "Use HTTPS remotely, or a private IPv4 address for trusted LAN."
            return
        }
        if (!consentCheck.isChecked) {
            statusText.text = "Approve the diagnostics sharing notice before connecting."
            return
        }

        connectButton.isEnabled = false
        statusText.text = "Connecting this device to SkySpy Control…"
        Thread {
            try {
                val info = DeviceInfo.collect(this)
                val response = ApiClient.postJson(
                    baseUrl,
                    "/api/device/register",
                    JSONObject()
                        .put("installId", installationId())
                        .put("consent", true)
                        .put("device", info.toJson())
                )
                val deviceId = response.getString("deviceId")
                val deviceToken = response.getString("deviceToken")
                preferences.edit()
                    .putString(KEY_SERVER_URL, baseUrl)
                    .putString(KEY_DEVICE_ID, deviceId)
                    .putString(KEY_DEVICE_TOKEN, deviceToken)
                    .apply()
                runOnUiThread {
                    renderRegistrationState()
                    statusText.text = "Connected. This device should now appear in SkySpy Control."
                    handler.removeCallbacks(heartbeatTask)
                    handler.post(heartbeatTask)
                    consentCheck.isChecked = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Connection failed: ${error.message ?: "Unknown error"}"
                    connectButton.isEnabled = consentCheck.isChecked
                }
            }
        }.start()
    }

    private fun sendHeartbeat() {
        val baseUrl = preferences.getString(KEY_SERVER_URL, null) ?: return
        val token = preferences.getString(KEY_DEVICE_TOKEN, null) ?: return
        Thread {
            try {
                ApiClient.postJson(
                    baseUrl,
                    "/api/device/heartbeat",
                    JSONObject().put("device", DeviceInfo.collect(this).toJson()),
                    token
                )
                runOnUiThread { statusText.text = "Connected · heartbeat sent just now" }
            } catch (error: Exception) {
                runOnUiThread { statusText.text = "Connection issue: ${error.message ?: "Server unavailable"}" }
            }
        }.start()
    }

    private fun unpairDevice() {
        val baseUrl = preferences.getString(KEY_SERVER_URL, null)
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            statusText.text = "This device is not connected."
            return
        }

        statusText.text = "Removing this device from Control…"
        Thread {
            try {
                ApiClient.postJson(baseUrl, "/api/device/unpair", JSONObject(), token)
                preferences.edit()
                    .remove(KEY_SERVER_URL)
                    .remove(KEY_DEVICE_ID)
                    .remove(KEY_DEVICE_TOKEN)
                    .apply()
                runOnUiThread {
                    handler.removeCallbacks(heartbeatTask)
                    renderRegistrationState()
                    statusText.text = "Disconnected. This device was removed from Control."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Disconnect could not be confirmed: ${error.message ?: "Server unavailable"}"
                }
            }
        }.start()
    }

    private fun renderRegistrationState() {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
        val server = preferences.getString(KEY_SERVER_URL, null)
        registeredText.text = if (deviceId.isNullOrBlank()) {
            "This device is not connected to a Control panel."
        } else {
            "Connected device ID: " + deviceId + "\nServer: " + server
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.rgb(25, 36, 53))
    }

    private fun editText(hintValue: String): EditText = EditText(this).apply {
        hint = hintValue
        setHintTextColor(Color.rgb(111, 128, 150))
        setTextColor(Color.WHITE)
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        setBackgroundColor(Color.rgb(25, 33, 46))
    }

    private fun button(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(62, 112, 218))
        minHeight = dp(48)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun params(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_INSTALL_ID = "install_id"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
