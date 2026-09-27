package sx.proxies.peer.network

import android.content.ContextWrapper
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real loopback WebSockets; platform context calls are stubbed, no outside traffic. */
class RelayConnectionRecoveryTest {
    private open class ClosingListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
    }
    private val server = MockWebServer()
    private var connection: RelayConnection? = null

    private fun peer(token: String, refresh: suspend () -> String?, connected: () -> Unit = {}, disconnected: () -> Unit = {}): RelayConnection {
        server.start()
        val peer = RelayConnection(
            context = ContextWrapper(null),
            relayUrl = server.url("/relay").toString().replace("http://", "ws://"),
            token = token,
            onConnected = { connected() },
            onDisconnected = disconnected,
            onProxyRequest = { error("No proxy traffic is allowed in this fixture") },
            onTrafficUpdate = { _, _ -> },
            refreshToken = refresh,
        )
        // Prevent the device-info public-IP lookup from leaving this JVM fixture.
        val privateHttp = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body("{\"ip\":\"127.0.0.1\"}".toResponseBody()).build()
        }.build()
        val field = RelayConnection::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(peer, privateHttp)
        connection = peer
        return peer
    }

    @After fun close() {
        connection?.disconnect()
        server.shutdown()
    }

    @Test fun authCloseReplacesTokenBeforeReconnect() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(4002, "Expired fixture") }
        }))
        val accepted = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"type\":\"connected\",\"payload\":{\"deviceId\":\"fixture\"}}")
            }
        }))
        val refreshes = AtomicInteger()
        val peer = peer("fixture-old", { refreshes.incrementAndGet(); "fixture-new" }, { accepted.countDown() })
        peer.connect()
        assertTrue("Expected authenticated reconnect", accepted.await(15, TimeUnit.SECONDS))
        assertEquals("token.fixture-old", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Sec-WebSocket-Protocol"))
        assertEquals("token.fixture-new", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Sec-WebSocket-Protocol"))
        assertEquals(1, refreshes.get())
    }

    @Test fun transportOpenAndEmptyAckDoNotResetRetryBudget() {
        val opened = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        lateinit var socket: WebSocket
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; opened.countDown() }
        }))
        val peer = peer("fixture-token", { error("No refresh expected") }, { accepted.countDown() })
        val attempts = RelayConnection::class.java.getDeclaredField("reconnectAttempt")
        attempts.isAccessible = true
        attempts.setInt(peer, 4)
        peer.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        socket.send("{\"type\":\"connected\",\"payload\":{\"deviceId\":\"\"}}")
        assertFalse(accepted.await(150, TimeUnit.MILLISECONDS))
        assertEquals(4, attempts.getInt(peer))
        socket.send("{\"type\":\"connected\",\"payload\":{\"deviceId\":\"fixture\"}}")
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        assertEquals(0, attempts.getInt(peer))
    }

    @Test fun stopDuringRefreshPreventsAnySocketDial() {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<String>()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":1}".toByteArray())
        val peer = peer("header.$payload.signature", { started.countDown(); release.await() })
        peer.connect()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        peer.disconnect()
        release.complete("fixture-new")
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
        peer.connect() // Explicitly disconnected instances cannot revive sharing.
        Thread.sleep(100)
        assertEquals(0, server.requestCount)
    }

    @Test fun stopCancelsQueuedAuthRecoveryAndReconnect() {
        val disconnected = CountDownLatch(1)
        val refreshes = AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(4001, "Fixture") }
        }))
        val peer = peer("fixture-old", { refreshes.incrementAndGet(); "fixture-new" }, disconnected = { disconnected.countDown() })
        peer.connect()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        peer.disconnect()
        Thread.sleep(5300)
        assertEquals(1, server.requestCount)
        assertEquals(0, refreshes.get())
    }
    @Test fun disconnectWaitsForAnAlreadyAcceptedCallbackAndFencesLateAck() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val effects = AtomicInteger()
        val opened = CountDownLatch(1)
        lateinit var socket: WebSocket
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; opened.countDown() }
        }))
        val peer = peer("fixture-token", { error("No refresh expected") }, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            effects.incrementAndGet()
        })
        peer.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        socket.send("{\"type\":\"connected\",\"payload\":{\"deviceId\":\"fixture\"}}")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopped = java.util.concurrent.CompletableFuture.runAsync { peer.disconnect() }
        try {
            Thread.sleep(150)
            assertFalse("Stop must serialize with callback side effects", stopped.isDone)
        } finally { release.countDown() }
        stopped.get(5, TimeUnit.SECONDS)
        socket.send("{\"type\":\"connected\",\"payload\":{\"deviceId\":\"fixture\"}}")
        Thread.sleep(100)
        assertEquals(1, effects.get())
    }

}
