package app.skerry.ui.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two-row key panel: its layout and the bytes each key puts on the wire. Expected sequences are
 * built from the ESC code point, not a literal, so they stay visible in Read/grep.
 */
class MobileKeybarLayoutTest {

    private val esc = 27.toChar()

    private fun sent(
        key: KeybarKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        applicationCursor: Boolean = false,
    ): String {
        val effect = keybarEffect(key, ctrl, alt, applicationCursor)
        assertTrue(effect is KeybarEffect.Send, "$key should reach the PTY as a control sequence, got $effect")
        return effect.sequence
    }

    private fun typed(key: KeybarKey, ctrl: Boolean = false, alt: Boolean = false): String {
        val effect = keybarEffect(key, ctrl, alt)
        assertTrue(effect is KeybarEffect.Type, "$key should be typed into the tracked line, got $effect")
        return effect.text
    }

    // Layout

    @Test
    fun `both layers are two rows of the same width`() {
        for (fn in listOf(false, true)) {
            val rows = keybarRows(fn)
            assertEquals(2, rows.size, "fnLayer=$fn")
            // Equal rows are what lets the panel lay out as a grid without horizontal scrolling:
            // a short row would stretch its keys to a different width than the row above.
            assertEquals(rows[0].size, rows[1].size, "fnLayer=$fn")
            assertEquals(rows.flatten().size, rows.flatten().toSet().size, "no key twice, fnLayer=$fn")
        }
    }

    @Test
    fun `base layer carries navigation, both modifiers and the layer switch`() {
        val keys = keybarRows(fnLayer = false).flatten()
        for (key in listOf(
            KeybarKey.Esc, KeybarKey.Tab, KeybarKey.Ctrl, KeybarKey.Alt, KeybarKey.Fn,
            KeybarKey.Home, KeybarKey.End, KeybarKey.PageUp, KeybarKey.PageDown,
            KeybarKey.Up, KeybarKey.Down, KeybarKey.Left, KeybarKey.Right,
            KeybarKey.Slash, KeybarKey.Pipe, KeybarKey.Dash, KeybarKey.Tilde,
            KeybarKey.HideKeyboard,
        )) {
            assertTrue(key in keys, "$key missing from the base layer")
        }
    }

    @Test
    fun `function layer carries F1 through F12`() {
        val keys = keybarRows(fnLayer = true).flatten()
        for (key in listOf(
            KeybarKey.F1, KeybarKey.F2, KeybarKey.F3, KeybarKey.F4, KeybarKey.F5, KeybarKey.F6,
            KeybarKey.F7, KeybarKey.F8, KeybarKey.F9, KeybarKey.F10, KeybarKey.F11, KeybarKey.F12,
        )) {
            assertTrue(key in keys, "$key missing from the function layer")
        }
    }

    @Test
    fun `the layer switch and the keyboard key keep their slot across layers`() {
        // Muscle memory: fn is what switches back, and it must not move under the finger that opened
        // the layer. Same for the key that drops the soft keyboard.
        val base = keybarRows(fnLayer = false)
        val fn = keybarRows(fnLayer = true)
        assertEquals(base[0].indexOf(KeybarKey.Fn), fn[0].indexOf(KeybarKey.Fn))
        assertEquals(base[1].indexOf(KeybarKey.HideKeyboard), fn[1].indexOf(KeybarKey.HideKeyboard))
    }

    @Test
    fun `arrows form a cross across the two rows`() {
        val rows = keybarRows(fnLayer = false)
        val up = rows[0].indexOf(KeybarKey.Up)
        val down = rows[1].indexOf(KeybarKey.Down)
        assertEquals(up, down, "up must sit directly above down")
        assertEquals(down - 1, rows[1].indexOf(KeybarKey.Left))
        assertEquals(down + 1, rows[1].indexOf(KeybarKey.Right))
    }

    // Encoding

    @Test
    fun `navigation keys encode xterm sequences`() {
        assertEquals("$esc[A", sent(KeybarKey.Up))
        assertEquals("$esc[D", sent(KeybarKey.Left))
        assertEquals("$esc[H", sent(KeybarKey.Home))
        assertEquals("$esc[F", sent(KeybarKey.End))
        assertEquals("$esc[5~", sent(KeybarKey.PageUp))
        assertEquals("$esc[6~", sent(KeybarKey.PageDown))
    }

    @Test
    fun `application cursor mode switches arrows and home-end to SS3`() {
        assertEquals("${esc}OA", sent(KeybarKey.Up, applicationCursor = true))
        assertEquals("${esc}OH", sent(KeybarKey.Home, applicationCursor = true))
        // Tilde keys carry no SS3 form — page keys stay CSI in application mode.
        assertEquals("$esc[5~", sent(KeybarKey.PageUp, applicationCursor = true))
    }

    @Test
    fun `armed modifiers are encoded inside the sequence, not dropped`() {
        // mod = 1 + shift + alt*2 + ctrl*4 — this is what makes ctrl+arrow a word jump on the panel.
        assertEquals("$esc[1;5C", sent(KeybarKey.Right, ctrl = true))
        assertEquals("$esc[1;3C", sent(KeybarKey.Right, alt = true))
        assertEquals("$esc[1;5H", sent(KeybarKey.Home, ctrl = true))
        assertEquals("$esc[5;5~", sent(KeybarKey.PageUp, ctrl = true))
        // Application-cursor mode is ignored once a modifier is armed: SS3 carries no parameter.
        assertEquals("$esc[1;5A", sent(KeybarKey.Up, ctrl = true, applicationCursor = true))
    }

    @Test
    fun `function keys encode SS3 and tilde forms`() {
        assertEquals("${esc}OP", sent(KeybarKey.F1))
        assertEquals("${esc}OS", sent(KeybarKey.F4))
        assertEquals("$esc[15~", sent(KeybarKey.F5))
        assertEquals("$esc[24~", sent(KeybarKey.F12))
    }

    @Test
    fun `esc and tab reach the PTY and ignore armed ctrl`() {
        assertEquals(esc.toString(), sent(KeybarKey.Esc))
        assertEquals("\t", sent(KeybarKey.Tab))
        // Ctrl+Esc and Ctrl+Tab have no xterm encoding: the key must still send itself rather than
        // swallow the tap because a modifier happened to be armed.
        assertEquals(esc.toString(), sent(KeybarKey.Esc, ctrl = true))
        assertEquals("\t", sent(KeybarKey.Tab, ctrl = true))
        // Alt is meta: ESC prefix.
        assertEquals("$esc\t", sent(KeybarKey.Tab, alt = true))
    }

    @Test
    fun `symbols are typed into the tracked line`() {
        assertEquals("/", typed(KeybarKey.Slash))
        assertEquals("|", typed(KeybarKey.Pipe))
        assertEquals("-", typed(KeybarKey.Dash))
        assertEquals("~", typed(KeybarKey.Tilde))
    }

    @Test
    fun `armed ctrl types the control byte of a symbol`() {
        assertEquals(0x0f, typed(KeybarKey.Slash, ctrl = true).single().code) // ctrl+/ = accept-line-and-down-history
        assertEquals(0x0d, typed(KeybarKey.Dash, ctrl = true).single().code)
    }

    @Test
    fun `armed alt prefixes a typed symbol with ESC`() {
        assertEquals("$esc~", typed(KeybarKey.Tilde, alt = true))
    }

    @Test
    fun `ctrl and alt together on a symbol keep both, meta outermost`() {
        // What mapTerminalKey encodes for the physical keyboard and what the IME path composes
        // (applyStickyMeta over applyStickyCtrl): ESC then the C0 byte. Dropping the alt here spent
        // a modifier the user armed on nothing.
        assertEquals(listOf(0x1b, 0x1e), typed(KeybarKey.Tilde, ctrl = true, alt = true).map { it.code })
    }

    @Test
    fun `ctrl and alt together on a navigation key encode mod 7`() {
        // mod = 1 + shift + alt*2 + ctrl*4. The combination most likely to fall through the
        // encoder's cracks, and the one keybarEffect must never answer nothing for.
        assertEquals("$esc[1;7C", sent(KeybarKey.Right, ctrl = true, alt = true))
        assertEquals("$esc[5;7~", sent(KeybarKey.PageUp, ctrl = true, alt = true))
        assertEquals("$esc[15;7~", sent(KeybarKey.F5, ctrl = true, alt = true))
    }

    @Test
    fun `every key answers with an effect under every modifier combination`() {
        // The panel must never swallow a tap: keybarEffect is called straight from onClick, so a key
        // that encodes to nothing would either crash or do nothing at all.
        val flags = listOf(false, true)
        val combos = flags.flatMap { ctrl -> flags.flatMap { alt -> flags.map { Triple(ctrl, alt, it) } } }
        for (key in KeybarKey.entries) {
            for ((ctrl, alt, appCursor) in combos) {
                val effect = keybarEffect(key, ctrl, alt, appCursor)
                val empty = effect is KeybarEffect.Send && effect.sequence.isEmpty() ||
                    effect is KeybarEffect.Type && effect.text.isEmpty()
                assertTrue(!empty, "$key produced nothing with ctrl=$ctrl alt=$alt")
            }
        }
    }

    @Test
    fun `panel keys that change the panel are commands`() {
        val expected = mapOf(
            KeybarKey.Ctrl to KeybarCommand.ArmCtrl,
            KeybarKey.Alt to KeybarCommand.ArmAlt,
            KeybarKey.Fn to KeybarCommand.ToggleFn,
            KeybarKey.HideKeyboard to KeybarCommand.HideKeyboard,
            KeybarKey.Ai to KeybarCommand.ToggleAi,
            KeybarKey.SearchHistory to KeybarCommand.SearchHistory,
            KeybarKey.FindOutput to KeybarCommand.FindOutput,
            KeybarKey.CycleSuggestion to KeybarCommand.CycleSuggestion,
        )
        for ((key, command) in expected) {
            assertEquals(KeybarEffect.Run(command), keybarEffect(key), "$key")
        }
    }
}
