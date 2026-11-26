package id.skyspy.companion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("skyspy_companion", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var serverInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pairedText: TextView

    private val heartbeatTask = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderPairingState()
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

        val brand = TextView(this).apply {
            text = "SKYSPY  /  COMPANION"
            setTextColor(Color.rgb(139, 177, 255))
            textSize = 11f
            letterSpacing = 0.14f
        }
        content.addView(brand)

        val title = text("Your device. Your permission.", 25f, Color.WHITE, true)
        val titleParams = params(top = 18)
        content.addView(title, titleParams)

        content.addView(
            text(
                "This app shares a small set of device diagnostics with the SkySpy Control panel after you enter a pairing code.",
                14f,
                Color.rgb(155, 169, 190),
                false
            ),
            params(top = 10)
        )

        val consentCard = card()
        consentCard.addView(text("WHAT IS SHARED", 10f, Color.rgb(139, 177, 255), true))
        consentCard.addView(text("• Device manufacturer and model\n• Android version and security patch\n• Battery level and charging state\n• Connection heartbeat while this app is open", 13f, Color.rgb(204, 216, 232), false), params(top = 10))
        consentCard.addView(text("No SMS, contacts, notifications, passwords, camera, microphone, location, or screen content is collected.", 12f, Color.rgb(238, 190, 145), false), params(top = 12))
        content.addView(consentCard, params(top = 25))

        content.addView(text("SERVER ADDRESS", 10f, Color.rgb(130, 148, 173), true), params(top = 25))
        serverInput = editText("https://your-server.example")
        serverInput.setText(preferences.getString(KEY_SERVER_URL, ""))
        content.addView(serverInput, params(top = 8))

        content.addView(text("SIX-DIGIT PAIRING CODE", 10f, Color.rgb(130, 148, 173), true), params(top = 18))
        codeInput = editText("Enter code shown in Control")
        codeInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        content.addView(codeInput, params(top = 8))

        val pairButton = button("Pair this device")
        pairButton.setOnClickListener { pairDevice() }
        content.addView(pairButton, params(top = 18))

        pairedText = text("", 12f, Color.rgb(160, 190, 235), false)
        content.addView(pairedText, params(top = 18))

        val unpairButton = button("Unpair this device")
        unpairButton.setBackgroundColor(Color.rgb(46, 56, 71))
        unpairButton.setOnClickListener { unpairDevice() }
        content.addView(unpairButton, params(top = 10))

        statusText = text("Not connected", 12f, Color.rgb(151, 165, 185), false)
        content.addView(statusText, params(top = 22))
        content.addView(
            text("Heartbeat runs only while this Companion screen is open. You can unpair here or uninstall the app at any time.", 11f, Color.rgb(107, 122, 143), false),
            params(top = 10)
        )
    }

    private fun pairDevice() {
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        val code = codeInput.text.toString().trim()
        if (!ApiClient.isAllowedServerUrl(baseUrl)) {
            statusText.text = "Use HTTPS remotely, or a private IPv4 address for trusted LAN HTTP."
            return
        }
        if (!Regex("^\\d{6}$").matches(code)) {
            statusText.text = "Enter the six-digit code from SkySpy Control."
            return
        }

        statusText.text = "Connecting and requesting device pairing…"
        Thread {
            try {
                val info = DeviceInfo.collect(this)
                val response = ApiClient.postJson(
                    baseUrl,
                    "/api/pairing/claim",
                    JSONObject().put("code", code).put("device", info.toJson())
                )
                val deviceId = response.getString("deviceId")
                val deviceToken = response.getString("deviceToken")
                preferences.edit()
                    .putString(KEY_SERVER_URL, baseUrl)
                    .putString(KEY_DEVICE_ID, deviceId)
                    .putString(KEY_DEVICE_TOKEN, deviceToken)
                    .apply()
                runOnUiThread {
                    codeInput.text.clear()
                    renderPairingState()
                    statusText.text = "Paired successfully. Sending status while this app is open."
                    handler.removeCallbacks(heartbeatTask)
                    handler.post(heartbeatTask)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Pairing failed: ${error.message ?: "Unknown error"}"
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
                runOnUiThread { statusText.text = "Connected · last heartbeat just now" }
            } catch (error: Exception) {
                runOnUiThread { statusText.text = "Connection issue: ${error.message ?: "Server unavailable"}" }
            }
        }.start()
    }

    private fun unpairDevice() {
        val baseUrl = preferences.getString(KEY_SERVER_URL, null)
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            preferences.edit().clear().apply()
            renderPairingState()
            statusText.text = "This device is not paired."
            return
        }

        statusText.text = "Requesting server-side unpair…"
        Thread {
            try {
                ApiClient.postJson(baseUrl, "/api/device/unpair", JSONObject(), token)
                preferences.edit().clear().apply()
                runOnUiThread {
                    handler.removeCallbacks(heartbeatTask)
                    serverInput.setText("")
                    codeInput.text.clear()
                    renderPairingState()
                    statusText.text = "Unpaired. This device was removed from the Control inventory."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Unpair could not be confirmed: ${error.message ?: "Server unavailable"}. Pairing data was kept."
                }
            }
        }.start()
    }

    private fun renderPairingState() {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
        val server = preferences.getString(KEY_SERVER_URL, null)
        if (deviceId.isNullOrBlank()) {
            pairedText.text = "Not paired with a Control panel."
        } else {
            pairedText.text = "Paired device ID: $deviceId\nServer: $server"
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
        singleLine = true
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

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView = TextView(this).apply {
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
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
