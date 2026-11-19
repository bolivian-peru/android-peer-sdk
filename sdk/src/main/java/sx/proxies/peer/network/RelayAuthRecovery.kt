package sx.proxies.peer.network

import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.decodeBase64

/** Refresh a rejected/expired credential once at a time, with a bounded auth budget. */
internal class RelayAuthRecovery(
    initialToken: String,
    private val refresh: suspend () -> String?,
) {
    private val mutex = Mutex()
    private var token = initialToken
    private var refreshAttempts = 0
    @Volatile private var refreshRequired = false
    @Volatile private var stopped = false

    fun rejected() { refreshRequired = true }
    fun authenticated() { refreshAttempts = 0 }
    fun stop() { stopped = true }

    suspend fun tokenForConnection(nowMillis: Long = System.currentTimeMillis()): String? = mutex.withLock {
        if (stopped) return@withLock null
        if (refreshRequired || expired(token, nowMillis)) {
            refreshRequired = true
            if (refreshAttempts >= 3) return@withLock null
            refreshAttempts++
            val replacement = refresh() ?: return@withLock null
            if (stopped) return@withLock null
            require(replacement.isNotBlank() && !expired(replacement, nowMillis)) {
                "Token refresh did not return a usable credential"
            }
            token = replacement
            refreshRequired = false
        }
        token
    }

    private fun expired(value: String, nowMillis: Long): Boolean {
        return try {
            val payload = value.split('.').getOrNull(1)?.decodeBase64()?.utf8() ?: return false
            val expiry = JsonParser.parseString(payload).asJsonObject.get("exp")?.asLong ?: return false
            expiry > 0 && expiry <= nowMillis / 1000
        } catch (_: Exception) {
            false // The server still verifies malformed/signature-invalid credentials.
        }
    }
}
