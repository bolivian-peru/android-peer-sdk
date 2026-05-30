package sx.proxies.peer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
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
    // Mutable: a server-driven relay_redirect rewrites this and the existing
    // reconnect path dials the new value. Starts at the geo-assigned relay.
    private var relayUrl: String,
    // Operator pinned a relay in Config -> ignore all runtime redirects.
    private val relayPinned: Boolean = false,
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

        // ─── Multi-region relay routing (v1.3.0 — May 2026) ──────────────
        //
        // Whichever relay a peer lands on can tell it (via `relay_redirect`)
        // to reconnect to a nearer one for its geo. Anti-flap guard: honor at
        // most one redirect per 60s so a flapping geo-classification can't
        // ping-pong the device between relays.
        private const val RELAY_REDIRECT_MIN_INTERVAL = 60000L // 60 seconds
        // Defense-in-depth: only ever redirect to a *.proxies.sx wss URL so a
        // spoofed/compromised message can't point peers at an attacker host.
        private val RELAY_URL_REGEX =
            Regex("^wss://[a-z0-9.-]+\\.proxies\\.sx(?:/|$)", RegexOption.IGNORE_CASE)
        // WS close code used when tearing down a socket to follow a redirect.
        private const val WS_CLOSE_RELAY_REDIRECT = 4100

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

        // Defensive cap on inbound WS frames (the relay caps tunnel frames
        // at 4 MB server-side). A frame larger than this is treated as
        // corrupt/hostile and dropped so a single message can't OOM the
        // device. Small slack added for the binary frame header.
        private const val MAX_INBOUND_FRAME_BYTES = 4 * 1024 * 1024 + 1024

        // Hard cap on simultaneously open device→target tunnels. Prevents a
        // misbehaving/compromised relay from exhausting FDs/threads on the
        // device. New tunnel_connect beyond this is refused.
        private const val MAX_ACTIVE_TUNNELS = 256

        // Idle tunnel reaper: a tunnel with no traffic in either direction
        // for this long is closed. Guards against half-open peers that never
        // send a FIN (soTimeout is 0, so the read loop would otherwise block
        // forever and leak the socket).
        private const val TUNNEL_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val TUNNEL_REAPER_INTERVAL_MS = 60 * 1000L
    }

    /**
     * One open device→target tunnel. All writes to [socket] go through
     * [writes], drained by exactly one [writerJob] coroutine so inbound
     * frames are written in the order the relay sent them. Launching a
     * write coroutine per frame (the pre-v1.3.1 behavior) let writes for
     * the same session race on Dispatchers.IO and interleave/reorder bytes,
     * corrupting the TCP stream under load.
     */
    private class TunnelSession(
        val socket: Socket,
        val writes: Channel<ByteArray>,
        val writerJob: Job,
    ) {
        @Volatile var lastActivityAt: Long = 0L
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

    // Read from multiple IO threads (send paths) and written on connect/
    // reconnect — volatile so a send never observes a stale socket.
    @Volatile private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var deviceId: String? = null
    @Volatile private var isConnected = false
    @Volatile private var shouldReconnect = true
    @Volatile private var isReconnecting = false
    private var reconnectAttempt = 0
    private var publicIp: String = ""
    @Volatile private var lastRedirectAt = 0L

    // Network monitoring: when connectivity returns after an outage we reset
    // the backoff counter and reconnect immediately, instead of staying dead
    // after MAX_RECONNECT_ATTEMPTS is exhausted (a foreground service holding
    // a wakelock but never reconnecting is the worst outcome).
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Flipped to true after the relay sends back its `connected` ack,
    // which is the platform's signal that our `protocol: "binary-v1"`
    // advertisement was processed and the relay will accept binary
    // frames from this device. Until then we stay on JSON (safe path).
    @Volatile private var binaryMode = false


    // Pending response callbacks
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<ProxyResponse>>()

    // Active tunnel connections (sessionId -> session with its single writer)
    private val activeTunnels = ConcurrentHashMap<String, TunnelSession>()

    private fun session(sessionId: String): TunnelSession? = activeTunnels[sessionId]

    fun connect() {
        // Recreate scope if it was cancelled by disconnect()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        registerNetworkCallback()
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

                // Send device info immediately. We no longer call a
                // third-party IP-echo service (api.ipify.org) here: the relay
                // sees our real source IP and echoes it back in the
                // `connected` ack (stored in publicIp for the next handshake).
                scope.launch {
                    sendDeviceInfo()
                    startHeartbeat()
                    startTunnelReaper()
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

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Connectivity returned. If we're meant to be up but aren't,
                // reset backoff and reconnect now rather than waiting out the
                // exponential delay (or staying dead past the attempt cap).
                if (shouldReconnect && !isConnected && !isReconnecting) {
                    DebugLogger.i("Network available — resetting backoff and reconnecting")
                    reconnectAttempt = 0
                    connect()
                }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            DebugLogger.w("Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager
        val callback = networkCallback
        if (cm != null && callback != null) {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    /**
     * Periodically close tunnels that have seen no traffic for
     * [TUNNEL_IDLE_TIMEOUT_MS]. Because tunnel sockets use soTimeout=0, a
     * half-open peer (no FIN, no data) would otherwise block the read loop
     * forever and leak the socket/thread.
     */
    private fun startTunnelReaper() {
        scope.launch {
            while (isConnected) {
                delay(TUNNEL_REAPER_INTERVAL_MS)
                val now = System.currentTimeMillis()
                activeTunnels.forEach { (sessionId, session) ->
                    if (now - session.lastActivityAt > TUNNEL_IDLE_TIMEOUT_MS) {
                        DebugLogger.d("Reaping idle tunnel: $sessionId")
                        closeTunnel(sessionId)
                    }
                }
            }
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
            // Opt in to server-driven nearest-relay routing (v1.3.0). Peers
            // that don't advertise this are never sent a relay_redirect.
            "supportsRelayRedirect" to true,
            "sdkVersion" to sx.proxies.peer.ProxiesPeerSDK.SDK_VERSION,
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
                    // Prefer the source IP the relay observed for us — it is
                    // authoritative and avoids a round-trip to a third-party
                    // IP-echo service (api.ipify.org) on every connect.
                    payload?.get("ip")?.asString?.let {
                        if (it.isNotEmpty()) publicIp = it
                    }
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

                "relay_redirect" -> {
                    payload?.let { handleRelayRedirect(it) }
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

    /**
     * Handle a server-driven relay redirect: the relay tells us a nearer relay
     * exists for our geo. Switch the active relay URL and reconnect there using
     * the existing reconnect path. Mirrors the Node reference SDK / skill.md.
     */
    private fun handleRelayRedirect(payload: JsonObject) {
        val target = payload.get("relay")?.asString
        val reason = payload.get("reason")?.asString ?: "geo"

        // Operator pin wins: respect the integrator's explicit relay choice.
        if (relayPinned) {
            DebugLogger.d("Ignoring relay_redirect: operator pinned relay")
            return
        }
        // Defense-in-depth: only redirect to a *.proxies.sx wss URL.
        if (target == null || !RELAY_URL_REGEX.containsMatchIn(target)) {
            DebugLogger.w("Ignoring relay_redirect: invalid target=$target")
            return
        }
        // No-op if we're already on the target relay.
        if (target == relayUrl) {
            DebugLogger.d("Ignoring relay_redirect: already on $target")
            return
        }
        // Anti-flap: at most one honored redirect per 60s.
        val now = System.currentTimeMillis()
        if (now - lastRedirectAt < RELAY_REDIRECT_MIN_INTERVAL) {
            DebugLogger.d("Ignoring relay_redirect: anti-flap guard (<60s)")
            return
        }
        lastRedirectAt = now

        DebugLogger.i("Relay redirect: $relayUrl -> $target ($reason)")
        relayUrl = target
        // Tear down the current socket; onClosed -> handleDisconnect() reconnects
        // to the updated relayUrl via the existing exponential-backoff path.
        webSocket?.close(WS_CLOSE_RELAY_REDIRECT, "relay_redirect")
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

            // SSRF guard: reject internal/loopback/link-local targets.
            val targetHost = try { java.net.URI(url).host } catch (e: Exception) { null }
            if (targetHost == null || !EgressFilter.isAllowedTarget(targetHost)) {
                DebugLogger.w("Refusing HTTP proxy to blocked host: $targetHost")
                sendTunnelData(
                    sessionId,
                    "HTTP/1.1 403 Forbidden\r\n\r\nblocked target".toByteArray(),
                )
                return@launch
            }

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

                // Send response back through tunnel (binary frame when the
                // relay has ack'd binary mode — same hot-path win as the
                // CONNECT-tunnel read loop).
                sendTunnelData(sessionId, combined)

                // Track traffic
                onTrafficUpdate(
                    (bodyBytes?.size ?: 0).toLong(),
                    combined.size.toLong()
                )

                DebugLogger.d("HTTP proxy response: ${response.code} (${combined.size} bytes)")

            } catch (e: Exception) {
                DebugLogger.e("HTTP proxy error: ${e.message}", e)
                val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n\r\n${e.message}"
                sendTunnelData(sessionId, errorResponse.toByteArray())
            }
        }
    }

    /**
     * Send tunnel payload back to the relay. Uses a raw binary frame once
     * the relay has acknowledged binary mode (zero base64/JSON overhead),
     * falling back to the legacy base64 JSON envelope otherwise.
     */
    private fun sendTunnelData(sessionId: String, data: ByteArray) {
        val ws = webSocket
        if (binaryMode && ws != null) {
            ws.send(encodeBinaryTunnelFrame(MSG_TUNNEL_DATA, sessionId, data, data.size))
        } else {
            sendMessage("tunnel_data", mapOf(
                "sessionId" to sessionId,
                "data" to android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            ))
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

        // SSRF guard: never let the relay point a peer at the device's own
        // loopback, its LAN, or cloud link-local/metadata ranges.
        if (!EgressFilter.isAllowedTarget(host)) {
            DebugLogger.w("Refusing tunnel to blocked host: $host")
            sendMessage("tunnel_closed", mapOf(
                "sessionId" to sessionId,
                "error" to "blocked target"
            ))
            return
        }
        // Cap simultaneously open tunnels so a hostile relay can't exhaust FDs.
        if (activeTunnels.size >= MAX_ACTIVE_TUNNELS) {
            DebugLogger.w("Refusing tunnel: active tunnel cap ($MAX_ACTIVE_TUNNELS) reached")
            sendMessage("tunnel_closed", mapOf(
                "sessionId" to sessionId,
                "error" to "tunnel cap reached"
            ))
            return
        }

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
                socket.soTimeout = 0 // No read timeout for tunnel; idle reaper handles half-open

                // One writer per session: a single coroutine drains `writes`
                // and writes to the socket in arrival order. Inbound frames
                // enqueue here instead of each launching its own write, which
                // would let writes for one session race and corrupt the stream.
                val writes = Channel<ByteArray>(Channel.UNLIMITED)
                val writerJob = launch(Dispatchers.IO) {
                    try {
                        val out = socket.getOutputStream()
                        for (chunk in writes) {
                            out.write(chunk)
                            out.flush()
                            session(sessionId)?.lastActivityAt = System.currentTimeMillis()
                            onTrafficUpdate(chunk.size.toLong(), 0)
                        }
                    } catch (e: Exception) {
                        DebugLogger.d("Tunnel writer ended: ${e.message}")
                        closeTunnel(sessionId)
                    }
                }
                val tunnel = TunnelSession(socket, writes, writerJob)
                tunnel.lastActivityAt = System.currentTimeMillis()
                activeTunnels[sessionId] = tunnel

                DebugLogger.i("Tunnel connected to $host:$port")

                // Start reading from socket and forwarding to relay
                launch {
                    try {
                        val buffer = ByteArray(TUNNEL_READ_BUFFER_BYTES)
                        val input = socket.getInputStream()
                        while (!socket.isClosed && socket.isConnected) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            tunnel.lastActivityAt = System.currentTimeMillis()

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

        val session = activeTunnels[sessionId]
        if (session == null || session.socket.isClosed) {
            DebugLogger.d("Tunnel data for closed session: $sessionId")
            return
        }

        val data = try {
            android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            DebugLogger.e("Tunnel data decode error: ${e.message}")
            return
        }
        // Enqueue for the session's single ordered writer (see TunnelSession).
        enqueueWrite(sessionId, session, data)
    }

    /**
     * Hand a chunk to the session's single writer. Ordering is preserved
     * because callers enqueue from the (single-threaded) WS reader and the
     * channel is FIFO. trySend never blocks; it only fails if the writer
     * has been closed, in which case the session is already going away.
     */
    private fun enqueueWrite(sessionId: String, session: TunnelSession, data: ByteArray) {
        val result = session.writes.trySend(data)
        if (result.isFailure) {
            DebugLogger.d("Dropping write for closing session: $sessionId")
        }
    }

    private fun handleTunnelClose(payload: JsonObject) {
        val sessionId = payload.get("sessionId")?.asString ?: return
        DebugLogger.d("Tunnel close requested: $sessionId")
        closeTunnel(sessionId)
    }

    private fun closeTunnel(sessionId: String) {
        val session = activeTunnels.remove(sessionId) ?: run {
            // Already closed by another path; don't double-notify the relay.
            return
        }
        session.writes.close()
        session.writerJob.cancel()
        try {
            session.socket.close()
        } catch (e: Exception) {
            // Ignore close errors
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
    ): ByteString = BinaryTunnelCodec.encode(type, sessionId, payload, payloadLen).toByteString()

    /**
     * Decodes inbound binary tunnel_data / tunnel_close frames from the
     * relay and dispatches to the active tunnel socket directly — zero
     * JSON parse, zero base64 decode.
     */
    private fun handleBinaryMessage(bytes: ByteString) {
        try {
            // Defensive cap: a frame larger than the relay's server-side
            // limit is corrupt or hostile — drop it rather than allocate.
            if (bytes.size > MAX_INBOUND_FRAME_BYTES) {
                DebugLogger.w("Dropping oversized binary frame: ${bytes.size} bytes")
                return
            }
            val frame = BinaryTunnelCodec.decode(bytes.toByteArray()) ?: return
            val sessionId = frame.sessionId

            when (frame.type) {
                MSG_TUNNEL_DATA -> {
                    val session = activeTunnels[sessionId]
                    if (session == null || session.socket.isClosed) return
                    // The decoded payload is a fresh array (codec copies it),
                    // so it can safely outlive this frame on the writer queue.
                    enqueueWrite(sessionId, session, frame.payload)
                }
                MSG_TUNNEL_CLOSE -> {
                    DebugLogger.d("Binary tunnel close: $sessionId")
                    closeTunnel(sessionId)
                }
                else -> {
                    // Unknown binary message type — relay may add new
                    // types in future; ignore for forward compat.
                    DebugLogger.d("Unknown binary frame type: ${frame.type}")
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

        unregisterNetworkCallback()

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
