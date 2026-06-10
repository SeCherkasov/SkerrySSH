package app.skerry.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformNameIsNotBlank() {
        assertTrue(platformName.isNotBlank())
    }
}
