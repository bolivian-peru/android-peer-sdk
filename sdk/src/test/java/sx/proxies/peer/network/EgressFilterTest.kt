package sx.proxies.peer.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSRF egress guard tests (S2). Uses literal IPs so the test does not depend
 * on network DNS; public hostnames are covered by a single, optional check.
 */
class EgressFilterTest {

    @Test
    fun blocks_loopback() {
        assertFalse(EgressFilter.isAllowedTarget("127.0.0.1"))
        assertFalse(EgressFilter.isAllowedTarget("::1"))
        assertFalse(EgressFilter.isAllowedTarget("localhost"))
    }

    @Test
    fun blocks_rfc1918_private_ranges() {
        assertFalse(EgressFilter.isAllowedTarget("10.0.0.5"))
        assertFalse(EgressFilter.isAllowedTarget("172.16.3.9"))
        assertFalse(EgressFilter.isAllowedTarget("192.168.1.1"))
    }

    @Test
    fun blocks_link_local_and_cloud_metadata() {
        assertFalse(EgressFilter.isAllowedTarget("169.254.169.254")) // cloud metadata
    }

    @Test
    fun blocks_any_local_and_multicast() {
        assertFalse(EgressFilter.isAllowedTarget("0.0.0.0"))
        assertFalse(EgressFilter.isAllowedTarget("224.0.0.1"))
    }

    @Test
    fun blocks_carrier_grade_nat() {
        assertFalse(EgressFilter.isAllowedTarget("100.64.0.1"))
        assertFalse(EgressFilter.isAllowedTarget("100.127.255.255"))
    }

    @Test
    fun blocks_empty_or_unresolvable() {
        assertFalse(EgressFilter.isAllowedTarget(""))
        assertFalse(EgressFilter.isAllowedTarget("   "))
        assertFalse(EgressFilter.isAllowedTarget("this-host-does-not-exist.invalid"))
    }

    @Test
    fun allows_public_ip_literals() {
        assertTrue(EgressFilter.isAllowedTarget("8.8.8.8"))
        assertTrue(EgressFilter.isAllowedTarget("1.1.1.1"))
        // 100.x outside the CGNAT 100.64/10 block is public and allowed.
        assertTrue(EgressFilter.isAllowedTarget("100.200.1.1"))
    }
}
