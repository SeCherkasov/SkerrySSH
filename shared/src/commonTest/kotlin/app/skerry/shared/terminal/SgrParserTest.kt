package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** Точечные тесты чистого API [SgrParser]; полный SGR-контракт покрыт через TerminalEmulatorTest. */
class SgrParserTest {

    private fun apply(raw: String, start: TermStyle = TermStyle()): TermStyle =
        SgrParser.apply(SgrParser.parseParams(raw), start)

    @Test
    fun `empty params reset to default style`() {
        assertEquals(TermStyle(), apply("", start = TermStyle(bold = true, fg = TermColor.Red)))
    }

    @Test
    fun `colon truecolor and legacy 256 forms parse`() {
        assertEquals(TermColor.Rgb(1, 2, 3), apply("38:2::1:2:3").fg)
        assertEquals(TermColor.Indexed(196), apply("48;5;196").bg)
    }

    @Test
    fun `apply builds on the given start style`() {
        val out = apply("1", start = TermStyle(italic = true))
        assertEquals(TermStyle(bold = true, italic = true), out)
    }
}
