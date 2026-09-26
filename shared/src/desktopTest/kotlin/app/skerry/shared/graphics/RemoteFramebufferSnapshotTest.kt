package app.skerry.shared.graphics

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The UI reads the framebuffer on its own thread while the read loop may be resizing it. What it
 * reads has to be one size and the array of that size: a width from before a resize with the
 * pixels from after it is how a stale rectangle overran the buffer on Android.
 */
class RemoteFramebufferSnapshotTest {

    @Test
    fun `a snapshot read during resizes is never torn`() {
        val framebuffer = RemoteFramebuffer(10, 10)
        val stop = AtomicBoolean(false)
        val resizer = thread {
            var wide = false
            while (!stop.get()) {
                wide = !wide
                if (wide) framebuffer.resize(40, 5) else framebuffer.resize(10, 10)
            }
        }
        var torn: String? = null
        try {
            val deadline = System.nanoTime() + READ_FOR_NANOS
            while (torn == null && System.nanoTime() < deadline) {
                val snapshot = framebuffer.snapshot
                if (snapshot.width * snapshot.height != snapshot.pixels.size) {
                    torn = "${snapshot.width}x${snapshot.height} over ${snapshot.pixels.size} pixels"
                }
            }
        } finally {
            stop.set(true)
            resizer.join()
        }
        assertEquals(null, torn, "a snapshot mixed two sizes")
    }

    private companion object {
        const val READ_FOR_NANOS = 500_000_000L
    }
}
