package app.skerry.ui.settings

import app.skerry.shared.vault.SecurityEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the security event list shows as an event's detail. A [SecurityEventType.KeyExported] event
 * stores the credential id (the label is secret and the log is plaintext on disk), but the id is an
 * opaque UUID no screen ever shows — so at display time it is resolved to the label, and an id that
 * no longer resolves (secret deleted, keychain not loaded this run) shows no detail at all rather than a UUID a
 * screen reader would spell out character by character. Every other event type shows its detail
 * verbatim — [SecurityEventType.DevicePaired] stores a human device name on purpose.
 */
class SecurityEventDisplayDetailTest {

    @Test
    fun `a key export resolves its credential id to the label`() {
        val detail = securityEventDisplayDetail(SecurityEventType.KeyExported, "cred-1") { id ->
            if (id == "cred-1") "prod bastion" else null
        }
        assertEquals("prod bastion", detail)
    }

    @Test
    fun `an unresolvable id shows no detail, not the raw id`() {
        assertNull(securityEventDisplayDetail(SecurityEventType.KeyExported, "cred-gone") { null })
    }

    @Test
    fun `other event types pass their detail through untouched`() {
        val detail = securityEventDisplayDetail(SecurityEventType.DevicePaired, "iPhone 16 Pro") { null }
        assertEquals("iPhone 16 Pro", detail)
    }

    @Test
    fun `a detail-less event stays detail-less`() {
        assertNull(securityEventDisplayDetail(SecurityEventType.KeyExported, null) { "never called" })
    }
}
