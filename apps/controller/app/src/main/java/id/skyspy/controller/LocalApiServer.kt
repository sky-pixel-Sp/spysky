package id.skyspy.controller

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalApiServer(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newFixedThreadPool(4)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket(PORT, 20, InetAddress.getByName("0.0.0.0"))
            socket.reuseAddress = true
            serverSocket = socket
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        workers.execute { handleClient(client) }
                    } catch (_: SocketException) {
                        if (running.get()) stop()
                    } catch (_: Exception) {
                        if (running.get()) stop()
                    }
                }
            }, "SkySpy-API-Acceptor").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            running.set(false)
            throw error
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workers.shutdownNow()
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 10_000
                val remote = client.inetAddress
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input, MAX_HEADER_BYTES) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeResponse(client.getOutputStream(), 400, error("Malformed request."))
                    return
                }

                val headers = linkedMapOf<String, String>()
                var headerBytes = requestLine.length
                while (true) {
                    val line = readLine(input, MAX_HEADER_BYTES - headerBytes) ?: break
                    headerBytes += line.length + 2
                    if (headerBytes > MAX_HEADER_BYTES) {
                        writeResponse(client.getOutputStream(), 431, error("Request headers are too large."))
                        return
                    }
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                            line.substring(separator + 1).trim()
                    }
                }

                val origin = headers["origin"]
                if (!isPrivateLan(remote) || origin != null) {
                    writeResponse(client.getOutputStream(), 403, error("Private LAN native-app requests only."))
                    return
                }

                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length < 0 || length > MAX_BODY_BYTES) {
                    writeResponse(client.getOutputStream(), 413, error("Request body is too large."))
                    return
                }
                val body = readExact(input, length)
                val bodyJson = if (body.isEmpty()) JSONObject() else {
                    try {
                        JSONObject(String(body, Charsets.UTF_8))
                    } catch (_: Exception) {
                        writeResponse(client.getOutputStream(), 400, error("Request body must be valid JSON."))
                        return
                    }
                }

                val response = route(parts[0].uppercase(Locale.ROOT), parts[1], headers, bodyJson)
                writeResponse(client.getOutputStream(), response.first, response.second)
            } catch (_: Exception) {
                try {
                    writeResponse(client.getOutputStream(), 400, error("Invalid request."))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: JSONObject
    ): Pair<Int, JSONObject> {
        if (method == "GET" && path == "/health") {
            return 200 to JSONObject()
                .put("ok", true)
                .put("service", "skyspy-android-controller")
                .put("version", "0.4.0")
        }

        if (method != "POST") return 404 to error("Route not found.")

        return when (path) {
            "/api/device/register" -> {
                if (!body.optBoolean("consent", false)) {
                    403 to error("The device owner must approve the listed diagnostics.")
                } else {
                    val installId = body.optString("installId")
                    val metadata = body.optJSONObject("device")
                    if (metadata == null || metadata.optString("model").isBlank()) {
                        400 to error("Device model is required.")
                    } else {
                        try {
                            201 to DeviceRepository.register(
                                appContext,
                                installId,
                                body.optString("deviceName").takeIf { it.isNotBlank() },
                                metadata
                            )
                        } catch (_: IllegalArgumentException) {
                            400 to error("Companion installation identity is invalid.")
                        } catch (_: Exception) {
                            500 to error("Device registration failed.")
                        }
                    }
                }
            }

            "/api/device/heartbeat" -> {
                val token = bearerToken(headers)
                if (token.isBlank()) {
                    401 to error("A device session is required.")
                } else {
                    val updated = DeviceRepository.heartbeat(appContext, token, body.optJSONObject("device"))
                    if (updated == null) 401 to error("Device session is invalid. Reconnect Companion.")
                    else 200 to updated
                }
            }

            "/api/device/unpair" -> {
                val token = bearerToken(headers)
                if (token.isBlank() || !DeviceRepository.unpair(appContext, token)) {
                    401 to error("Device session is invalid.")
                } else {
                    200 to JSONObject().put("ok", true).put("message", "Device removed from Controller.")
                }
            }

            else -> 404 to error("Route not found.")
        }
    }

    private fun bearerToken(headers: Map<String, String>): String {
        val value = headers["authorization"] ?: return ""
        val prefix = "Bearer "
        return if (value.startsWith(prefix, ignoreCase = true)) value.substring(prefix.length).trim() else ""
    }

    private fun writeResponse(output: OutputStream, status: Int, payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        if (maxBytes <= 0) throw IllegalArgumentException("Header too large.")
        val buffer = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
            if (value == 10 && previous == 13) break
            if (previous >= 0) buffer.write(previous)
            previous = value
            if (buffer.size() > maxBytes) throw IllegalArgumentException("Line too long.")
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw IllegalArgumentException("Incomplete request body.")
            offset += count
        }
        return result
    }

    private fun isPrivateLan(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun error(message: String) = JSONObject().put("error", message)

    companion object {
        const val PORT = 8787
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 16 * 1024
    }
}
