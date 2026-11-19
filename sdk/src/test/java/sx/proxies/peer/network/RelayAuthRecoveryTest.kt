package sx.proxies.peer.network

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelayAuthRecoveryTest {
    private fun token(expiry: Long): String = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"exp\":$expiry}".toByteArray()) + ".signature"

    @Test fun validTokenDoesNotRefresh() = runBlocking {
        val original = token(500)
        val state = RelayAuthRecovery(original) { error("Unexpected refresh") }
        assertEquals(original, state.tokenForConnection(100_000))
    }

    @Test fun expiredTokenIsReplacedBeforeItCanBeSent() = runBlocking {
        var calls = 0
        val fresh = token(500)
        val state = RelayAuthRecovery(token(100)) { calls++; fresh }
        assertEquals(fresh, state.tokenForConnection(100_000))
        assertEquals(1, calls)
    }

    @Test fun explicitRejectionRefreshesEvenAnUnexpiredToken() = runBlocking {
        var calls = 0
        val state = RelayAuthRecovery(token(500)) { calls++; token(600) }
        state.rejected()
        assertEquals(token(600), state.tokenForConnection(100_000))
        assertEquals(1, calls)
    }

    @Test fun repeatedRejectedCredentialsCannotRegisterForever() = runBlocking {
        var calls = 0
        val state = RelayAuthRecovery(token(500)) { calls++; token(600 + calls.toLong()) }
        repeat(3) { state.rejected(); assertNotNull(state.tokenForConnection(100_000)) }
        state.rejected()
        assertNull(state.tokenForConnection(100_000))
        assertEquals(3, calls)
    }

    @Test fun onlyApplicationAuthenticationResetsTheRefreshBudget() = runBlocking {
        var calls = 0
        val state = RelayAuthRecovery(token(500)) { calls++; token(600 + calls.toLong()) }
        repeat(3) { state.rejected(); state.tokenForConnection(100_000) }
        state.authenticated()
        state.rejected()
        assertNotNull(state.tokenForConnection(100_000))
        assertEquals(4, calls)
    }

    @Test fun concurrentReconnectsShareOneRefresh() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val state = RelayAuthRecovery(token(50)) { calls++; release.await(); token(500) }
        val requests = List(8) { async { state.tokenForConnection(100_000) } }
        release.complete(Unit)
        assertTrue(requests.awaitAll().all { it == token(500) })
        assertEquals(1, calls)
    }

    @Test fun stoppingDuringRefreshCannotReturnCredentialsForAnotherDial() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = RelayAuthRecovery(token(50)) { started.complete(Unit); release.await(); token(500) }
        val pending = async { state.tokenForConnection(100_000) }
        started.await()
        state.stop()
        release.complete(Unit)
        assertNull(pending.await())
        assertNull(state.tokenForConnection(100_000))
    }

    @Test fun refreshErrorsAlsoHaveABoundedAttemptBudget() = runBlocking {
        var calls = 0
        val state = RelayAuthRecovery(token(50)) { calls++; throw IllegalStateException("Transient fixture") }
        repeat(3) {
            try { state.tokenForConnection(100_000); fail("Expected refresh failure") }
            catch (_: IllegalStateException) { }
        }
        assertNull(state.tokenForConnection(100_000))
        assertEquals(3, calls)
    }

    @Test fun expiredOrEmptyRefreshResultsNeverReachTheRelay() = runBlocking {
        for (replacement in listOf("", token(50))) {
            val state = RelayAuthRecovery(token(50)) { replacement }
            try { state.tokenForConnection(100_000); fail("Expected unusable token rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
