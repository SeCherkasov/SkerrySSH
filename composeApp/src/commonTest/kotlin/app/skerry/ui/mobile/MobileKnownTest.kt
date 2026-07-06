package app.skerry.ui.mobile

import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.KnownHost
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure logic for the mobile Known hosts screen: row projection, status icon, key-change banner. */
class MobileKnownTest {

    private fun entry(
        keyType: String = "ssh-ed25519",
        fingerprint: String = "SHA256:8c3F1a2bQzABCDEFGHIJKLMNpK9R",
        status: KnownHostStatus = KnownHostStatus.Verified,
    ): KnownHostEntry = KnownHostEntry(
        KnownHost("prod-web-01", 22, keyType, fingerprint, "2026-01-12T09:00:00Z"),
        status,
    )

    private fun mismatch(host: String = "nas-truenas", keyType: String = "ssh-ed25519"): HostKeyMismatch =
        HostKeyMismatch(host, 22, keyType, "SHA256:old", "SHA256:new", "2026-06-22T08:00:00Z")

    // mobileKnownSubtitle, mobileKnownBannerTitle and mobileKnownBannerBody are now @Composable
    // (text is localized via string resources); their unit tests were removed. Below: the remaining
    // pure logic (status icon).

    // Row status icon

    @Test
    fun status_icon_verified_or_error() {
        assertEquals("verified", mobileKnownStatusIcon(KnownHostStatus.Verified))
        assertEquals("error", mobileKnownStatusIcon(KnownHostStatus.Changed))
    }
}
