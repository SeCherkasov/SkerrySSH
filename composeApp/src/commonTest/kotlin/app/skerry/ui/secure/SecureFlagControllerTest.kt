package app.skerry.ui.secure

import kotlin.test.Test
import kotlin.test.assertEquals

class SecureFlagControllerTest {
    @Test
    fun first_acquire_enables_and_last_release_disables() {
        val calls = mutableListOf<Boolean>()
        val c = SecureFlagController { calls.add(it) }
        c.acquire()
        assertEquals(listOf(true), calls)
        c.release()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun overlapping_holders_keep_flag_until_last_release() {
        val calls = mutableListOf<Boolean>()
        val c = SecureFlagController { calls.add(it) }
        c.acquire()
        c.acquire()
        assertEquals(listOf(true), calls) // включили ровно один раз
        c.release()
        assertEquals(listOf(true), calls) // ещё один держатель — флаг держим
        c.release()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun re_acquire_after_full_release_re_enables() {
        val calls = mutableListOf<Boolean>()
        val c = SecureFlagController { calls.add(it) }
        c.acquire()
        c.release()
        c.acquire()
        assertEquals(listOf(true, false, true), calls)
    }

    @Test
    fun extra_release_after_full_cycle_is_noop() {
        val calls = mutableListOf<Boolean>()
        val c = SecureFlagController { calls.add(it) }
        c.acquire()
        c.release()
        c.release() // лишний release после полного цикла — без эффекта
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun release_without_acquire_is_noop() {
        val calls = mutableListOf<Boolean>()
        val c = SecureFlagController { calls.add(it) }
        c.release()
        assertEquals(emptyList<Boolean>(), calls)
    }
}
