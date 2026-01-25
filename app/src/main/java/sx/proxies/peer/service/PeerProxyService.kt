package sx.proxies.peer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import sx.proxies.peer.R
import sx.proxies.peer.network.RelayConnection
import sx.proxies.peer.network.LocalProxyServer
import sx.proxies.peer.ui.MainActivity

class PeerProxyService : Service() {
    companion object {
        private const val TAG = "PeerProxyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "peer_proxy_channel"

        const val ACTION_START = "sx.proxies.peer.action.START"
        const val ACTION_STOP = "sx.proxies.peer.action.STOP"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        const val EXTRA_RELAY_URL = "relay_url"

        fun start(context: Context, deviceToken: String, relayUrl: String) {
            val intent = Intent(context, PeerProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_TOKEN, deviceToken)
                putExtra(EXTRA_RELAY_URL, relayUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PeerProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var relayConnection: RelayConnection? = null
    private var localProxyServer: LocalProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var totalBytesIn = 0L
    private var totalBytesOut = 0L
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_DEVICE_TOKEN) ?: return START_NOT_STICKY
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) ?: return START_NOT_STICKY
                startProxy(token, relayUrl)
                return START_STICKY
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    private fun startProxy(token: String, relayUrl: String) {
        Log.d(TAG, "Starting proxy service")

        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        serviceScope.launch {
            try {
                // Initialize relay connection
                relayConnection = RelayConnection(
                    relayUrl = relayUrl,
                    token = token,
                    onConnected = { deviceId ->
                        isConnected = true
                        updateNotification("Connected as $deviceId")
                        // Start local proxy server
                        startLocalProxy()
                    },
                    onDisconnected = {
                        isConnected = false
                        updateNotification("Disconnected")
                    },
                    onProxyRequest = { request ->
                        handleProxyRequest(request)
                    },
                    onTrafficUpdate = { bytesIn, bytesOut ->
                        totalBytesIn += bytesIn
                        totalBytesOut += bytesOut
                        updateNotificationStats()
                    }
                )

                relayConnection?.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                updateNotification("Connection failed: ${e.message}")
            }
        }
    }

    private fun startLocalProxy() {
        // Local HTTP proxy server that forwards to relay
        localProxyServer = LocalProxyServer(
            port = 8888,
            onRequest = { method, url, headers, body ->
                relayConnection?.sendHttpRequest(method, url, headers, body)
                    ?: ProxyResponse(
                        requestId = "",
                        statusCode = 503,
                        headers = mapOf("Content-Type" to "text/plain"),
                        body = android.util.Base64.encodeToString(
                            "Relay not connected".toByteArray(),
                            android.util.Base64.NO_WRAP
                        )
                    )
            }
        )
        localProxyServer?.start()
        Log.d(TAG, "Local proxy server started on port 8888")
    }

    private suspend fun handleProxyRequest(request: ProxyRequest): ProxyResponse {
        return withContext(Dispatchers.IO) {
            try {
                // Execute HTTP request through device's network
                val connection = java.net.URL(request.url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = request.method
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                // Set headers
                request.headers.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                // Send body if present
                if (request.body != null && request.body.isNotEmpty()) {
                    connection.doOutput = true
                    connection.outputStream.use { os ->
                        os.write(android.util.Base64.decode(request.body, android.util.Base64.DEFAULT))
                    }
                }

                // Read response
                val responseCode = connection.responseCode
                val responseHeaders = mutableMapOf<String, String>()
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        responseHeaders[key] = values.joinToString(", ")
                    }
                }

                val responseBody = try {
                    connection.inputStream.use { it.readBytes() }
                } catch (e: Exception) {
                    connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                }

                // Track traffic (use actual byte count, not base64 length)
                val requestBodyBytes = request.body?.let {
                    try { android.util.Base64.decode(it, android.util.Base64.DEFAULT).size } catch (e: Exception) { 0 }
                } ?: 0
                totalBytesIn += responseBody.size
                totalBytesOut += requestBodyBytes

                ProxyResponse(
                    requestId = request.requestId,
                    statusCode = responseCode,
                    headers = responseHeaders,
                    body = android.util.Base64.encodeToString(responseBody, android.util.Base64.NO_WRAP)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Proxy request failed: ${e.message}")
                ProxyResponse(
                    requestId = request.requestId,
                    statusCode = 502,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = android.util.Base64.encodeToString(
                        "Proxy error: ${e.message}".toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                )
            }
        }
    }

    private fun stopProxy() {
        Log.d(TAG, "Stopping proxy service")
        relayConnection?.disconnect()
        relayConnection = null
        localProxyServer?.stop()
        localProxyServer = null
        isConnected = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Peer Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when proxy service is running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PeerProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxies.sx Peer")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationStats() {
        val mbIn = totalBytesIn / (1024.0 * 1024.0)
        val mbOut = totalBytesOut / (1024.0 * 1024.0)
        updateNotification("↓ %.2f MB | ↑ %.2f MB".format(mbIn, mbOut))
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProxiesPeer::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire() // Acquire indefinitely while service runs
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxy()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}

// Data classes for proxy communication
data class ProxyRequest(
    val requestId: String,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?
)

data class ProxyResponse(
    val requestId: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)
