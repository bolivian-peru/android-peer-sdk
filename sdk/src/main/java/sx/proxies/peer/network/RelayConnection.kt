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
import okio.ByteString
import okio.ByteString.Companion.toByteString
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
        private const val RECONNECT_DELAY_BASE = 5000L  // 5 seconds
        private const val RECONNECT_DELAY_MAX = 120000L  // 2 minutes max
        private const val MAX_RECONNECT_ATTEMPTS = 50

        // ─── Binary tunnel protocol (v1.2.0 — May 2026) ──────────────────
        //
        // The relay supports a binary WS frame layout for tunnel_data that
        // skips the base64+JSON overhead of the legacy protocol. Format:
        //
        //   byte 0       : message type (0x01 = tunnel_data, 0x03 = tunnel_close)
        //   byte 1       : sessionId length in UTF-8 bytes (≤ 255)
        //   bytes 2..N+1 : sessionId UTF-8 bytes
        //   bytes N+2..  : raw payload bytes (no base64, no JSON envelope)
        //
        // Net throughput gain on a typical mobile peer: 4–10× over the
        // legacy JSON path. CPU overhead on encode drops from ~480ms per
        // 1 MB transfer to ~30ms. See SDK-V1.2.0-BINARY-PROTOCOL-PLAN.md
        // in the platform repo for the full rationale.
        private const val MSG_TUNNEL_DATA: Byte = 0x01
        private const val MSG_TUNNEL_CLOSE: Byte = 0x03

        // v1.2.0 bumped this from 32 KB → 64 KB to halve the frame count
        // per MB of traffic. v1.2.1 bumps it again to 256 KB — at this
        // size the per-frame framing overhead is negligible vs. the
        // payload, and OkHttp / ws library frame caps (4 MB) leave
        // plenty of headroom. The kernel recv buffer (256 KB below) is
        // sized to match so a full read can land in one round.
        //
        // Bigger isn't always better: too-large frames hold the WS
        // send queue while one frame compresses (permessage-deflate)
        // or encrypts (TLS). 256 KB is the sweet spot we measured on
        // production mobile peers.
        private const val TUNNEL_READ_BUFFER_BYTES = 262_144

        // Larger kernel receive buffer on the device→target socket so
        // the kernel absorbs more bytes between our reads. Kept under
        // typical mobile carrier socket buffer caps (~512 KB).
        private const val TUNNEL_SOCKET_RECV_BUFFER_BYTES = 262_144
    }

    // WebSocket client. OkHttp 4.x auto-negotiates the permessage-deflate
    // RFC 7692 extension whenever the server advertises it in the upgrade
    // response — no explicit client config needed. The relay enables it
    // server-side in v1.2.1 (relay-server/src/index.ts), so binary
    // tunnel_data frames now ship through an LZ77-compressed pipe for
    // free. Typical scrape workloads (HTML/JSON/JS) compress 2–5×.
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
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var deviceId: String? = null
    @Volatile private var isConnected = false
    @Volatile private var shouldReconnect = true
    @Volatile private var isReconnecting = false
    private var reconnectAttempt = 0
    private var publicIp: String = ""

    // Flipped to true after the relay sends back its `connected` ack,
    // which is the platform's signal that our `protocol: "binary-v1"`
    // advertisement was processed and the relay will accept binary
    // frames from this device. Until then we stay on JSON (safe path).
    @Volatile private var binaryMode = false


    // Pending response callbacks
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<ProxyResponse>>()

    // Active tunnel connections (sessionId -> Socket)
    private val activeTunnels = ConcurrentHashMap<String, Socket>()

    fun connect() {
        // Recreate scope if it was cancelled by disconnect()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        // Close any existing connection before reconnecting
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        isReconnecting = false

        DebugLogger.d("Connecting to relay: ${relayUrl.take(30)}...")

        val request = Request.Builder()
            .url(relayUrl)
            .header("Sec-WebSocket-Protocol", "token.$token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.i("WebSocket connected!")
                isConnected = true
                reconnectAttempt = 0  // Reset on successful connection

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

            // Binary frames carry tunnel_data on the hot path (v1.2.0+).
            // Control messages (tunnel_connect, device_info, etc.) still
            // arrive as text, so this only fires for the perf-critical
            // bytes-flowing path. We dispatch to the binary handler
            // without any JSON parse or base64 decode.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes)
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

        // protocol: "binary-v1" tells the relay this peer accepts binary
        // tunnel_data frames. The relay will subsequently send tunnel
        // data as raw WS binary instead of base64+JSON — eliminating
        // the encoding overhead that capped v1.1.x peers at ~70 KB/s.
        // See SDK-V1.2.0-BINARY-PROTOCOL-PLAN.md for full rationale.
        val deviceInfo = mapOf(
            "country" to countryCode,
            "carrier" to carrierName,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "osVersion" to "Android ${Build.VERSION.RELEASE}",
            "currentIp" to publicIp,
            "protocol" to "binary-v1",
        )

        DebugLogger.d("Sending device info: country=$countryCode, carrier=$carrierName, ip=$publicIp, protocol=binary-v1")
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
                    // Relay has processed our `device_info` advertising
                    // `protocol: "binary-v1"` — safe to start sending
                    // tunnel_data as binary frames. Until this flips,
                    // any tunnel_data we send (shouldn't happen this
                    // early, but defensive) stays on the legacy JSON
                    // path so an old relay would still understand us.
                    binaryMode = true
                    DebugLogger.i("Connected as device: $deviceId (binary tunnel protocol active)")
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
                // TCP tuning on the device→target socket (v1.2.0):
                //   tcpNoDelay = true     — disable Nagle so small writes
                //                           don't sit 40ms in the kernel
                //                           waiting for piggyback ACK
                //   receiveBufferSize 256K — let the kernel buffer more
                //                           bytes between our reads, so
                //                           fewer trips through the WS
                //                           encode loop per MB.
                // Combined with the 64KB user-space read buffer, this is
                // the difference between mobile peers serving customer
                // traffic at 70 KB/s vs 1+ MB/s.
                val socket = Socket()
                try { socket.tcpNoDelay = true } catch (_: Exception) {}
                try { socket.receiveBufferSize = TUNNEL_SOCKET_RECV_BUFFER_BYTES } catch (_: Exception) {}
                socket.connect(InetSocketAddress(host, port), 30000)
                socket.soTimeout = 0 // No read timeout for tunnel

                activeTunnels[sessionId] = socket

                DebugLogger.i("Tunnel connected to $host:$port")

                // Start reading from socket and forwarding to relay
                launch {
                    try {
                        val buffer = ByteArray(TUNNEL_READ_BUFFER_BYTES)
                        val input = socket.getInputStream()
                        while (!socket.isClosed && socket.isConnected) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break

                            // Hot path. After register, `binaryMode` is true →
                            // we encode directly into a raw WS binary frame
                            // (zero base64, zero JSON). Legacy JSON path kept
                            // for pre-v1.1.x relays that wouldn't understand
                            // binary frames (shouldn't happen — backend
                            // upgraded May 2026 — but defensive).
                            if (binaryMode) {
                                val ws = webSocket
                                if (ws != null) {
                                    val frame = encodeBinaryTunnelFrame(
                                        MSG_TUNNEL_DATA, sessionId, buffer, bytesRead,
                                    )
                                    ws.send(frame)
                                }
                            } else {
                                val data = buffer.copyOf(bytesRead)
                                sendMessage("tunnel_data", mapOf(
                                    "sessionId" to sessionId,
                                    "data" to android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP),
                                ))
                            }
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

    // ─── Binary tunnel codec (v1.2.0) ────────────────────────────────────
    //
    // Frame layout matches relay-server/src/index.ts encodeBinaryTunnelData
    // exactly, byte-for-byte:
    //
    //   [0]            type           (MSG_TUNNEL_DATA / MSG_TUNNEL_CLOSE)
    //   [1]            sidLen         (UTF-8 byte count of sessionId, ≤ 255)
    //   [2..2+sidLen)  sessionId      (UTF-8 bytes)
    //   [2+sidLen..]   payload        (raw bytes from target socket)
    //
    // No allocations on the hot path beyond the output ByteArray itself
    // (vs. v1.1.x which allocated: chunk copy + Base64 string + JSON
    // string + UTF-8 bytes — 4 allocations per chunk + GC pressure).
    private fun encodeBinaryTunnelFrame(
        type: Byte,
        sessionId: String,
        payload: ByteArray,
        payloadLen: Int = payload.size,
    ): ByteString {
        val sidBytes = sessionId.toByteArray(Charsets.UTF_8)
        require(sidBytes.size <= 255) { "sessionId too long for binary frame" }
        val out = ByteArray(2 + sidBytes.size + payloadLen)
        out[0] = type
        out[1] = sidBytes.size.toByte()
        System.arraycopy(sidBytes, 0, out, 2, sidBytes.size)
        System.arraycopy(payload, 0, out, 2 + sidBytes.size, payloadLen)
        return out.toByteString()
    }

    /**
     * Decodes inbound binary tunnel_data / tunnel_close frames from the
     * relay and dispatches to the active tunnel socket directly — zero
     * JSON parse, zero base64 decode.
     */
    private fun handleBinaryMessage(bytes: ByteString) {
        try {
            val buf = bytes.toByteArray()
            if (buf.size < 2) return
            val type = buf[0]
            val sidLen = buf[1].toInt() and 0xFF
            if (buf.size < 2 + sidLen) return
            val sessionId = String(buf, 2, sidLen, Charsets.UTF_8)

            when (type) {
                MSG_TUNNEL_DATA -> {
                    val socket = activeTunnels[sessionId]
                    if (socket == null || socket.isClosed) return
                    // Slice payload without an extra copy where possible:
                    // OutputStream.write(buf, off, len) writes a window
                    // into our existing buffer.
                    val payloadOff = 2 + sidLen
                    val payloadLen = buf.size - payloadOff
                    scope.launch(Dispatchers.IO) {
                        try {
                            val out = socket.getOutputStream()
                            out.write(buf, payloadOff, payloadLen)
                            out.flush()
                            onTrafficUpdate(payloadLen.toLong(), 0)
                        } catch (e: Exception) {
                            DebugLogger.e("Tunnel binary write error: ${e.message}", e)
                            closeTunnel(sessionId)
                        }
                    }
                }
                MSG_TUNNEL_CLOSE -> {
                    DebugLogger.d("Binary tunnel close: $sessionId")
                    closeTunnel(sessionId)
                }
                else -> {
                    // Unknown binary message type — relay may add new
                    // types in future; ignore for forward compat.
                    DebugLogger.d("Unknown binary frame type: $type")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("Binary frame decode error: ${e.message}", e)
        }
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
        // Force a clean re-handshake of the binary protocol on reconnect —
        // we'd never want to send binary frames to a relay that hasn't
        // ack'd our `device_info` yet on the new session.
        binaryMode = false

        // Close all active tunnels
        activeTunnels.keys.toList().forEach { closeTunnel(it) }

        onDisconnected()

        if (shouldReconnect && !isReconnecting && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            isReconnecting = true
            reconnectAttempt++
            // Exponential backoff: 5s, 10s, 20s, 40s... up to 2 minutes
            val delay = minOf(RECONNECT_DELAY_BASE * (1L shl minOf(reconnectAttempt - 1, 5)), RECONNECT_DELAY_MAX)
            DebugLogger.d("Reconnecting in ${delay/1000}s (attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)...")

            scope.launch {
                delay(delay)
                if (shouldReconnect) {
                    connect()
                } else {
                    isReconnecting = false
                }
            }
        } else if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            DebugLogger.w("Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up.")
        }
    }

    fun disconnect() {
        shouldReconnect = false
        isConnected = false
        isReconnecting = false
        reconnectAttempt = 0

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
