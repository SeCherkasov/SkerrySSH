package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopiedBannerTest {

    // Regression for the stuck-banner bug: the copy counter is re-keyed to 0 on tab switch, and a 0
    // must hide the flash rather than leaving whatever it was (else copying then switching tabs within
    // the show window left "Copied" pinned on the other tab).
    @Test
    fun `nonce 0 hides the flash`() {
        assertFalse(shouldShowCopiedFlash(0))
    }

    @Test
    fun `a bumped nonce shows the flash`() {
        assertTrue(shouldShowCopiedFlash(1))
        assertTrue(shouldShowCopiedFlash(42))
    }

    // After ~2^31 copies the counter wraps to a negative value; still a real copy, still shown.
    @Test
    fun `a wrapped-around negative nonce still shows the flash`() {
        assertTrue(shouldShowCopiedFlash(Int.MIN_VALUE))
    }
}
