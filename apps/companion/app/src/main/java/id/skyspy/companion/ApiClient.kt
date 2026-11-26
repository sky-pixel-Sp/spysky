package id.skyspy.companion

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    fun isAllowedServerUrl(raw: String): Boolean {
        return try {
            val url = URL(raw.trim().trimEnd('/'))
            when (url.protocol.lowercase()) {
                "https" -> true
                "http" -> isPrivateLanHost(url.host)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateLanHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when (octets[0]) {
            10 -> true
            127 -> true
            192 -> octets[1] == 168
            172 -> octets[1] in 16..31
            else -> false
        }
    }

    fun postJson(
        baseUrl: String,
        path: String,
        body: JSONObject,
        bearerToken: String? = null
    ): JSONObject {
        if (!isAllowedServerUrl(baseUrl)) {
            throw IOException("Use HTTPS for remote servers. Plain HTTP is allowed only for localhost/private LAN IPv4.")
        }

        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearerToken != null) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            if (status !in 200..299) {
                throw IOException(response.optString("error").ifBlank { "Server returned HTTP $status." })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
