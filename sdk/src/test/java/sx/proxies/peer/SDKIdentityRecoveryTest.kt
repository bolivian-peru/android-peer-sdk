package sx.proxies.peer

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import sx.proxies.peer.util.SecurePreferences

class SDKIdentityRecoveryTest {
    private class TestContext : ContextWrapper(null) {
        val serviceCalls = AtomicInteger()
        val called = CountDownLatch(1)
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? {
            serviceCalls.incrementAndGet(); called.countDown(); return null
        }
    }
    private var sdk: ProxiesPeerSDK? = null
    private val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val secureInstance = SecurePreferences::class.java.getDeclaredField("instance").apply { isAccessible = true }

    private fun create(context: TestContext, handler: (okhttp3.Request) -> Pair<Int, String>): ProxiesPeerSDK {
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { if (args[1] == null) stored.remove(args[0]) else stored[args[0] as String] = args[1] as String; proxy }
                "apply" -> null
                "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> stored[args[0]] ?: args[1]
                "edit" -> editor
                "contains" -> stored.containsKey(args[0])
                "getAll" -> stored.toMap()
                else -> null
            }
        } as SharedPreferences
        secureInstance.set(null, prefs)
        val constructor = ProxiesPeerSDK::class.java.getDeclaredConstructor(Context::class.java, String::class.java, ProxiesPeerSDK.Config::class.java)
        constructor.isAccessible = true
        val instance = constructor.newInstance(context, "fixture-key", ProxiesPeerSDK.Config(apiUrl = "http://127.0.0.1/v1"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        ProxiesPeerSDK::class.java.getDeclaredField("client").apply { isAccessible = true }.set(instance, client)
        sdk = instance
        return instance
    }

    @After fun clean() { sdk?.stop(); secureInstance.set(null, null) }

    @Test fun rejectedRefreshReregistersThePersistedIdentityAndKeepsRotatedRefreshToken() {
        stored["device_id"] = "fixture-existing-device"
        stored["device_token"] = "fixture-old"
        stored["refresh_token"] = "fixture-refresh"
        val context = TestContext()
        var registeredIdentity: String? = null
        val instance = create(context) { request ->
            when {
                request.url.encodedPath.endsWith("/refresh") -> 401 to "{}"
                request.url.encodedPath.endsWith("/register") -> {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    registeredIdentity = com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject["deviceId"].asString
                    200 to "{\"device\":{\"deviceId\":\"fixture-existing-device\"},\"token\":\"fixture-new\",\"refreshToken\":\"fixture-rotated\"}"
                }
                else -> 503 to "{}"
            }
        }
        instance.start()
        assertTrue(context.called.await(5, TimeUnit.SECONDS))
        assertEquals("fixture-existing-device", registeredIdentity)
        assertEquals("fixture-existing-device", stored["device_id"])
        assertEquals("fixture-rotated", stored["refresh_token"])
    }

    @Test fun firstFallbackIdentityIsSavedBeforeFailedRegistrationAndReused() {
        val context = TestContext()
        val attempts = CountDownLatch(2)
        val firstAttempt = CountDownLatch(1)
        val ids = java.util.concurrent.CopyOnWriteArrayList<String>()
        val instance = create(context) { request ->
            if (request.url.encodedPath.endsWith("/register")) {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val id = com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject["deviceId"].asString
                assertEquals("Identity must be persisted before the request", id, stored["device_id"])
                ids.add(id); attempts.countDown(); firstAttempt.countDown()
            }
            503 to "{}"
        }
        instance.start()
        assertTrue(firstAttempt.await(5, TimeUnit.SECONDS))
        instance.start()
        assertTrue(attempts.await(5, TimeUnit.SECONDS))
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertEquals(0, context.serviceCalls.get())
    }

    @Test fun stoppingWhileStartupRefreshIsPendingCannotStartSharingLater() {
        stored["device_id"] = "fixture-existing-device"
        stored["device_token"] = "fixture-old"
        stored["refresh_token"] = "fixture-refresh"
        val context = TestContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val instance = create(context) { request ->
            if (request.url.encodedPath.endsWith("/refresh")) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                200 to "{\"token\":\"fixture-new\",\"refreshToken\":\"fixture-rotated\"}"
            } else 503 to "{}"
        }
        instance.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        instance.stop()
        release.countDown()
        Thread.sleep(250)
        assertEquals("Only the explicit stop intent may be sent", 1, context.serviceCalls.get())
        assertEquals(ProxiesPeerSDK.Status.STOPPED, instance.getStatus())
    }
}
