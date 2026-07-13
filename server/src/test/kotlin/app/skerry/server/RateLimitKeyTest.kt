package app.skerry.server

import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitKeyTest {

    @Test
    fun `no trusted proxies keys on the direct peer`() {
        assertEquals("203.0.113.9", rateLimitClientKey("203.0.113.9", "1.2.3.4", trustedProxies = emptySet()))
    }

    @Test
    fun `direct peer not trusted ignores the forwarded header`() {
        // A client connecting directly can put anything in X-Forwarded-For to try to shift buckets.
        assertEquals(
            "203.0.113.9",
            rateLimitClientKey("203.0.113.9", "10.0.0.1", trustedProxies = setOf("192.168.1.1")),
        )
    }

    @Test
    fun `trusted proxy uses the real client from the forwarded header`() {
        assertEquals(
            "203.0.113.9",
            rateLimitClientKey("192.168.1.1", "203.0.113.9", trustedProxies = setOf("192.168.1.1")),
        )
    }

    @Test
    fun `chain of trusted proxies resolves to the rightmost untrusted client`() {
        // client, edge, inner — edge and inner are trusted; the client is the real key.
        assertEquals(
            "203.0.113.9",
            rateLimitClientKey(
                directPeer = "192.168.1.2",
                forwardedFor = "203.0.113.9, 192.168.1.1, 192.168.1.2",
                trustedProxies = setOf("192.168.1.1", "192.168.1.2"),
            ),
        )
    }

    @Test
    fun `spoofed client through a trusted proxy still resolves the injected value`() {
        // A malicious client behind a trusted proxy can forge earlier XFF entries, but the value we
        // take (rightmost untrusted) is still tied to that client's real path — this documents that
        // trust is transitive: only deploy trustedProxies you actually control.
        assertEquals(
            "5.5.5.5",
            rateLimitClientKey("192.168.1.1", "1.1.1.1, 5.5.5.5", trustedProxies = setOf("192.168.1.1")),
        )
    }

    @Test
    fun `trusted proxy with empty or all-trusted chain falls back to the direct peer`() {
        assertEquals("192.168.1.1", rateLimitClientKey("192.168.1.1", null, trustedProxies = setOf("192.168.1.1")))
        assertEquals("192.168.1.1", rateLimitClientKey("192.168.1.1", "", trustedProxies = setOf("192.168.1.1")))
        assertEquals(
            "192.168.1.1",
            rateLimitClientKey("192.168.1.1", "192.168.1.1", trustedProxies = setOf("192.168.1.1")),
        )
    }
}
