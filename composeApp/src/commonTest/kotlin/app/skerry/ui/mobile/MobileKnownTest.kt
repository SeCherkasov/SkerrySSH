package app.skerry.ui.mobile

import app.skerry.ui.known.KnownHostStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure logic for the mobile Known hosts screen: status icon. */
class MobileKnownTest {

    @Test
    fun status_icon_verified_or_error() {
        assertEquals("verified", mobileKnownStatusIcon(KnownHostStatus.Verified))
        assertEquals("error", mobileKnownStatusIcon(KnownHostStatus.Changed))
    }
}
