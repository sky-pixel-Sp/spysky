package id.skyspy.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ControllerService : Service() {
    private var apiServer: LocalApiServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Local panel is starting on port ${LocalApiServer.PORT}.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            if (apiServer == null) {
                apiServer = LocalApiServer(this).also { it.start() }
            }
            preferences().edit().putBoolean(KEY_RUNNING, true).apply()
            updateNotification("Panel active on your private Wi-Fi · port ${LocalApiServer.PORT}")
        } catch (error: Exception) {
            preferences().edit().putBoolean(KEY_RUNNING, false).apply()
            updateNotification("Could not start the local panel: ${error.message ?: "port unavailable"}")
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        apiServer?.stop()
        apiServer = null
        preferences().edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun preferences() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SkySpy Controller service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when this Android phone is hosting the SkySpy local panel."
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(message: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launchIntent?.let {
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SkySpy Controller is active")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        private const val PREFS = "skyspy_controller_data"
        private const val KEY_RUNNING = "api_running"
        private const val CHANNEL_ID = "skyspy_controller_service"
        private const val NOTIFICATION_ID = 2702
    }
}
