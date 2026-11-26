package id.skyspy.controller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object DeviceRepository {
    private const val PREFS = "skyspy_controller_data"
    private const val KEY_DEVICES = "devices"
    private val random = SecureRandom()

    @Synchronized
    fun all(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { JSONObject(it.toString()) }
        }.sortedByDescending { it.optLong("lastSeenAt", 0L) }
    }

    @Synchronized
    fun register(
        context: Context,
        installId: String,
        deviceName: String?,
        metadata: JSONObject
    ): JSONObject {
        val installUuid = UUID.fromString(installId)
        val installHash = sha256(installUuid.toString())
        val devices = readArray(context)
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHash = sha256(token)
        val now = System.currentTimeMillis()
        var found = -1

        for (index in 0 until devices.length()) {
            if (devices.optJSONObject(index)?.optString("installIdHash") == installHash) {
                found = index
                break
            }
        }

        val previous = if (found >= 0) devices.optJSONObject(found) else null
        val model = clean(metadata.optString("model"), 80).ifBlank { "Android device" }
        val name = clean(deviceName.orEmpty(), 60).ifBlank { model }
        val record = JSONObject()
            .put("id", previous?.optString("id").orEmpty().ifBlank { UUID.randomUUID().toString() })
            .put("installIdHash", installHash)
            .put("tokenHash", tokenHash)
            .put("name", name)
            .put("manufacturer", clean(metadata.optString("manufacturer"), 50))
            .put("model", model)
            .put("androidVersion", clean(metadata.optString("androidVersion"), 50))
            .put("securityPatch", clean(metadata.optString("securityPatch"), 20))
            .put("batteryPercent", optionalInt(metadata, "batteryPercent"))
            .put("charging", optionalBoolean(metadata, "charging"))
            .put("appVersion", clean(metadata.optString("appVersion"), 30))
            .put("createdAt", previous?.optLong("createdAt", now) ?: now)
            .put("lastSeenAt", now)

        if (found >= 0) devices.put(found, record) else devices.put(record)
        save(context, devices)

        return JSONObject()
            .put("deviceId", record.getString("id"))
            .put("deviceToken", token)
            .put("registeredAt", record.getLong("createdAt"))
            .put("message", "Device registered after owner approval.")
    }

    @Synchronized
    fun heartbeat(context: Context, token: String, metadata: JSONObject?): JSONObject? {
        val expectedHash = sha256(token)
        val devices = readArray(context)
        for (index in 0 until devices.length()) {
            val old = devices.optJSONObject(index) ?: continue
            if (!constantTimeEquals(old.optString("tokenHash"), expectedHash)) continue

            val updated = JSONObject(old.toString())
                .put("lastSeenAt", System.currentTimeMillis())
            if (metadata != null) {
                copyText(metadata, updated, "manufacturer", 50)
                copyText(metadata, updated, "model", 80)
                copyText(metadata, updated, "androidVersion", 50)
                copyText(metadata, updated, "securityPatch", 20)
                copyText(metadata, updated, "appVersion", 30)
                if (!metadata.isNull("batteryPercent")) {
                    updated.put("batteryPercent", metadata.optInt("batteryPercent").coerceIn(0, 100))
                }
                if (!metadata.isNull("charging")) updated.put("charging", metadata.optBoolean("charging"))
            }
            devices.put(index, updated)
            save(context, devices)
            return JSONObject()
                .put("ok", true)
                .put("deviceId", updated.getString("id"))
                .put("lastSeenAt", updated.getLong("lastSeenAt"))
        }
        return null
    }

    @Synchronized
    fun unpair(context: Context, token: String): Boolean {
        val expectedHash = sha256(token)
        val devices = readArray(context)
        for (index in 0 until devices.length()) {
            val record = devices.optJSONObject(index) ?: continue
            if (constantTimeEquals(record.optString("tokenHash"), expectedHash)) {
                devices.remove(index)
                save(context, devices)
                return true
            }
        }
        return false
    }

    @Synchronized
    fun revokeById(context: Context, id: String): Boolean {
        val devices = readArray(context)
        for (index in 0 until devices.length()) {
            if (devices.optJSONObject(index)?.optString("id") == id) {
                devices.remove(index)
                save(context, devices)
                return true
            }
        }
        return false
    }

    private fun readArray(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, "[]") ?: "[]"
        return JSONArray(raw)
    }

    private fun save(context: Context, devices: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICES, devices.toString())
            .apply()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun clean(value: String, max: Int): String =
        value.trim().filterNot(Char::isISOControl).take(max)

    private fun optionalInt(source: JSONObject, key: String): Any =
        if (source.isNull(key)) JSONObject.NULL else source.optInt(key).coerceIn(0, 100)

    private fun optionalBoolean(source: JSONObject, key: String): Any =
        if (source.isNull(key)) JSONObject.NULL else source.optBoolean(key)

    private fun copyText(source: JSONObject, target: JSONObject, key: String, max: Int) {
        val value = clean(source.optString(key), max)
        if (value.isNotBlank()) target.put(key, value)
    }
}
