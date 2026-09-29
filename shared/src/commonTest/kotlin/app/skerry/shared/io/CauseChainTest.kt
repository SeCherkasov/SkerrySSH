package app.skerry.shared.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CauseChainTest {

    private class Looping : Exception("loop") {
        override val cause: Throwable get() = this
    }

    @Test
    fun `a chain that loops back on itself still ends`() {
        assertEquals(8, Looping().causeChain().count())
    }

    @Test
    fun `the first cause of the asked type is found under the wrappers`() {
        val inner = IllegalStateException("inner")
        val outer = RuntimeException("outer", IllegalArgumentException("middle", inner))

        assertSame(inner, outer.findCause<IllegalStateException>())
        assertNull(outer.findCause<UnsupportedOperationException>())
    }
}
