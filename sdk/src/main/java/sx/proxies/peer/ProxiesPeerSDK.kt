package sx.proxies.peer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import sx.proxies.peer.service.PeerProxyService
import java.util.concurrent.TimeUnit

/**
 * Proxies.sx Peer SDK
 *
 * Integrates bandwidth sharing into your Android app.
 * Users earn rewards for sharing their unused mobile bandwidth.
 *
 * Usage:
 * ```kotlin
 * // Initialize with your developer API key
 * val sdk = ProxiesPeerSDK.init(context, "your-api-key")
 *
 * // Start sharing (usually when app is in background/idle)
 * sdk.start()
 *
 * // Stop sharing
 * sdk.stop()
 *
 * // Get earnings
 * val earnings = sdk.getEarnings()
 * ```
 */
class ProxiesPeerSDK private constructor(
    private val context: Context,
    private val apiKey: String,
    private val config: Config
) {
    companion object {
        private const val TAG = "ProxiesPeerSDK"
        // Production URLs
        private const val DEFAULT_API_URL = "https://api.proxies.sx/v1"
        private const val DEFAULT_RELAY_URL = "wss://relay.proxies.sx"
        // Development URLs (uncomment for local testing)
        // private const val DEFAULT_API_URL = "http://10.0.2.2:4500/v1"  // Android emulator localhost
        // private const val DEFAULT_RELAY_URL = "ws://10.0.2.2:8080"

        private var instance: ProxiesPeerSDK? = null

        /**
         * Initialize the SDK with your developer API key.
         * Call this once, typically in Application.onCreate()
         */
        @JvmStatic
        fun init(
            context: Context,
            apiKey: String,
            config: Config = Config()
        ): ProxiesPeerSDK {
            if (instance == null) {
                instance = ProxiesPeerSDK(context.applicationContext, apiKey, config)
            }
            return instance!!
        }

        /**
         * Get the SDK instance. Must call init() first.
         */
        @JvmStatic
        fun getInstance(): ProxiesPeerSDK {
            return instance ?: throw IllegalStateException("SDK not initialized. Call init() first.")
        }
    }

    data class Config(
        val apiUrl: String = DEFAULT_API_URL,
        val relayUrl: String = DEFAULT_RELAY_URL,
        val userId: String? = null, // Optional: link to your user system
        val onEarningsUpdate: ((EarningsInfo) -> Unit)? = null,
        val onStatusChange: ((Status) -> Unit)? = null
    )

    data class EarningsInfo(
        val totalEarnedCents: Int,
        val todayEarnedCents: Int,
        val totalTrafficMB: Double,
        val todayTrafficMB: Double
    )

    enum class Status {
        STOPPED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private var deviceId: String? = null
    private var deviceToken: String? = null
    private var currentStatus = Status.STOPPED

    private val prefs by lazy {
        context.getSharedPreferences("proxies_peer_sdk", Context.MODE_PRIVATE)
    }

    init {
        // Load persisted device ID
        deviceId = prefs.getString("device_id", null)
    }

    /**
     * Start the bandwidth sharing service.
     * Registers the device if needed and connects to the relay.
     */
    fun start() {
        Log.d(TAG, "Starting SDK")
        updateStatus(Status.CONNECTING)

        scope.launch {
            try {
                // Register or get token for device
                if (deviceId == null || deviceToken == null) {
                    registerDevice()
                } else {
                    refreshToken()
                }

                // Start foreground service
                val token = deviceToken
                if (token != null) {
                    PeerProxyService.start(
                        context,
                        token,
                        config.relayUrl
                    )
                    // Note: Status will be CONNECTING until relay actually connects
                    // The service will update status via callbacks
                } else {
                    Log.e(TAG, "No device token available")
                    updateStatus(Status.ERROR)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start", e)
                updateStatus(Status.ERROR)
            }
        }
    }

    /**
     * Stop the bandwidth sharing service.
     */
    fun stop() {
        Log.d(TAG, "Stopping SDK")
        PeerProxyService.stop(context)
        updateStatus(Status.STOPPED)
    }

    /**
     * Check if the service is currently running.
     */
    fun isRunning(): Boolean {
        return currentStatus == Status.CONNECTED
    }

    /**
     * Get current status.
     */
    fun getStatus(): Status {
        return currentStatus
    }

    /**
     * Get earnings information.
     */
    suspend fun getEarnings(): EarningsInfo {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${config.apiUrl}/peer/earnings/${deviceId}")
                    .header("X-API-Key", apiKey)
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        val json = gson.fromJson(body, JsonObject::class.java)
                        EarningsInfo(
                            totalEarnedCents = json?.get("totalEarnedCents")?.asInt ?: 0,
                            todayEarnedCents = json?.get("todayEarnedCents")?.asInt ?: 0,
                            totalTrafficMB = json?.get("totalTrafficMB")?.asDouble ?: 0.0,
                            todayTrafficMB = json?.get("todayTrafficMB")?.asDouble ?: 0.0
                        )
                    } else {
                        EarningsInfo(0, 0, 0.0, 0.0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get earnings", e)
                EarningsInfo(0, 0, 0.0, 0.0)
            }
        }
    }

    private suspend fun registerDevice() {
        Log.d(TAG, "Registering device")

        val deviceInfo = mapOf(
            "deviceId" to getUniqueDeviceId(),
            "model" to android.os.Build.MODEL,
            "osVersion" to "Android ${android.os.Build.VERSION.RELEASE}",
            "appVersion" to getAppVersion(),
            "country" to getCountryCode(),
            "carrier" to getCarrierName(),
            "sdkVersion" to "1.0.1",
            "apiKey" to apiKey,
            "userId" to config.userId
        )

        val json = gson.toJson(deviceInfo)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${config.apiUrl}/peer/register")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (resp.isSuccessful) {
                val responseBody = resp.body?.string()
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)

                deviceId = responseJson?.get("device")?.asJsonObject?.get("deviceId")?.asString
                deviceToken = responseJson?.get("token")?.asString

                // Persist device ID
                prefs.edit().putString("device_id", deviceId).apply()

                Log.d(TAG, "Device registered: $deviceId")
            } else {
                throw Exception("Registration failed: ${resp.code}")
            }
        }
    }

    private suspend fun refreshToken() {
        Log.d(TAG, "Refreshing token for device: $deviceId")

        val request = Request.Builder()
            .url("${config.apiUrl}/peer/token/$deviceId")
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (resp.isSuccessful) {
                val responseBody = resp.body?.string()
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                deviceToken = responseJson?.get("token")?.asString
                Log.d(TAG, "Token refreshed")
            } else if (resp.code == 404) {
                // Device not found, re-register
                deviceId = null
                registerDevice()
            } else {
                throw Exception("Token refresh failed: ${resp.code}")
            }
        }
    }

    private fun updateStatus(status: Status) {
        currentStatus = status
        config.onStatusChange?.invoke(status)
    }

    private fun getUniqueDeviceId(): String {
        // Use Android ID as base, combined with app-specific salt
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: java.util.UUID.randomUUID().toString().replace("-", "")
        return "psx_${androidId.take(16)}"
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun getCountryCode(): String {
        return java.util.Locale.getDefault().country
    }

    private fun getCarrierName(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.networkOperatorName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
