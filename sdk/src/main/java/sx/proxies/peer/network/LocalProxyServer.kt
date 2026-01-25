package sx.proxies.peer.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import sx.proxies.peer.service.ProxyResponse

/**
 * Local HTTP proxy server that runs on the device.
 * Used for SDK mode where the host app makes requests through localhost.
 */
class LocalProxyServer(
    port: Int,
    private val onRequest: suspend (
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ) -> ProxyResponse
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "LocalProxyServer"
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

            // Read body if present
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val bytesRead = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }
                buffer
            } else null

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
}
