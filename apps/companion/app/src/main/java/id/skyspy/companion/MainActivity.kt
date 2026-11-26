package id.skyspy.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private lateinit var serverInput: EditText
    private lateinit var consentCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var registeredText: TextView
    private lateinit var connectButton: Button
    private lateinit var setupSection: LinearLayout
    private lateinit var registeredSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderRegistrationState()
        requestNotificationPermissionOnce()
    }

    override fun onResume() {
        super.onResume()
        startAutoConnectIfEnrolled()
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

        content.addView(text("Automatic device connection", 26f, Color.WHITE, true), params(top = 18))
        content.addView(
            text(
                "After the device owner approves the first connection, SkySpy reconnects automatically whenever Companion is opened. A visible Android notification indicates that the connection service is running.",
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

        setupSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setupSection.addView(text("ONE-TIME CONNECTION SETUP", 10f, Color.rgb(130, 148, 173), true), params(top = 25))
        serverInput = editText("Enter the server address for first connection")
        serverInput.setText(preferences.getString(KEY_SERVER_URL, ""))
        setupSection.addView(text("SkySpy server address", 12f, Color.rgb(155, 169, 190), false), params(top = 10))
        setupSection.addView(serverInput, params(top = 6))

        consentCheck = CheckBox(this).apply {
            text = "I own or administer this device and agree to share only the diagnostics listed above with this SkySpy server."
            setTextColor(Color.rgb(201, 215, 233))
            textSize = 12f
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(111, 157, 255))
            setPadding(0, dp(8), 0, dp(8))
        }
        setupSection.addView(consentCheck, params(top = 14))

        connectButton = button("Approve and connect once")
        connectButton.isEnabled = false
        connectButton.setOnClickListener { registerDevice() }
        setupSection.addView(connectButton, params(top = 10))
        content.addView(setupSection)

        registeredSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        registeredSection.addView(text("AUTOMATIC CONNECTION", 10f, Color.rgb(130, 148, 173), true), params(top = 25))
        registeredText = text("", 13f, Color.rgb(204, 216, 232), false)
        registeredSection.addView(registeredText, params(top = 8))
        val removeButton = button("Remove this device from Control")
        removeButton.setBackgroundColor(Color.rgb(64, 73, 88))
        removeButton.setOnClickListener { unpairDevice() }
        registeredSection.addView(removeButton, params(top = 16))
        content.addView(registeredSection)

        statusText = text("Waiting for first-time setup.", 12f, Color.rgb(151, 165, 185), false)
        content.addView(statusText, params(top = 22))
        content.addView(
            text(
                "Once approved, the saved device identity is used for automatic reconnection. The connection remains visible through Android's foreground-service notification. The owner can remove this device from the panel here.",
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
            statusText.text = "Approve the diagnostics notice before connecting."
            return
        }

        connectButton.isEnabled = false
        statusText.text = "Registering this owner-approved device…"
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
                    .putBoolean(KEY_OWNER_APPROVED, true)
                    .apply()

                runOnUiThread {
                    renderRegistrationState()
                    startAutoConnectIfEnrolled()
                    statusText.text = "Approved. Automatic connection is now active."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Connection failed: ${error.message ?: "Unknown error"}"
                    connectButton.isEnabled = consentCheck.isChecked
                }
            }
        }.start()
    }

    private fun startAutoConnectIfEnrolled() {
        val server = preferences.getString(KEY_SERVER_URL, null)
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)
        val approved = preferences.getBoolean(KEY_OWNER_APPROVED, false)
        if (!approved || server.isNullOrBlank() || token.isNullOrBlank()) return

        val serviceIntent = Intent(this, AutoConnectService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "Automatic connection service is active."
        } catch (error: Exception) {
            statusText.text = "Could not start the visible connection service: ${error.message ?: "Android restricted startup"}"
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (preferences.getBoolean(KEY_NOTIFICATION_REQUESTED, false)) return
        preferences.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
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
                    .remove(KEY_OWNER_APPROVED)
                    .apply()
                runOnUiThread {
                    stopService(Intent(this, AutoConnectService::class.java))
                    renderRegistrationState()
                    statusText.text = "Device removed from Control."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Removal could not be confirmed: ${error.message ?: "Server unavailable"}"
                }
            }
        }.start()
    }

    private fun renderRegistrationState() {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
        val server = preferences.getString(KEY_SERVER_URL, null)
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)
        val approved = preferences.getBoolean(KEY_OWNER_APPROVED, false)
        val enrolled = approved && !deviceId.isNullOrBlank() && !server.isNullOrBlank() && !token.isNullOrBlank()

        setupSection.visibility = if (enrolled) View.GONE else View.VISIBLE
        registeredSection.visibility = if (enrolled) View.VISIBLE else View.GONE
        registeredText.text = if (enrolled) {
            "Device ID: ${deviceId}\nServer: ${server}\nReconnect mode: automatic"
        } else {
            ""
        }
        if (!enrolled) statusText.text = "Waiting for first-time owner approval."
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
        private const val PREFS_NAME = "skyspy_companion"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_OWNER_APPROVED = "owner_approved"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        private const val NOTIFICATION_PERMISSION_REQUEST = 4102
    }
}
