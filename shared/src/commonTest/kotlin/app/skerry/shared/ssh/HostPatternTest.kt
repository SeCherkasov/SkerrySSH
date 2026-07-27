package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPatternTest {

    @Test
    fun `matches a literal host`() {
        assertTrue(HostPattern.matches("gate.example.com", "gate.example.com", 22))
        assertFalse(HostPattern.matches("gate.example.com", "other.example.com", 22))
    }

    @Test
    fun `matches a wildcard pattern`() {
        assertTrue(HostPattern.matches("*.prod.example.com", "web-01.prod.example.com", 22))
        assertFalse(HostPattern.matches("*.prod.example.com", "web-01.staging.example.com", 22))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(HostPattern.matches("web-0?.example.com", "web-01.example.com", 22))
        assertFalse(HostPattern.matches("web-0?.example.com", "web-001.example.com", 22))
    }

    @Test
    fun `matches any element of a comma separated list`() {
        assertTrue(HostPattern.matches("a.example.com,b.example.com", "b.example.com", 22))
        assertFalse(HostPattern.matches("a.example.com,b.example.com", "c.example.com", 22))
    }

    @Test
    fun `a negated element excludes a host the positive pattern would match`() {
        assertTrue(HostPattern.matches("*.example.com,!admin.example.com", "web.example.com", 22))
        assertFalse(HostPattern.matches("*.example.com,!admin.example.com", "admin.example.com", 22))
    }

    @Test
    fun `a pattern of negations alone matches nothing`() {
        // OpenSSH: negation only removes from what a positive pattern allowed.
        assertFalse(HostPattern.matches("!admin.example.com", "web.example.com", 22))
    }

    @Test
    fun `host comparison ignores case`() {
        assertTrue(HostPattern.matches("*.Example.COM", "web.example.com", 22))
        assertTrue(HostPattern.matches("gate.example.com", "GATE.EXAMPLE.COM", 22))
    }

    @Test
    fun `a portless pattern applies to any port`() {
        // Unlike an OpenSSH known_hosts file (bare = port 22), a CA is trusted for a fleet, and
        // fleets run SSH on more than one port.
        assertTrue(HostPattern.matches("*.example.com", "web.example.com", 2222))
    }

    @Test
    fun `a bracketed pattern applies only to its port`() {
        assertTrue(HostPattern.matches("[*.example.com]:2222", "web.example.com", 2222))
        assertFalse(HostPattern.matches("[*.example.com]:2222", "web.example.com", 22))
    }

    @Test
    fun `whitespace around list elements is ignored`() {
        assertTrue(HostPattern.matches(" a.example.com , b.example.com ", "b.example.com", 22))
    }

    @Test
    fun `an empty pattern matches nothing`() {
        assertFalse(HostPattern.matches("", "web.example.com", 22))
        assertFalse(HostPattern.matches("  ", "web.example.com", 22))
    }

    @Test
    fun `many wildcards do not blow up`() {
        // The ReDoS shape: a translated regex `.*.*.*…` against a long non-matching value would
        // backtrack catastrophically. The two-pointer matcher must stay linear-ish.
        val pattern = "*".repeat(64) + "z.example.com"
        assertFalse(HostPattern.matches(pattern, "a".repeat(4096), 22))
    }

    @Test
    fun `coversAnyHost rejects what matches would silently skip`() {
        assertTrue(HostPattern.coversAnyHost("*.example.com,!admin.example.com"))
        assertFalse(HostPattern.coversAnyHost("!admin.example.com"), "negations alone cover nothing")
        assertFalse(HostPattern.coversAnyHost("   "))
        assertFalse(HostPattern.coversAnyHost("a".repeat(HostPattern.MAX_PATTERN_LENGTH + 1)), "over-long element never matches")
        assertFalse(HostPattern.coversAnyHost("[broken:2222"), "a malformed bracketed element never matches")
    }

    @Test
    fun `normalize trims and lowercases elements so equal coverage compares equal`() {
        assertEquals("*.example.com,db.example.com", HostPattern.normalize(" *.Example.com , DB.example.com "))
        assertEquals("*.example.com", HostPattern.normalize("*.example.com,,"))
    }

    @Test
    fun `an over-long pattern is refused rather than matched`() {
        val pattern = "*a".repeat(HostPattern.MAX_PATTERN_LENGTH)
        assertFalse(HostPattern.matches(pattern, "a".repeat(512), 22))
    }
}
