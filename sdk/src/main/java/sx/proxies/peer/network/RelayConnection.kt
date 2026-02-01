package sx.proxies.peer.network

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import sx.proxies.peer.service.ProxyRequest
import sx.proxies.peer.service.ProxyResponse
import sx.proxies.peer.util.DebugLogger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RelayConnection(
    private val context: Context,
    private val relayUrl: String,
    private val token: String,
    private val onConnected: (deviceId: String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onProxyRequest: suspend (ProxyRequest) -> ProxyResponse,
    private val onTrafficUpdate: (bytesIn: Long, bytesOut: Long) -> Unit
) {
    companion object {
        private const val TAG = "RelayConnection"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val RECONNECT_DELAY = 5000L // 5 seconds
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var deviceId: String? = null
    private var isConnected = false
    private var shouldReconnect = true
    private var publicIp: String = ""

    // Pending response callbacks
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<ProxyResponse>>()

    // Active tunnel connections (sessionId -> Socket)
    private val activeTunnels = ConcurrentHashMap<String, Socket>()

    fun connect() {
        val url = "$relayUrl?token=$token"
        DebugLogger.d("Connecting to relay: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.i("WebSocket connected!")
                isConnected = true

                // Fetch public IP first, then send device info
                scope.launch {
                    fetchPublicIp()
                    sendDeviceInfo()
                    startHeartbeat()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogger.d("WebSocket closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogger.d("WebSocket closed: $code - $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLogger.e("WebSocket failure: ${t.message}", t)
                handleDisconnect()
            }
        })
    }

    private suspend fun fetchPublicIp() {
        try {
            val request = Request.Builder()
                .url("https://api.ipify.org?format=json")
                .build()

            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val json = gson.fromJson(body, JsonObject::class.java)
                        publicIp = json.get("ip")?.asString ?: ""
                        DebugLogger.i("Public IP: $publicIp")
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("Failed to get public IP: ${e.message}")
        }
    }

    private fun sendDeviceInfo() {
        val countryCode = getCountryFromNetwork()
        val carrierName = getCarrierName()

        val deviceInfo = mapOf(
            "country" to countryCode,
            "carrier" to carrierName,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "osVersion" to "Android ${Build.VERSION.RELEASE}",
            "currentIp" to publicIp
        )

        DebugLogger.d("Sending device info: country=$countryCode, carrier=$carrierName, ip=$publicIp")
        sendMessage("device_info", deviceInfo)
    }

    private fun handleMessage(text: String) {
        try {
            val message = gson.fromJson(text, JsonObject::class.java)
            val type = message.get("type")?.asString ?: return
            val payload = message.get("payload")?.asJsonObject

            when (type) {
                "connected" -> {
                    deviceId = payload?.get("deviceId")?.asString
                    DebugLogger.i("Connected as device: $deviceId")
                    deviceId?.let { onConnected(it) }
                }

                "proxy_request" -> {
                    payload?.let { handleProxyRequest(it) }
                }

                "proxy_http_request" -> {
                    payload?.let { handleProxyHttpRequest(it) }
                }

                "tunnel_connect" -> {
                    payload?.let { handleTunnelConnect(it) }
                }

                "tunnel_open" -> {
                    payload?.let { handleTunnelOpen(it) }
                }

                "tunnel_data" -> {
                    payload?.let { handleTunnelData(it) }
                }

                "tunnel_close" -> {
                    payload?.let { handleTunnelClose(it) }
                }

                "heartbeat_ack" -> {
                    DebugLogger.v("Heartbeat acknowledged")
                }

                "http_response" -> {
                    payload?.let { handleHttpResponse(it) }
                }

                "error" -> {
                    val errorMsg = payload?.get("message")?.asString ?: "Unknown error"
                    DebugLogger.e("Relay error: $errorMsg")
                }

                else -> {
                    DebugLogger.d("Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("Error handling message: ${e.message}", e)
        }
    }

    private fun handleProxyRequest(payload: JsonObject) {
        scope.launch {
            try {
                val requestId = payload.get("requestId")?.asString
                val method = payload.get("method")?.asString
                val url = payload.get("url")?.asString

                if (requestId == null || method == null || url == null) {
                    DebugLogger.e("Invalid proxy request: missing required fields")
                    return@launch
                }

                val request = ProxyRequest(
                    requestId = requestId,
                    method = method,
                    url = url,
                    headers = payload.get("headers")?.asJsonObject?.entrySet()
                        ?.associate { it.key to it.value.asString } ?: emptyMap(),
                    body = payload.get("body")?.asString
                )

                DebugLogger.d("Proxy request: ${request.method} ${request.url}")

                val response = onProxyRequest(request)
                sendProxyResponse(response)

                // Track traffic
                val requestBytes = request.body?.let {
                    try { android.util.Base64.decode(it, android.util.Base64.DEFAULT).size.toLong() } catch (e: Exception) { 0L }
                } ?: 0L
                val responseBytes = try {
                    android.util.Base64.decode(response.body, android.util.Base64.DEFAULT).size.toLong()
                } catch (e: Exception) { 0L }
                onTrafficUpdate(requestBytes, responseBytes)
            } catch (e: Exception) {
                DebugLogger.e("Error handling proxy request: ${e.message}", e)
                val requestId = payload.get("requestId")?.asString
                if (requestId != null) {
                    sendProxyError(requestId, e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Handle HTTP proxy request - make the HTTP request and send response back
     */
    private fun handleProxyHttpRequest(payload: JsonObject) {
        scope.launch {
            val sessionId = payload.get("sessionId")?.asString ?: return@launch
            val method = payload.get("method")?.asString ?: "GET"
            val url = payload.get("url")?.asString ?: return@launch
            val headers = payload.get("headers")?.asJsonObject?.entrySet()
                ?.associate { it.key to it.value.asString } ?: emptyMap()
            val bodyBase64 = payload.get("body")?.asString

            DebugLogger.d("HTTP proxy request: $method $url (session: $sessionId)")

            try {
                val requestBuilder = Request.Builder()
                    .url(url)

                // Add headers
                headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                // Add body for POST/PUT/PATCH
                val bodyBytes = bodyBase64?.let {
                    android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                }

                val requestBody = when (method.uppercase()) {
                    "POST", "PUT", "PATCH" -> {
                        val contentType = headers["content-type"] ?: "application/octet-stream"
                        (bodyBytes ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
                    }
                    else -> null
                }

                requestBuilder.method(method.uppercase(), requestBody)

                val response = httpClient.newCall(requestBuilder.build()).execute()

                // Build response string
                val statusLine = "HTTP/1.1 ${response.code} ${response.message}\r\n"
                val responseHeaders = response.headers.toMultimap().entries
                    .flatMap { (key, values) -> values.map { "$key: $it" } }
                    .joinToString("\r\n")
                val responseBody = response.body?.bytes() ?: ByteArray(0)

                val fullResponse = StringBuilder()
                fullResponse.append(statusLine)
                fullResponse.append(responseHeaders)
                fullResponse.append("\r\n\r\n")

                val headerBytes = fullResponse.toString().toByteArray()
                val combined = headerBytes + responseBody

                // Send response back through tunnel
                sendMessage("tunnel_data", mapOf(
                    "sessionId" to sessionId,
                    "data" to android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
                ))

                // Track traffic
                onTrafficUpdate(
                    (bodyBytes?.size ?: 0).toLong(),
                    combined.size.toLong()
                )

                DebugLogger.d("HTTP proxy response: ${response.code} (${combined.size} bytes)")

            } catch (e: Exception) {
                DebugLogger.e("HTTP proxy error: ${e.message}", e)
                val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n\r\n${e.message}"
                sendMessage("tunnel_data", mapOf(
                    "sessionId" to sessionId,
                    "data" to android.util.Base64.encodeToString(errorResponse.toByteArray(), android.util.Base64.NO_WRAP)
                ))
            }
        }
    }

    /**
     * Handle HTTPS tunnel connect request
     */
    private fun handleTunnelConnect(payload: JsonObject) {
        val sessionId = payload.get("sessionId")?.asString ?: return
        val host = payload.get("host")?.asString ?: return
        val port = payload.get("port")?.asInt ?: 443

        DebugLogger.d("Tunnel connect: $host:$port (session: $sessionId)")

        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 30000)
                socket.soTimeout = 0 // No read timeout for tunnel

                activeTunnels[sessionId] = socket

                DebugLogger.i("Tunnel connected to $host:$port")

                // Start reading from socket and forwarding to relay
                launch {
                    try {
                        val buffer = ByteArray(32768)
                        val input = socket.getInputStream()
                        while (!socket.isClosed && socket.isConnected) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break

                            val data = buffer.copyOf(bytesRead)
                            sendMessage("tunnel_data", mapOf(
                                "sessionId" to sessionId,
                                "data" to android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                            ))
                            onTrafficUpdate(0, bytesRead.toLong())
                        }
                    } catch (e: Exception) {
                        DebugLogger.d("Tunnel read ended: ${e.message}")
                    } finally {
                        closeTunnel(sessionId)
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("Tunnel connect failed: ${e.message}", e)
                sendMessage("tunnel_closed", mapOf(
                    "sessionId" to sessionId,
                    "error" to (e.message ?: "Connection failed")
                ))
            }
        }
    }

    private fun handleTunnelOpen(payload: JsonObject) {
        val sessionId = payload.get("sessionId")?.asString ?: return
        DebugLogger.d("Tunnel opened: $sessionId")
    }

    private fun handleTunnelData(payload: JsonObject) {
        val sessionId = payload.get("sessionId")?.asString ?: return
        val dataBase64 = payload.get("data")?.asString ?: return

        val socket = activeTunnels[sessionId]
        if (socket == null || socket.isClosed) {
            DebugLogger.d("Tunnel data for closed session: $sessionId")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val data = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
                socket.getOutputStream().write(data)
                socket.getOutputStream().flush()
                onTrafficUpdate(data.size.toLong(), 0)
            } catch (e: Exception) {
                DebugLogger.e("Tunnel write error: ${e.message}", e)
                closeTunnel(sessionId)
            }
        }
    }

    private fun handleTunnelClose(payload: JsonObject) {
        val sessionId = payload.get("sessionId")?.asString ?: return
        DebugLogger.d("Tunnel close requested: $sessionId")
        closeTunnel(sessionId)
    }

    private fun closeTunnel(sessionId: String) {
        val socket = activeTunnels.remove(sessionId)
        socket?.let {
            try {
                it.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        sendMessage("tunnel_closed", mapOf("sessionId" to sessionId))
    }

    private fun handleHttpResponse(payload: JsonObject) {
        try {
            val requestId = payload.get("requestId")?.asString ?: return
            val deferred = pendingResponses.remove(requestId) ?: return

            val response = ProxyResponse(
                requestId = requestId,
                statusCode = payload.get("statusCode")?.asInt ?: 200,
                headers = payload.get("headers")?.asJsonObject?.entrySet()
                    ?.associate { it.key to it.value.asString } ?: emptyMap(),
                body = payload.get("body")?.asString ?: ""
            )
            deferred.complete(response)
        } catch (e: Exception) {
            DebugLogger.e("Error handling HTTP response: ${e.message}", e)
        }
    }

    private fun sendProxyResponse(response: ProxyResponse) {
        sendMessage("proxy_response", response)
    }

    private fun sendProxyError(requestId: String, error: String) {
        sendMessage("proxy_error", mapOf(
            "requestId" to requestId,
            "error" to error
        ))
    }

    private fun sendMessage(type: String, payload: Any) {
        val message = mapOf(
            "type" to type,
            "payload" to payload
        )
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isConnected) {
                delay(HEARTBEAT_INTERVAL)
                if (isConnected) {
                    sendMessage("heartbeat", mapOf("timestamp" to System.currentTimeMillis()))
                }
            }
        }
    }

    private fun handleDisconnect() {
        isConnected = false

        // Close all active tunnels
        activeTunnels.keys.toList().forEach { closeTunnel(it) }

        onDisconnected()

        if (shouldReconnect) {
            scope.launch {
                delay(RECONNECT_DELAY)
                if (shouldReconnect) {
                    DebugLogger.d("Attempting to reconnect...")
                    connect()
                }
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        isConnected = false

        // Close all active tunnels
        activeTunnels.keys.toList().forEach { closeTunnel(it) }

        webSocket?.close(1000, "User disconnected")
        webSocket = null
        scope.cancel()
    }

    suspend fun sendHttpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): ProxyResponse {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ProxyResponse>()
        pendingResponses[requestId] = deferred

        val request = ProxyRequest(
            requestId = requestId,
            method = method,
            url = url,
            headers = headers,
            body = body?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        )

        sendMessage("http_request", request)

        return try {
            withTimeout(30000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingResponses.remove(requestId)
            throw e
        }
    }

    /**
     * Get country code from network (SIM or mobile network)
     */
    private fun getCountryFromNetwork(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

            // Try network country first (actual network location)
            val networkCountry = tm?.networkCountryIso?.uppercase()
            if (!networkCountry.isNullOrEmpty()) {
                DebugLogger.d("Country from network: $networkCountry")
                return networkCountry
            }

            // Fall back to SIM country
            val simCountry = tm?.simCountryIso?.uppercase()
            if (!simCountry.isNullOrEmpty()) {
                DebugLogger.d("Country from SIM: $simCountry")
                return simCountry
            }

            // Last resort: device locale (not accurate)
            val localeCountry = java.util.Locale.getDefault().country
            DebugLogger.d("Country from locale (fallback): $localeCountry")
            localeCountry
        } catch (e: Exception) {
            DebugLogger.e("Error getting country: ${e.message}")
            java.util.Locale.getDefault().country
        }
    }

    /**
     * Get carrier name from TelephonyManager
     */
    private fun getCarrierName(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.networkOperatorName
            if (!carrier.isNullOrEmpty()) {
                DebugLogger.d("Carrier: $carrier")
                carrier
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            DebugLogger.e("Error getting carrier: ${e.message}")
            "Unknown"
        }
    }
}
