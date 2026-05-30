package sx.proxies.peer.network

import sx.proxies.peer.util.DebugLogger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * SSRF / egress guard for peer-proxied traffic.
 *
 * A peer is an exit node for traffic the relay hands it. Without filtering,
 * a malicious or compromised relay (or a malicious customer request) could
 * point the device at its own loopback, the user's LAN, or cloud
 * link-local/metadata ranges — turning every peer into an SSRF pivot.
 *
 * [isAllowedTarget] resolves the host and rejects it if ANY resolved address
 * is loopback, any-local, link-local (incl. 169.254/16 cloud metadata),
 * site-local / RFC-1918, unique-local IPv6 (fc00::/7), or multicast.
 */
object EgressFilter {

    private const val TAG = "EgressFilter"

    /**
     * @return true if [host] resolves only to public, routable addresses.
     *         false if it is unresolvable or any address is internal.
     */
    fun isAllowedTarget(host: String): Boolean {
        val trimmed = host.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty()) return false

        // Reject obvious local hostnames before paying for DNS.
        val lower = trimmed.lowercase()
        if (lower == "localhost" || lower.endsWith(".localhost") || lower == "ip6-localhost") {
            return false
        }

        val addresses = try {
            InetAddress.getAllByName(trimmed)
        } catch (e: Exception) {
            DebugLogger.w("EgressFilter: cannot resolve host '$trimmed': ${e.message}")
            return false
        }

        if (addresses.isEmpty()) return false

        // Block if ANY resolved address is internal — defeats DNS that returns
        // both a public and a private record (DNS rebinding style attacks).
        for (addr in addresses) {
            if (isBlocked(addr)) {
                DebugLogger.w("EgressFilter: blocked internal address ${addr.hostAddress} for '$trimmed'")
                return false
            }
        }
        return true
    }

    private fun isBlocked(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr.isAnyLocalAddress) return true
        if (addr.isLinkLocalAddress) return true   // includes 169.254/16 metadata
        if (addr.isSiteLocalAddress) return true    // RFC-1918 (10/8, 172.16/12, 192.168/16)
        if (addr.isMulticastAddress) return true

        if (addr is Inet6Address) {
            // Unique local addresses fc00::/7 are not flagged site-local by the JDK.
            val b0 = addr.address[0].toInt() and 0xFE
            if (b0 == 0xFC) return true
        }
        if (addr is Inet4Address) {
            val b = addr.address
            val o0 = b[0].toInt() and 0xFF
            val o1 = b[1].toInt() and 0xFF
            // Carrier-grade NAT 100.64.0.0/10 — internal infra, not a valid exit target.
            if (o0 == 100 && o1 in 64..127) return true
        }
        return false
    }
}
