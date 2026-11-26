package id.skyspy.companion

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val securityPatch: String,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val appVersion: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("androidVersion", androidVersion)
        .put("securityPatch", securityPatch)
        .put("batteryPercent", batteryPercent ?: JSONObject.NULL)
        .put("charging", charging ?: JSONObject.NULL)
        .put("appVersion", appVersion)

    companion object {
        fun collect(context: Context): DeviceInfo {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
            } else {
                null
            }

            val status = batteryIntent?.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )
            val isCharging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }

            val manufacturer = Build.MANUFACTURER.orEmpty()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val model = Build.MODEL.orEmpty().ifBlank { "Android device" }

            return DeviceInfo(
                manufacturer = manufacturer,
                model = model,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
                batteryPercent = percent,
                charging = isCharging,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }
}
