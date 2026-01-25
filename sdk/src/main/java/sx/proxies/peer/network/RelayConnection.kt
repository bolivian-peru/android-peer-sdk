package sx.proxies.peer.network

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.*
import sx.proxies.peer.service.ProxyRequest
import sx.proxies.peer.service.ProxyResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RelayConnection(
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

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var deviceId: String? = null
    private var isConnected = false
    private var shouldReconnect = true

    // Pending response callbacks
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<ProxyResponse>>()

    fun connect() {
        val url = "$relayUrl?token=$token"
        Log.d(TAG, "Connecting to relay: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                sendDeviceInfo()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                handleDisconnect()
            }
        })
    }

    private fun sendDeviceInfo() {
        // Get device info
        val deviceInfo = mapOf(
            "country" to getCountryCode(),
            "carrier" to getCarrierName(),
            "model" to Build.MODEL,
            "osVersion" to "Android ${Build.VERSION.RELEASE}",
            "currentIp" to getPublicIp()
        )

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
                    Log.d(TAG, "Connected as device: $deviceId")
                    deviceId?.let { onConnected(it) }
                }

                "proxy_request" -> {
                    payload?.let { handleProxyRequest(it) }
                }

                "tunnel_request" -> {
                    payload?.let { handleTunnelRequest(it) }
                }

                "tunnel_data" -> {
                    payload?.let { handleTunnelData(it) }
                }

                "tunnel_close" -> {
                    payload?.let { handleTunnelClose(it) }
                }

                "heartbeat_ack" -> {
                    Log.v(TAG, "Heartbeat acknowledged")
                }

                "http_response" -> {
                    // Handle response for sendHttpRequest calls
                    payload?.let { handleHttpResponse(it) }
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun handleProxyRequest(payload: JsonObject) {
        scope.launch {
            try {
                val requestId = payload.get("requestId")?.asString
                val method = payload.get("method")?.asString
                val url = payload.get("url")?.asString

                if (requestId == null || method == null || url == null) {
                    Log.e(TAG, "Invalid proxy request: missing required fields")
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

                Log.d(TAG, "Proxy request: ${request.method} ${request.url}")

                val response = onProxyRequest(request)
                sendProxyResponse(response)

                // Track traffic (use actual byte count, not base64 length)
                val requestBytes = request.body?.let {
                    try { android.util.Base64.decode(it, android.util.Base64.DEFAULT).size.toLong() } catch (e: Exception) { 0L }
                } ?: 0L
                val responseBytes = try {
                    android.util.Base64.decode(response.body, android.util.Base64.DEFAULT).size.toLong()
                } catch (e: Exception) { 0L }
                onTrafficUpdate(requestBytes, responseBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling proxy request", e)
                val requestId = payload.get("requestId")?.asString
                if (requestId != null) {
                    sendProxyError(requestId, e.message ?: "Unknown error")
                }
            }
        }
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
            Log.e(TAG, "Error handling HTTP response", e)
        }
    }

    private fun handleTunnelRequest(payload: JsonObject) {
        // Handle HTTPS CONNECT tunneling
        val tunnelId = payload.get("tunnelId")?.asString ?: return
        val target = payload.get("target")?.asString ?: return
        Log.d(TAG, "Tunnel request: $tunnelId -> $target")

        // TODO: Implement actual TCP tunnel
        // For MVP, acknowledge and set up socket connection
    }

    private fun handleTunnelData(payload: JsonObject) {
        val tunnelId = payload.get("tunnelId")?.asString ?: return
        val data = payload.get("data")?.asString ?: return
        Log.d(TAG, "Tunnel data for: $tunnelId, ${data.length} chars")
        // Forward data to the appropriate tunnel socket
    }

    private fun handleTunnelClose(payload: JsonObject) {
        val tunnelId = payload.get("tunnelId")?.asString ?: return
        Log.d(TAG, "Tunnel closed: $tunnelId")
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
        onDisconnected()

        if (shouldReconnect) {
            scope.launch {
                delay(RECONNECT_DELAY)
                if (shouldReconnect) {
                    Log.d(TAG, "Attempting to reconnect...")
                    connect()
                }
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        isConnected = false
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
            // Clean up on timeout to prevent memory leak
            pendingResponses.remove(requestId)
            throw e
        }
    }

    // Helper functions
    private fun getCountryCode(): String {
        // In production, use TelephonyManager or IP geolocation
        return java.util.Locale.getDefault().country
    }

    private fun getCarrierName(): String {
        // In production, use TelephonyManager.getNetworkOperatorName()
        return "Unknown"
    }

    private fun getPublicIp(): String {
        // In production, fetch from a service like ipify
        return ""
    }
}
