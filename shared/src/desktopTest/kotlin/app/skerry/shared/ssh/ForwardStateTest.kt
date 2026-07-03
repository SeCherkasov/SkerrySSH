package app.skerry.shared.ssh

import java.io.Closeable
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Тесты общего стейта пробросов [ForwardState] (телеметрия + закрытие живых ресурсов). */
class ForwardStateTest {

    @Test
    fun `fresh state is active and not paused with zero counters`() {
        val state = ForwardState()
        assertTrue(state.active.get())
        assertFalse(state.paused.get())
        assertEquals(0L, state.up.get())
        assertEquals(0L, state.down.get())
        assertTrue(state.live.isEmpty())
    }

    @Test
    fun `closeAll closes every live resource and swallows close failures`() {
        val state = ForwardState()
        var closed = 0
        state.live.add(Closeable { closed++ })
        state.live.add(Closeable { throw IOException("boom") }) // сбой одного не мешает остальным
        state.live.add(Closeable { closed++ })
        state.closeAll()
        assertEquals(2, closed)
    }
}
