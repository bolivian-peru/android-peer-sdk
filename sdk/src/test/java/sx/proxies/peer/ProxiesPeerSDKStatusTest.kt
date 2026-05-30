package sx.proxies.peer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F1 regression: a relay "connected" event must map to CONNECTED (so
 * isRunning() returns true and onStatusChange fires), and a disconnect must
 * map back to CONNECTING. Before the fix, the service never propagated this
 * and the SDK was stuck at CONNECTING forever.
 */
class ProxiesPeerSDKStatusTest {

    @Test
    fun connected_event_maps_to_CONNECTED() {
        assertEquals(ProxiesPeerSDK.Status.CONNECTED, ProxiesPeerSDK.statusForConnected(true))
    }

    @Test
    fun disconnected_event_maps_to_CONNECTING() {
        assertEquals(ProxiesPeerSDK.Status.CONNECTING, ProxiesPeerSDK.statusForConnected(false))
    }
}
