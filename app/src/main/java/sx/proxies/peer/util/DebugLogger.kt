package sx.proxies.peer.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug logger that captures logs for display in the UI.
 */
object DebugLogger {
    private const val TAG = "ProxiesPeer"
    private const val MAX_LOGS = 500

    private val logs = CopyOnWriteArrayList<String>()
    private var listener: ((String) -> Unit)? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setListener(callback: (String) -> Unit) {
        listener = callback
    }

    fun clearListener() {
        listener = null
    }

    fun v(message: String) {
        log("V", message)
        Log.v(TAG, message)
    }

    fun d(message: String) {
        log("D", message)
        Log.d(TAG, message)
    }

    fun i(message: String) {
        log("I", message)
        Log.i(TAG, message)
    }

    fun w(message: String) {
        log("W", message)
        Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        log("E", msg)
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $level: $message"
        logs.add(entry)

        // Trim old logs
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }

        listener?.invoke(entry)
    }

    fun getAllLogs(): String {
        return logs.joinToString("\n")
    }

    fun clear() {
        logs.clear()
    }
}
