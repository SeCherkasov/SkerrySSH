package app.skerry.server.routes

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope rule itself, without a server around it. [webSessionMayCall] is the whole of what keeps
 * the web password — the lesser credential — away from what the master password protects, so every
 * branch is worth pinning down directly rather than sampling through HTTP.
 *
 * The spellings matter as much as the paths: Ktor resolves a route on **decoded** segments and skips
 * empty ones, while `call.request.path()` hands back the raw request target. A rule that compares the
 * raw string is a rule the router disagrees with.
 */
class WebSessionGuardTest {

    private fun mayGet(path: String) = webSessionMayCall(HttpMethod.Get, path)

    @Test
    fun `metadata reads are allowed`() {
        listOf(
            "/account/summary", "/account/activity", "/vault/envelopes", "/devices", "/teams",
            "/teams/t1/members", "/teams/t1/scopes", "/teams/t1/activity", "/teams/t1/shares",
        ).forEach {
            assertTrue(mayGet(it), it)
        }
    }

    /**
     * A WebSocket handshake is an HTTP GET, so "any GET is a read" let a web session onto the share
     * relay: `/host` opens a live session under the account's name and burns the per-team share cap,
     * `/join` takes a viewer slot and relays frames back to a real host. Neither is a read.
     */
    @Test
    fun `the share sockets are refused`() {
        assertFalse(mayGet("/teams/t1/shares/s1/host"))
        assertFalse(mayGet("/teams/t1/shares/s1/join"))
        assertFalse(mayGet("/sync"))
    }

    /**
     * The rule is an allow-list, so a route nobody thought about is refused by default. That is the
     * whole point: the next route added under `authenticate("auth-jwt")` must not open itself to a
     * browser session by being a GET.
     */
    @Test
    fun `a path the account zone does not call is refused`() {
        assertFalse(mayGet("/auth/web-password"))
        assertFalse(mayGet("/teams/t1"))
        assertFalse(mayGet("/account"))
        assertFalse(mayGet("/account/summary/extra"))
        assertFalse(mayGet("/devices/some-device"))
        assertFalse(mayGet("/"))
    }

    @Test
    fun `the two reads that carry ciphertext are refused`() {
        assertFalse(mayGet("/vault/keys"))
        assertFalse(mayGet("/vault/records"))
    }

    @Test
    fun `team record blobs are refused whatever the team id`() {
        assertFalse(mayGet("/teams/t1/records"))
        assertFalse(mayGet("/teams/some-other-team/records"))
        // The projection beside them is metadata and stays open.
        assertTrue(mayGet("/teams/t1/scopes"))
    }

    @Test
    fun `writes are refused except revoking a device`() {
        assertFalse(webSessionMayCall(HttpMethod.Post, "/auth/web-password"))
        assertFalse(webSessionMayCall(HttpMethod.Post, "/pairing/start"))
        assertFalse(webSessionMayCall(HttpMethod.Put, "/vault/records"))
        assertFalse(webSessionMayCall(HttpMethod.Delete, "/teams/t1"))
        assertTrue(webSessionMayCall(HttpMethod.Delete, "/devices/some-device"))
        // One id, not a prefix: the rule used to be `startsWith("/devices/")`, which would also
        // have covered anything nested under it.
        assertFalse(webSessionMayCall(HttpMethod.Delete, "/devices/a/b"))
        assertFalse(webSessionMayCall(HttpMethod.Delete, "/devices"))
    }

    /**
     * The router decodes each segment before matching, so `/vault/%72ecords` reaches the handler that
     * serves every ciphertext blob. A rule that compares the raw string sees a path it doesn't know
     * and falls through to "a GET is fine".
     */
    @Test
    fun `a percent-encoded spelling of a refused path is still refused`() {
        assertFalse(mayGet("/vault/%72ecords"))
        assertFalse(mayGet("/vault/k%65ys"))
        assertFalse(mayGet("/%76ault/keys"))
        assertFalse(mayGet("/teams/t1/%72ecords"))
        // Upper-case hex digits are the same escape.
        assertFalse(mayGet("/vault/%6Beys"))
    }

    /** The router skips empty segments, so `/vault//records` resolves to `/vault/records`. */
    @Test
    fun `an empty segment does not hide a refused path`() {
        assertFalse(mayGet("/vault//records"))
        assertFalse(mayGet("//vault/records"))
        assertFalse(mayGet("/teams/t1//records"))
    }

    /**
     * `%2F` decodes to a slash *inside* one segment, which is not the same route — the router sees a
     * single segment `vault/keys` and matches nothing. Refusing it anyway is the fail-closed answer:
     * the guard never has to be right about which unroutable spellings are harmless.
     */
    @Test
    fun `an escaped separator is refused rather than reasoned about`() {
        assertFalse(mayGet("/vault%2Fkeys"))
    }

    /** A malformed escape can't be decoded, so it can't be cleared either. */
    @Test
    fun `an undecodable path is refused`() {
        assertFalse(mayGet("/vault/%zz"))
        assertFalse(mayGet("/vault/records%"))
    }
}
