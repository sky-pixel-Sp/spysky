package id.skyspy.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AutoConnectService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val requestInFlight = AtomicBoolean(false)
    @Volatile private var destroyed = false

    private val heartbeatLoop = object : Runnable {
        override fun run() {
            if (destroyed) return
            sendHeartbeat()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Connecting to your approved SkySpy panel…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enrolled = preferences.getBoolean(KEY_OWNER_APPROVED, false)
            && !preferences.getString(KEY_SERVER_URL, null).isNullOrBlank()
            && !preferences.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank()

        if (!enrolled) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        handler.removeCallbacks(heartbeatLoop)
        handler.post(heartbeatLoop)
        return START_STICKY
    }

    private fun sendHeartbeat() {
        if (!requestInFlight.compareAndSet(false, true)) {
            handler.postDelayed(heartbeatLoop, RETRY_INTERVAL_MS)
            return
        }

        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val server = preferences.getString(KEY_SERVER_URL, null)
        val token = preferences.getString(KEY_DEVICE_TOKEN, null)
        if (server.isNullOrBlank() || token.isNullOrBlank()) {
            requestInFlight.set(false)
            stopSelf()
            return
        }

        Thread {
            val outcome = try {
                ApiClient.postJson(
                    server,
                    "/api/device/heartbeat",
                    JSONObject().put("device", DeviceInfo.collect(this@AutoConnectService).toJson()),
                    token
                )
                "Connected · diagnostics synced"
            } catch (error: Exception) {
                "Reconnecting · ${error.message ?: "panel unavailable"}"
            }

            handler.post {
                requestInFlight.set(false)
                if (!destroyed) {
                    updateNotification(outcome)
                    handler.removeCallbacks(heartbeatLoop)
                    handler.postDelayed(heartbeatLoop, HEARTBEAT_INTERVAL_MS)
                }
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SkySpy device connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when this owner-approved device is connected to SkySpy Control."
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SkySpy Companion is active")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PREFS_NAME = "skyspy_companion"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_OWNER_APPROVED = "owner_approved"
        private const val CHANNEL_ID = "skyspy_connection"
        private const val NOTIFICATION_ID = 2701
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val RETRY_INTERVAL_MS = 30_000L
    }
}
