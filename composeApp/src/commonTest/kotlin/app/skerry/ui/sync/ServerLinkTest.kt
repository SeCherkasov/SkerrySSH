package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue #243: a [ServerLink] is what the reactivation debt and the sync cursor are keyed on, and it used
 * to be keyed on the URL exactly as it was typed. `disconnect` erases the saved link, so the next connect
 * is typed by hand with nothing to prefill it — and a variant spelling of the same server made the rebuild
 * this device owes not owed, letting it push back what the account had purged.
 *
 * What is the same server is decided once, here: scheme and host are case-insensitive, the default port
 * for the scheme is the same as no port, and a bare trailing slash is no path at all. What a server may
 * legitimately distinguish — a path, a non-default port, the account id — is left alone.
 */
class ServerLinkTest {

    private val account = "maya"

    private fun link(url: String) = ServerLink(url, account)

    @Test
    fun `scheme and host case do not make a second server`() {
        assertEquals(link("https://work.test"), link("HTTPS://Work.TEST"))
    }

    @Test
    fun `the default port for the scheme is the same as no port`() {
        assertEquals(link("https://work.test"), link("https://work.test:443"))
        assertEquals(link("http://work.test"), link("http://work.test:80"))
    }

    @Test
    fun `a bare trailing slash is no path`() {
        assertEquals(link("https://work.test"), link("https://work.test/"))
        assertEquals(link("https://work.test:443/"), link("https://work.test"))
    }

    @Test
    fun `surrounding whitespace is not part of the url`() {
        assertEquals(link("https://work.test"), link("  https://work.test \n"))
    }

    @Test
    fun `the canonical form is what the link reports`() {
        assertEquals("https://work.test", link("HTTPS://Work.test:443/").serverUrl)
    }

    @Test
    fun `a non-default port is a different server`() {
        assertNotEquals(link("https://work.test"), link("https://work.test:8443"))
    }

    @Test
    fun `a path is a different endpoint`() {
        assertNotEquals(link("https://work.test"), link("https://work.test/sync"))
        // Only a BARE trailing slash is dropped: a server is free to route /sync and /sync/ apart.
        assertNotEquals(link("https://work.test/sync"), link("https://work.test/sync/"))
    }

    /** A query or a fragment ends the authority just as a path does — there is no path to trim there. */
    @Test
    fun `a query or a fragment is not part of the host`() {
        assertEquals("https://work.test?x=1", link("HTTPS://Work.test:443?x=1").serverUrl)
        assertEquals("https://work.test#top", link("https://WORK.TEST#top").serverUrl)
        assertNotEquals(link("https://work.test?x=1"), link("https://work.test?x=2"))
    }

    /** An '@' is legal inside user info, so the host starts after the LAST one, not the first. */
    @Test
    fun `user info containing an at sign still ends where the host begins`() {
        assertEquals(link("https://work.test"), link("https://ada@mail.test:pw@work.test"))
    }

    /**
     * The account id is not always this user's own typing: a device that joins by pairing learns it from
     * the server's answer, so a hostile one can put the separator itself inside it. The key must still name
     * exactly one link — sharing a cursor between two links is the whole of issue #242, and a server that
     * can arrange it remotely is worse than a user who trips over it.
     */
    @Test
    fun `no account id can make one link's key read as another's`() {
        val a = ServerLink("https://work.test", "maya\u0000https://home.test").cursorKey
        val b = ServerLink("https://work.test\u0000https://home.test", "maya").cursorKey
        assertNotEquals(a, b)
        assertNotEquals(
            ServerLink("https://work.test", "ab").cursorKey,
            ServerLink("https://work.tes", "t\u0000ab").cursorKey,
        )
        // A legacy key was the bare account id; nothing here can be mistaken for one.
        assertNotEquals("maya", ServerLink("https://work.test", "maya").cursorKey)
    }

    @Test
    fun `the account id still separates two accounts on one server`() {
        assertNotEquals(ServerLink("https://work.test", "maya"), ServerLink("https://work.test", "ada"))
    }

    @Test
    fun `an ipv6 literal keeps its brackets and its port`() {
        assertEquals(link("https://[2001:db8::1]"), link("https://[2001:DB8::1]:443"))
        assertNotEquals(link("https://[2001:db8::1]"), link("https://[2001:db8::1]:8443"))
    }

    @Test
    fun `a trailing dot is the same host`() {
        assertEquals(link("https://work.test"), link("https://work.test./"))
        assertEquals(link("https://work.test"), link("https://Work.TEST.:443"))
    }

    /**
     * A single label is the exception: with a resolver `search` list, `sync.` is the absolute name and
     * `sync` goes through the suffix, so they can be two different machines. Fusing them would put two
     * servers on one cursor and let a rebuild owed to one look discharged by a cycle on the other.
     */
    @Test
    fun `a trailing dot on a single-label host is part of the name`() {
        assertNotEquals(link("https://sync"), link("https://sync."))
    }

    /**
     * User info is not part of the request — this client authenticates with a bearer token and never
     * sends it — so it is not part of the server's identity either. Keeping it would mean a rebuild owed
     * to a server is not owed under a spelling that differs only by a prefix nobody transmits.
     */
    @Test
    fun `user info is not part of the server`() {
        assertEquals(link("https://work.test"), link("https://ada@work.test"))
        assertEquals(link("https://ada@work.test"), link("https://Bob@Work.test:443/"))
        assertEquals("https://work.test", link("https://ada@work.test").serverUrl)
    }

    @Test
    fun `a port spelled with leading zeros is the same port`() {
        assertEquals(link("https://work.test"), link("https://work.test:0443"))
        assertEquals(link("https://work.test:8443"), link("https://work.test:08443"))
    }

    @Test
    fun `a url the form would not accept is left as it is`() {
        // canSubmit blocks these; identity still has to be total, and inventing a shape for a string that
        // has none would collapse two links that are not the same.
        assertEquals("work.test", link("  work.test  ").serverUrl)
        assertNotEquals(link("work.test"), link("https://work.test"))
    }
}
