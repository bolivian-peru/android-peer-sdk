package sx.proxies.peer.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import sx.proxies.peer.service.ProxyResponse
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy server that runs on the device.
 * Used for SDK mode where the host app makes requests through localhost.
 *
 * SECURITY: Binds to localhost (127.0.0.1) only to prevent external access.
 */
class LocalProxyServer(
    port: Int,
    private val onRequest: suspend (
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ) -> ProxyResponse
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "LocalProxyServer"
        // Bound concurrency: each request runs runBlocking on its worker, so
        // an unbounded thread-per-request runner (NanoHTTPD's default) could
        // spawn arbitrarily many blocked threads under load.
        private const val MAX_WORKERS = 16
        private const val READ_TO_EOF_CAP = 16 * 1024 * 1024 // safety cap for chunked bodies
    }

    init {
        // Replace the default unbounded async runner with a bounded pool.
        setAsyncRunner(BoundedAsyncRunner(MAX_WORKERS))
    }

    /** Fixed-size thread pool runner so concurrent requests can't exhaust threads. */
    private class BoundedAsyncRunner(maxThreads: Int) : AsyncRunner {
        private val executor = Executors.newFixedThreadPool(maxThreads) as ThreadPoolExecutor
        private val running = java.util.Collections.synchronizedList(ArrayList<ClientHandler>())

        override fun closeAll() {
            ArrayList(running).forEach { it.close() }
            executor.shutdownNow()
        }

        override fun closed(clientHandler: ClientHandler) { running.remove(clientHandler) }

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            executor.execute(clientHandler)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val method = session.method.name
            val uri = session.uri
            val queryString = session.queryParameterString ?: ""
            val fullUrl = if (queryString.isNotEmpty()) "$uri?$queryString" else uri

            Log.d(TAG, "Local proxy request: $method $fullUrl")

            // Collect headers
            val headers = session.headers.mapKeys { it.key.lowercase() }

            // Read body honoring either Content-Length or chunked encoding.
            val body = readRequestBody(session, headers)

            // Forward request through relay
            val response = runBlocking {
                onRequest(method, fullUrl, headers, body)
            }

            // Build NanoHTTPD response
            val decodedBody = if (response.body.isNotEmpty()) {
                android.util.Base64.decode(response.body, android.util.Base64.DEFAULT)
            } else {
                ByteArray(0)
            }
            val nanoResponse = newFixedLengthResponse(
                Response.Status.lookup(response.statusCode) ?: Response.Status.OK,
                response.headers["Content-Type"] ?: "application/octet-stream",
                decodedBody.inputStream(),
                decodedBody.size.toLong()
            )

            // Add response headers
            response.headers.forEach { (key, value) ->
                if (key.lowercase() !in listOf("content-length", "transfer-encoding")) {
                    nanoResponse.addHeader(key, value)
                }
            }

            nanoResponse
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy error: ${e.message}"
            )
        }
    }

    private fun readRequestBody(session: IHTTPSession, headers: Map<String, String>): ByteArray? {
        val contentLength = headers["content-length"]?.toIntOrNull()
        val isChunked = headers["transfer-encoding"]
            ?.split(",")?.any { it.trim().equals("chunked", ignoreCase = true) } == true

        return when {
            contentLength != null && contentLength > 0 -> {
                val buffer = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                if (totalRead == contentLength) buffer else buffer.copyOf(totalRead)
            }
            isChunked -> readChunkedBody(session.inputStream)
            else -> null
        }
    }

    /**
     * Minimal HTTP/1.1 chunked-transfer decoder. Previously chunked uploads
     * were silently dropped (only Content-Length was honored). Reads
     * `<hex-size>CRLF<data>CRLF` chunks until a zero-size chunk.
     */
    private fun readChunkedBody(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLine(input) // consume trailing CRLF after the last chunk
                    break
                }
                if (out.size() + size > READ_TO_EOF_CAP) {
                    Log.w(TAG, "Chunked body exceeds cap; truncating")
                    break
                }
                val chunk = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(chunk, read, size - read)
                    if (n == -1) break
                    read += n
                }
                out.write(chunk, 0, read)
                readLine(input) // consume CRLF after chunk data
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chunked decode error: ${e.message}")
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    /** Read a CRLF/LF-terminated line of ASCII (chunk-size lines are short). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }
}
