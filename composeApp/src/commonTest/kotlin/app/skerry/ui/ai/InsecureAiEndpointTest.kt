package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The warning under the endpoint field is the only thing telling the user their API key is about to
 * cross the network in the clear — the refresh button sends it there on one click.
 */
class InsecureAiEndpointTest {

    @Test
    fun `plain http to a remote host is insecure`() {
        assertTrue(isInsecureAiEndpoint("http://proxy.example.com/v1"))
        assertTrue(isInsecureAiEndpoint("  http://proxy.example.com/v1  "))
        assertTrue(isInsecureAiEndpoint("http://198.51.100.7:8080/v1"))
    }

    @Test
    fun `the scheme is compared case-insensitively`() {
        assertTrue(isInsecureAiEndpoint("HTTP://proxy.example.com/v1"), "a client lowercases the scheme; the warning must too")
        assertTrue(isInsecureAiEndpoint("Http://proxy.example.com/v1"))
    }

    @Test
    fun `a host that merely starts with localhost is not the loopback host`() {
        assertTrue(isInsecureAiEndpoint("http://localhost.attacker.com/v1"))
        assertTrue(isInsecureAiEndpoint("http://127.0.0.1.attacker.com/v1"))
    }

    @Test
    fun `the loopback host is exempt, with or without a port`() {
        assertFalse(isInsecureAiEndpoint("http://localhost:11434/v1"))
        assertFalse(isInsecureAiEndpoint("http://localhost/v1"))
        assertFalse(isInsecureAiEndpoint("http://127.0.0.1:1234/v1"))
        assertFalse(isInsecureAiEndpoint("http://[::1]:11434/v1"))
        assertFalse(isInsecureAiEndpoint("http://[::1]/v1"), "a bracketed IPv6 literal has no port to strip")
    }

    @Test
    fun `a loopback name smuggled into the fragment, query or path does not exempt the real host`() {
        // The authority ends at the first of / ? # \ — a client dials evil.com and the key goes with it.
        assertTrue(isInsecureAiEndpoint("http://evil.com#@localhost/v1"))
        assertTrue(isInsecureAiEndpoint("http://evil.com:8080#@127.0.0.1"))
        assertTrue(isInsecureAiEndpoint("http://evil.com?@localhost/v1"))
        assertTrue(isInsecureAiEndpoint("http://evil.com\\@localhost/v1"))
    }

    @Test
    fun `an address that cannot be confirmed as TLS is treated as cleartext`() {
        assertTrue(isInsecureAiEndpoint("http:evil.com/v1"), "no slashes, still http")
        assertTrue(isInsecureAiEndpoint("//evil.com/v1"), "protocol-relative: a client defaults it to http")
        assertTrue(isInsecureAiEndpoint("ftp://evil.com/v1"))
    }

    @Test
    fun `a half-typed address does not flash a warning`() {
        assertFalse(isInsecureAiEndpoint(""))
        assertFalse(isInsecureAiEndpoint("   "))
        assertFalse(isInsecureAiEndpoint("api.example.com"), "no scheme yet — the user is still typing")
    }

    @Test
    fun `https is never flagged`() {
        assertFalse(isInsecureAiEndpoint("https://api.openai.com/v1"))
        assertFalse(isInsecureAiEndpoint("HTTPS://api.openai.com/v1"))
        assertFalse(isInsecureAiEndpoint(""))
    }
}
