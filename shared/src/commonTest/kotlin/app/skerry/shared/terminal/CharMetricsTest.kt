package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Точечные тесты чистого API [CharMetrics]; поведение в сетке покрыто через TerminalEmulatorTest. */
class CharMetricsTest {

    @Test
    fun `width is 2 for CJK and emoji, 1 for latin`() {
        assertEquals(2, CharMetrics.charWidth(0x4E2D)) // 中
        assertEquals(2, CharMetrics.charWidth(0x1F600)) // 😀
        assertEquals(1, CharMetrics.charWidth('a'.code))
    }

    @Test
    fun `combining marks and ZWJ are combining, letters are not`() {
        assertTrue(CharMetrics.isCombining(0x0301)) // acute accent
        assertTrue(CharMetrics.isCombining(0x200D)) // ZWJ
        assertFalse(CharMetrics.isCombining('e'.code))
    }

    @Test
    fun `codePointToString handles BMP, astral and invalid`() {
        assertEquals("A", CharMetrics.codePointToString(0x41))
        assertEquals("😀", CharMetrics.codePointToString(0x1F600)) // суррогатная пара
        assertEquals("�", CharMetrics.codePointToString(0xD800))  // одиночный суррогат — невалиден
    }
}
