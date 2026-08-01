package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The address the Web access card prints and opens. The server URL comes from a text field the user
 * typed into, so a trailing slash and stray spaces are both ordinary input — and `//account` is a
 * different path to a browser than `/account`.
 */
class AccountPageUrlTest {

    @Test
    fun `the account page hangs off the configured server`() {
        assertEquals("http://localhost:8080/account", accountPageUrl("http://localhost:8080"))
        assertEquals("https://sync.example.com/account", accountPageUrl("https://sync.example.com"))
    }

    @Test
    fun `a typed trailing slash does not become a double slash`() {
        assertEquals("http://localhost:8080/account", accountPageUrl("http://localhost:8080/"))
        assertEquals("http://localhost:8080/account", accountPageUrl("  http://localhost:8080/  "))
    }

    @Test
    fun `no server means no address to show`() {
        assertNull(accountPageUrl(null))
        assertNull(accountPageUrl(""))
        assertNull(accountPageUrl("   "))
        // Everything trimmed away is the same as nothing configured, not a link to "/account".
        assertNull(accountPageUrl("/"))
    }
}
