package app.skerry.ui.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardInputTest {

    @Test
    fun `parses a well-formed forward and trims the host`() {
        val req = parseForwardInput(bindPort = "8080", destHost = "  10.0.0.5 ", destPort = "80")
        assertEquals(ForwardRequest(bindPort = 8080, destHost = "10.0.0.5", destPort = 80), req)
    }

    @Test
    fun `allows bind port zero (OS-assigned listener)`() {
        val req = parseForwardInput(bindPort = "0", destHost = "db.internal", destPort = "5432")
        assertEquals(ForwardRequest(bindPort = 0, destHost = "db.internal", destPort = 5432), req)
    }

    @Test
    fun `rejects a blank destination host`() {
        assertNull(parseForwardInput(bindPort = "8080", destHost = "   ", destPort = "80"))
    }

    @Test
    fun `rejects a non-numeric bind port`() {
        assertNull(parseForwardInput(bindPort = "abc", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects a bind port above the valid range`() {
        assertNull(parseForwardInput(bindPort = "70000", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects destination port zero`() {
        // Listener port 0 means OS-assigned, but destination port 0 is meaningless: nothing to connect to.
        assertNull(parseForwardInput(bindPort = "8080", destHost = "host", destPort = "0"))
    }

    @Test
    fun `rejects a destination port above the valid range`() {
        assertNull(parseForwardInput(bindPort = "8080", destHost = "host", destPort = "65536"))
    }

    @Test
    fun `rejects an empty bind port`() {
        assertNull(parseForwardInput(bindPort = "", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects a negative bind port`() {
        // UI restricts input to digits, but the function is public; the contract is enforced explicitly here.
        assertNull(parseForwardInput(bindPort = "-1", destHost = "host", destPort = "80"))
    }

    @Test
    fun `parseBindPort accepts the listener range including zero and rejects the rest`() {
        // -D has no destination; only the listener port is valid (0 = OS-assigned).
        assertEquals(1080, parseBindPort("1080"))
        assertEquals(0, parseBindPort("0"))
        assertNull(parseBindPort("70000"))
        assertNull(parseBindPort(""))
        assertNull(parseBindPort("abc"))
    }
}
