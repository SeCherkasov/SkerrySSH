package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class FingerprintElisionTest {

    @Test
    fun `full 8-group fingerprint keeps head and tail pairs`() {
        assertEquals(
            "a117-d7d7-…-8eda-d598",
            elideFingerprint("a117-d7d7-0d5d-bd7c-901c-c8cf-8eda-d598"),
        )
    }

    @Test
    fun `short values stay untouched`() {
        assertEquals("a117-d7d7-0d5d-bd7c", elideFingerprint("a117-d7d7-0d5d-bd7c"))
        assertEquals("a117", elideFingerprint("a117"))
        assertEquals("", elideFingerprint(""))
    }
}
