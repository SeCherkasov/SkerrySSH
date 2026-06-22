package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalMouseTest {

    private val esc = 27

    /** Удобный декод результата в человекочитаемую форму: ESC → "ESC", прочее как ASCII/число. */
    private fun ByteArray.show(): String = joinToString("") { b ->
        val v = b.toInt() and 0xff
        when {
            v == esc -> "ESC"
            v in 0x20..0x7e -> v.toChar().toString()
            else -> "<$v>"
        }
    }

    private fun String.show(): String = encodeToByteArray().show()

    private fun encode(
        tracking: MouseTracking,
        sgr: Boolean,
        button: MouseButton,
        type: MouseEventType,
        col: Int = 0,
        row: Int = 0,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ) = encodeMouseReport(tracking, sgr, button, type, col, row, shift, alt, ctrl)

    // --- Режим Off ---------------------------------------------------------

    @Test
    fun `off reports nothing`() {
        assertNull(encode(MouseTracking.Off, sgr = true, button = MouseButton.Left, type = MouseEventType.Press))
    }

    // --- SGR (1006) --------------------------------------------------------

    @Test
    fun `sgr left press at origin`() {
        val out = encode(MouseTracking.Normal, sgr = true, button = MouseButton.Left, type = MouseEventType.Press)
        assertEquals("ESC[<0;1;1M", out!!.show())
    }

    @Test
    fun `sgr release uses lowercase m and keeps real button`() {
        val out = encode(MouseTracking.Normal, sgr = true, button = MouseButton.Right, type = MouseEventType.Release, col = 4, row = 2)
        assertEquals("ESC[<2;5;3m", out!!.show())
    }

    @Test
    fun `sgr coordinates are one-based`() {
        val out = encode(MouseTracking.Normal, sgr = true, button = MouseButton.Left, type = MouseEventType.Press, col = 9, row = 19)
        assertEquals("ESC[<0;10;20M", out!!.show())
    }

    @Test
    fun `sgr middle button is code one`() {
        val out = encode(MouseTracking.Normal, sgr = true, button = MouseButton.Middle, type = MouseEventType.Press)
        assertEquals("ESC[<1;1;1M", out!!.show())
    }

    @Test
    fun `sgr wheel up and down are codes 64 and 65`() {
        assertEquals("ESC[<64;1;1M", encode(MouseTracking.Normal, sgr = true, button = MouseButton.WheelUp, type = MouseEventType.Press)!!.show())
        assertEquals("ESC[<65;1;1M", encode(MouseTracking.Normal, sgr = true, button = MouseButton.WheelDown, type = MouseEventType.Press)!!.show())
    }

    @Test
    fun `sgr drag sets motion bit 32`() {
        val out = encode(MouseTracking.ButtonEvent, sgr = true, button = MouseButton.Left, type = MouseEventType.Drag)
        assertEquals("ESC[<32;1;1M", out!!.show())
    }

    @Test
    fun `sgr modifiers add shift alt ctrl`() {
        // shift=4, alt=8, ctrl=16 → 0+4+8+16 = 28
        val out = encode(MouseTracking.Normal, sgr = true, button = MouseButton.Left, type = MouseEventType.Press, shift = true, alt = true, ctrl = true)
        assertEquals("ESC[<28;1;1M", out!!.show())
    }

    // --- SGR-Pixels (1016) -------------------------------------------------

    @Test
    fun `pixel mode encodes pixel coordinates not cells`() {
        // pixels=true: координаты берутся из pixelX/pixelY (1-based), а не из col/row.
        val out = encodeMouseReport(
            MouseTracking.ButtonEvent, sgr = true, button = MouseButton.Left, type = MouseEventType.Press,
            col = 3, row = 7, pixels = true, pixelX = 119, pixelY = 239,
        )
        assertEquals("ESC[<0;120;240M", out!!.show())
    }

    @Test
    fun `pixel mode release uses lowercase m and keeps button even without sgr flag`() {
        // 1016 без 1006 (sgr=false): release всё равно сохраняет код кнопки (не legacy-3).
        val out = encodeMouseReport(
            MouseTracking.ButtonEvent, sgr = false, button = MouseButton.Right, type = MouseEventType.Release,
            col = 0, row = 0, pixels = true, pixelX = 9, pixelY = 4,
        )
        assertEquals("ESC[<2;10;5m", out!!.show())
    }

    @Test
    fun `pixel mode forces sgr form even when sgr flag is false`() {
        // 1016 подразумевает SGR-кодировку независимо от 1006.
        val out = encodeMouseReport(
            MouseTracking.ButtonEvent, sgr = false, button = MouseButton.Middle, type = MouseEventType.Drag,
            col = 0, row = 0, pixels = true, pixelX = 0, pixelY = 0,
        )
        // Cb = 1(middle) + 32(motion) = 33; координаты 0+1/0+1.
        assertEquals("ESC[<33;1;1M", out!!.show())
    }

    @Test
    fun `pixel mode still gated by tracking off`() {
        assertNull(encodeMouseReport(
            MouseTracking.Off, sgr = true, button = MouseButton.Left, type = MouseEventType.Press,
            col = 0, row = 0, pixels = true, pixelX = 5, pixelY = 5,
        ))
    }

    // --- Legacy (X11 1000) -------------------------------------------------

    @Test
    fun `legacy left press encodes three offset bytes`() {
        // Cb = 0+32 = 32 = ' '; col 0 → 1+32 = 33 = '!'; row 0 → '!'.
        val out = encode(MouseTracking.Normal, sgr = false, button = MouseButton.Left, type = MouseEventType.Press)
        assertEquals("ESC[M !!", out!!.show())
    }

    @Test
    fun `legacy release uses button code three`() {
        // Cb = 3+32 = 35 = '#'.
        val out = encode(MouseTracking.Normal, sgr = false, button = MouseButton.Left, type = MouseEventType.Release)
        assertEquals("ESC[M#!!", out!!.show())
    }

    @Test
    fun `legacy drag combines motion bit and offset`() {
        // Cb = 0 + 32(motion) + 32(offset) = 64 = '@'.
        val out = encode(MouseTracking.ButtonEvent, sgr = false, button = MouseButton.Left, type = MouseEventType.Drag)
        assertEquals("ESC[M@!!", out!!.show())
    }

    @Test
    fun `legacy coordinate clamps beyond byte range`() {
        // col 300 → 301+32 = 333 → clamp 255.
        val out = encode(MouseTracking.Normal, sgr = false, button = MouseButton.Left, type = MouseEventType.Press, col = 300, row = 0)
        assertEquals("ESC[M <255>!", out!!.show())
    }

    // --- Гейтинг по режимам ------------------------------------------------

    @Test
    fun `x10 reports only button press, no release drag wheel`() {
        assertEquals("ESC[M !!", encode(MouseTracking.X10, sgr = false, button = MouseButton.Left, type = MouseEventType.Press)!!.show())
        assertNull(encode(MouseTracking.X10, sgr = false, button = MouseButton.Left, type = MouseEventType.Release))
        assertNull(encode(MouseTracking.X10, sgr = false, button = MouseButton.Left, type = MouseEventType.Drag))
        assertNull(encode(MouseTracking.X10, sgr = false, button = MouseButton.WheelUp, type = MouseEventType.Press))
    }

    @Test
    fun `x10 ignores modifiers`() {
        // В X10 модификаторы не кодируются: остаётся чистый Cb=32.
        val out = encode(MouseTracking.X10, sgr = false, button = MouseButton.Left, type = MouseEventType.Press, ctrl = true)
        assertEquals("ESC[M !!", out!!.show())
    }

    @Test
    fun `normal mode drops drag and move`() {
        assertNull(encode(MouseTracking.Normal, sgr = true, button = MouseButton.Left, type = MouseEventType.Drag))
        assertNull(encode(MouseTracking.Normal, sgr = true, button = MouseButton.Left, type = MouseEventType.Move))
    }

    @Test
    fun `button-event mode keeps drag but drops move`() {
        assertEquals("ESC[<32;1;1M", encode(MouseTracking.ButtonEvent, sgr = true, button = MouseButton.Left, type = MouseEventType.Drag)!!.show())
        assertNull(encode(MouseTracking.ButtonEvent, sgr = true, button = MouseButton.Left, type = MouseEventType.Move))
    }

    @Test
    fun `any-event mode reports move with motion bit and button 3`() {
        // Движение без кнопки: motion(32) + «нет кнопки» (3) = 35.
        val out = encode(MouseTracking.AnyEvent, sgr = true, button = MouseButton.Left, type = MouseEventType.Move)
        assertEquals("ESC[<35;1;1M", out!!.show())
    }

    // --- Bracketed paste ---------------------------------------------------

    @Test
    fun `bracketed paste wraps text in markers`() {
        assertEquals("ESC[200~hi there ESC[201~".replace(" ESC", "ESC"),
            bracketedPasteWrap("hi there", bracketed = true).show())
    }

    @Test
    fun `bracketed paste passthrough when disabled`() {
        assertEquals("hi there", bracketedPasteWrap("hi there", bracketed = false))
    }

    @Test
    fun `bracketed paste strips embedded closing marker (anti-injection)`() {
        val esc = esc.toChar()
        // Текст с подсаженным закрывающим маркером + "вредоносной" командой за ним.
        val malicious = "ok${esc}[201~rm -rf /"
        val out = bracketedPasteWrap(malicious, bracketed = true).show()
        // Закрывающий маркер встречается ровно один раз — наш собственный, в самом конце.
        assertEquals("ESC[200~okrm -rf /ESC[201~", out)
    }

    @Test
    fun `disabled paste strips CR and ESC (anti command injection)`() {
        // Без bracketed-paste shell не отличает вставку от ввода: подсаженный CR = Enter → команда
        // за ним исполнилась бы. Режем CR и ESC (и прочие управляющие C0/DEL).
        assertEquals("okrm -rf /", bracketedPasteWrap("ok" + Char(13) + "rm -rf /", bracketed = false).show())
        assertEquals("plain", bracketedPasteWrap("pl" + Char(27) + "ain", bracketed = false).show())
    }

    @Test
    fun `disabled paste keeps tab and newline`() {
        // Tab и LF — легитимные разделители многострочной/колоночной вставки, их сохраняем.
        val input = "a" + Char(9) + "b" + Char(10) + "c"
        assertEquals("a<9>b<10>c", bracketedPasteWrap(input, bracketed = false).show())
    }

    @Test
    fun `disabled paste strips DEL and NUL`() {
        val input = "a" + Char(0) + Char(127) + "b"
        assertEquals("ab", bracketedPasteWrap(input, bracketed = false).show())
    }

    @Test
    fun `enabled paste preserves control chars inside markers`() {
        // В bracketed-режиме приложение само знает, что это вставка — управляющие байты безопасны
        // и должны дойти как литералы (режем только подсаженный закрывающий маркер, см. тест выше).
        val input = "a" + Char(13) + "b" + Char(9) + "c"
        assertEquals("ESC[200~a<13>b<9>cESC[201~", bracketedPasteWrap(input, bracketed = true).show())
    }

    // --- Гейтинг X10 в SGR-кодировке --------------------------------------

    @Test
    fun `x10 drops non-press events even in sgr encoding`() {
        assertNull(encode(MouseTracking.X10, sgr = true, button = MouseButton.Left, type = MouseEventType.Release))
        assertNull(encode(MouseTracking.X10, sgr = true, button = MouseButton.WheelUp, type = MouseEventType.Press))
        assertEquals("ESC[<0;1;1M", encode(MouseTracking.X10, sgr = true, button = MouseButton.Left, type = MouseEventType.Press)!!.show())
    }
}
