package sx.proxies.peer.lifecycle

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import sx.proxies.peer.ProxiesPeerSDK
import sx.proxies.peer.service.PeerProxyService
import sx.proxies.peer.util.SecurePreferences

/** Test APK only. The host runner also verifies its private AVD and blocks non-loopback traffic. */
class LifecycleTestActivity : Activity()

internal class LifecycleFixture(preserveStorage: Boolean = false, seedExistingIdentity: Boolean = true) : AutoCloseable {
    val context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(Build.HARDWARE == "ranchu") { "Qualification is restricted to the isolated emulator" }
        check(InstrumentationRegistry.getArguments().getString("isolatedLoopback") == "true") {
            "Use the guarded host runner; off-device traffic must be blocked first"
        }
    }
    val prefs = SecurePreferences.get(context)
    val server = MockWebServer()
    val sockets = CopyOnWriteArrayList<WebSocket>()
    val protocols = CopyOnWriteArrayList<String>()
    val registrations = CopyOnWriteArrayList<String>()
    val refreshPaths = CopyOnWriteArrayList<String>()
    val refreshes = AtomicInteger()
    val refreshEntered = CountDownLatch(1)
    val releaseRefresh = CountDownLatch(1)
    @Volatile var blockRefresh = false
    @Volatile var expiredRegistration = false
    @Volatile var rejectFirstSocket = false
    @Volatile var acknowledge = true
    private var activity: ActivityScenario<LifecycleTestActivity>
    val sdk: ProxiesPeerSDK

    init {
        if (!preserveStorage) {
            check(prefs.edit().clear().commit())
            if (seedExistingIdentity) {
                check(prefs.edit().putString("device_id", "fixture-existing-identity").commit())
            }
        }
        check(!proxyListening()) { "A previous local proxy leaked into this process" }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/peer/register" -> {
                        val id = JsonParser.parseString(request.body.readUtf8()).asJsonObject["deviceId"].asString
                        registrations.add(id)
                        json("""{"device":{"deviceId":"$id"},"token":"${token(1, expiredRegistration)}","refreshToken":"fixture-refresh-1"}""")
                    }
                    request.path?.endsWith("/refresh") == true -> {
                        refreshPaths.add(request.path!!)
                        val n = refreshes.incrementAndGet()
                        refreshEntered.countDown()
                        if (blockRefresh && !releaseRefresh.await(12, TimeUnit.SECONDS)) {
                            return MockResponse().setResponseCode(504)
                        }
                        json("""{"token":"${token(n + 1)}","refreshToken":"fixture-refresh-${n + 1}"}""")
                    }
                    request.path == "/relay" -> {
                        protocols.add(request.getHeader("Sec-WebSocket-Protocol") ?: "")
                        val number = protocols.size
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                sockets.add(webSocket)
                                if (rejectFirstSocket && number == 1) {
                                    webSocket.close(4002, "Fixture auth rejection")
                                } else if (acknowledge) {
                                    ack(webSocket)
                                }
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason)
                            }
                        })
                    }
                    request.path?.contains("/earnings") == true -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        activity = ActivityScenario.launch(LifecycleTestActivity::class.java)
        sdk = ProxiesPeerSDK.init(context, "fixture-local-key", ProxiesPeerSDK.Config(
            apiUrl = server.url("/v1").toString(),
            relayUrl = server.url("/relay").toString().replace("http://", "ws://"),
        ))
    }

    fun ack(socket: WebSocket) {
        val id = prefs.getString("device_id", null) ?: error("Missing fixture identity")
        socket.send("""{"type":"connected","payload":{"deviceId":"$id"}}""")
    }

    fun background() {
        activity.onActivity { it.moveTaskToBack(true) }
        await("Activity is stopped in background") { activity.state == Lifecycle.State.CREATED }
    }

    fun foreground() {
        // Relaunch the test UI as a user would; resuming a stopped activity alone
        // does not bring its task back from the background on this Android OS.
        activity.close()
        activity = ActivityScenario.launch(LifecycleTestActivity::class.java)
    }

    fun dropTransport() {
        // MockWebServer 4.12's server-side WebSocket.cancel has no client Call.
        // Close its actual accepted sockets instead, without a WS close frame or
        // rebinding the listener. Reflection is confined to the pinned fixture.
        val field = MockWebServer::class.java.getDeclaredField("openClientSockets").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val accepted = (field.get(server) as Set<Socket>).toList()
        check(accepted.isNotEmpty())
        accepted.forEach { it.close() }
    }

    fun serviceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(20).any { it.service.className == PeerProxyService::class.java.name }
    }

    fun notificationStop() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == 1001 }.notification
        notification.actions.single().actionIntent.send()
    }

    fun awaitConnected() {
        await("Application authentication ACK", 15000) { sdk.getStatus() == ProxiesPeerSDK.Status.CONNECTED }
        await("Foreground service") { serviceRunning() }
        await("Local proxy listener") { proxyListening() }
    }

    fun assertStopped() {
        await("Service stopped") { !serviceRunning() }
        await("Local proxy released") { !proxyListening() }
        assertEquals(ProxiesPeerSDK.Status.STOPPED, sdk.getStatus())
    }

    override fun close() {
        releaseRefresh.countDown()
        sdk.stop()
        context.stopService(Intent(context, PeerProxyService::class.java))
        sockets.forEach { it.close(1000, "Fixture cleanup") }
        server.shutdown()
        activity.close()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        fun token(sequence: Int, expired: Boolean = false): String {
            val expiry = if (expired) 1 else System.currentTimeMillis() / 1000 + 3600
            val body = Base64.encodeToString("""{"exp":$expiry,"fixture":$sequence}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "fixture.$body.signature"
        }

        fun proxyListening(): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8888), 100) }
            true
        } catch (_: Exception) { false }

        fun await(message: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertTrue(message, condition())
        }
    }
}

class SdkLifecycleInstrumentedTest {
    private var fixture: LifecycleFixture? = null
    private fun fixture() = LifecycleFixture().also { fixture = it }
    @After fun close() { fixture?.close() }

    @Test fun expiredTokenRefreshesBeforeAnySocketAndPersistsRotation() {
        val f = fixture()
        f.expiredRegistration = true
        f.sdk.start()
        f.awaitConnected()
        assertEquals(listOf("fixture-existing-identity"), f.registrations.toList())
        assertEquals(1, f.refreshes.get())
        assertEquals(1, f.protocols.size)
        assertEquals("token." + f.prefs.getString("device_token", null), f.protocols.single())
        assertEquals("fixture-refresh-2", f.prefs.getString("refresh_token", null))
        f.sdk.stop()
        f.assertStopped()
    }

    @Test fun explicitAuthCloseRefreshesBeforeAuthenticatedReconnect() {
        val f = fixture()
        f.rejectFirstSocket = true
        f.sdk.start()
        f.awaitConnected()
        assertEquals(1, f.refreshes.get())
        assertEquals(2, f.protocols.size)
        assertNotEquals(f.protocols[0], f.protocols[1])
        assertEquals("fixture-existing-identity", f.prefs.getString("device_id", null))
        f.sdk.stop()
        f.assertStopped()
    }

    @Test fun transportLossReconnectsAndStopReleasesTheOnlyProxyListener() {
        val f = fixture()
        f.sdk.start()
        f.awaitConnected()
        f.dropTransport()
        LifecycleFixture.await("Reconnect after transport loss", 15000) { f.protocols.size == 2 }
        f.awaitConnected()
        assertEquals(f.protocols[0], f.protocols[1])
        assertEquals(0, f.refreshes.get())
        f.sdk.stop()
        f.assertStopped()
    }

    @Test fun stopDuringServiceRefreshCannotStartASocketLater() {
        val f = fixture()
        f.expiredRegistration = true
        f.blockRefresh = true
        f.sdk.start()
        assertTrue(f.refreshEntered.await(8, TimeUnit.SECONDS))
        f.sdk.stop()
        f.releaseRefresh.countDown()
        f.assertStopped()
        Thread.sleep(750)
        assertEquals(0, f.protocols.size)
        assertEquals(ProxiesPeerSDK.Status.STOPPED, f.sdk.getStatus())
    }

    @Test fun stopAfterTransportOpenFencesLateAuthenticationAck() {
        val f = fixture()
        f.acknowledge = false
        f.sdk.start()
        LifecycleFixture.await("Transport opened") { f.sockets.isNotEmpty() }
        f.sdk.stop()
        f.sockets.forEach { f.ack(it) }
        f.assertStopped()
        Thread.sleep(750)
        assertFalse(LifecycleFixture.proxyListening())
        assertEquals(ProxiesPeerSDK.Status.STOPPED, f.sdk.getStatus())
    }

    @Test fun notificationStopCancelsQueuedAuthRecovery() {
        val f = fixture()
        f.rejectFirstSocket = true
        f.sdk.start()
        LifecycleFixture.await("Rejected transport opened") { f.sockets.isNotEmpty() }
        f.notificationStop()
        f.assertStopped()
        Thread.sleep(5500)
        assertEquals(0, f.refreshes.get())
        assertEquals(1, f.protocols.size)
    }

    @Test fun backgroundServiceDestroyClosesSocketsAndProxy() {
        val f = fixture()
        f.sdk.start()
        f.awaitConnected()
        f.background()
        assertTrue(f.serviceRunning())
        assertTrue(f.context.stopService(Intent(f.context, PeerProxyService::class.java)))
        LifecycleFixture.await("OS destroyed service") { !f.serviceRunning() }
        LifecycleFixture.await("OS destroy released local proxy") { !LifecycleFixture.proxyListening() }
        LifecycleFixture.await("Destroyed service reports STOPPED") {
            f.sdk.getStatus() == ProxiesPeerSDK.Status.STOPPED
        }
        val requests = f.protocols.size
        Thread.sleep(5500)
        assertEquals(requests, f.protocols.size)
        f.foreground()
        f.sdk.start()
        LifecycleFixture.await("Explicit start after OS destruction", 15000) { f.protocols.size == requests + 1 }
        f.awaitConnected()
        assertEquals(1, f.registrations.size)
        assertEquals("fixture-existing-identity", f.prefs.getString("device_id", null))
        f.sdk.stop()
        f.assertStopped()
    }
}

/** These two phases must run in separate processes without clearing app data. */
class ProcessPersistenceInstrumentedTest {
    private var fixture: LifecycleFixture? = null
    @After fun close() { fixture?.close() }

    @Test fun seedEncryptedIdentityBeforeProcessExit() {
        val f = LifecycleFixture(seedExistingIdentity = false).also { fixture = it }
        f.sdk.start()
        f.awaitConnected()
        val id = f.prefs.getString("device_id", null)!!
        assertTrue(f.registrations.contains(id))
        check(f.context.getSharedPreferences("lifecycle_checkpoint", Context.MODE_PRIVATE).edit()
            .putString("identity", id).putInt("pid", android.os.Process.myPid()).commit())
        f.sdk.stop()
        f.assertStopped()
        // A synchronous commit ensures encrypted state is durable before the host kills this process.
        check(f.prefs.edit().putString("device_id", id).commit())
    }

    @Test fun newProcessRetainsIdentityAndWaitsForExplicitStart() {
        val f = LifecycleFixture(preserveStorage = true).also { fixture = it }
        val checkpoint = f.context.getSharedPreferences("lifecycle_checkpoint", Context.MODE_PRIVATE)
        val id = checkpoint.getString("identity", null) ?: error("Run the seed phase first")
        assertNotEquals(checkpoint.getInt("pid", -1), android.os.Process.myPid())
        assertEquals(id, f.prefs.getString("device_id", null))
        assertEquals(ProxiesPeerSDK.Status.STOPPED, f.sdk.getStatus())
        Thread.sleep(500)
        assertEquals(0, f.server.requestCount)
        f.sdk.start()
        f.awaitConnected()
        assertEquals(0, f.registrations.size)
        assertEquals(listOf("/v1/peer/token/$id/refresh"), f.refreshPaths.toList())
        assertEquals(id, f.prefs.getString("device_id", null))
        f.sdk.stop()
        f.assertStopped()
    }
}
