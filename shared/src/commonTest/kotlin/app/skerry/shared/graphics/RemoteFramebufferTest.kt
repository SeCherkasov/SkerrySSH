package app.skerry.shared.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RemoteFramebufferTest {

    @Test
    fun `a snapshot taken before a resize keeps the size its pixels were drawn at`() {
        val framebuffer = RemoteFramebuffer(4, 2)
        framebuffer.setPixel(3, 1, RED)
        val before = framebuffer.snapshot

        framebuffer.resize(2, 1)

        assertEquals(4, before.width)
        assertEquals(2, before.height)
        assertEquals(RED, before.pixels[1 * 4 + 3])
        val after = framebuffer.snapshot
        assertEquals(2 * 1, after.pixels.size)
        assertSame(framebuffer.pixels, after.pixels, "the writers draw into what the reader sees")
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
