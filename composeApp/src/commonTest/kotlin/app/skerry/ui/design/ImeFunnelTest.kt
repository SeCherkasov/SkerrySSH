package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The soft keyboard's funnel, at the level where a keyboard's delivery pattern is visible.
 *
 * A key event never reaches the terminal on this path — a field trace from a Galaxy S24 showed
 * Samsung Keyboard delivering part of the number row as two edits ~2 ms apart, both carrying the
 * same field contents (`commitText` followed by `finishComposingText`, composition already null).
 * Every one of those pairs put the digit into the shell twice: `1234567` arrived as `11233455677`.
 *
 * What decides the reading of an edit is whether the funnel's reset had reached the editor before
 * it arrived. [Field] models exactly that: the editor's buffer, and the composition that refills
 * it. Every test says which of the two happened; nothing here is left to timing.
 */
class ImeFunnelTest {

    @Test
    fun `a repeated edit carrying the same character sends it once`() {
        val field = Field()

        val first = field.type("1")
        // The keyboard's second edit for the same keypress: the field is unchanged, and no
        // composition has run in between, so the reset the funnel asked for has not landed.
        val repeat = field.deliverAgain()

        assertEquals("1", first)
        assertEquals("", repeat, "the digit reached the shell more than once")
    }

    /** The rule above must not cost a repeated digit its second press. */
    @Test
    fun `the same character typed four times reaches the shell four times`() {
        val field = Field()

        val sent = (1..4).map { field.resetLands().type("1") }

        assertEquals(List(4) { "1" }, sent)
    }

    /** Two characters typed before the reset lands: the second one is what is new, not both. */
    @Test
    fun `a character typed before the reset lands sends only itself`() {
        val field = Field()

        assertEquals("1", field.type("1"))
        assertEquals("2", field.type("2"))
    }

    /**
     * The reset does not land on the same edit every time. A keypress delivered before it arrives
     * leaves the field holding what that keypress typed, and the next press — after the reset — can
     * look byte for byte like a deletion of it. Reading it that way sent a Backspace the user never
     * pressed: `aca` reached the shell as `ac` and ate the character before it.
     */
    @Test
    fun `a keypress after a late reset is not read as a deletion`() {
        val field = Field()

        assertEquals("a", field.resetLands().type("a"))
        assertEquals("c", field.type("c"))

        assertEquals("a", field.resetLands().type("a"))
    }

    /** Same shape, with the repeated character adjacent: `aaa` must not come out as `aa` + DEL. */
    @Test
    fun `a repeated character across a late reset is not read as a deletion`() {
        val field = Field()

        val sent = listOf(field.resetLands().type("a"), field.type("a"), field.resetLands().type("a"))

        assertEquals(listOf("a", "a", "a"), sent)
    }

    /**
     * Deleting an anchor is how a Backspace becomes visible at all when nothing has been typed
     * since the last reset.
     */
    @Test
    fun `a backspace on an untouched field sends DEL`() {
        val field = Field()

        assertEquals(DEL, field.backspace())
    }

    /**
     * Held Backspace, the way it came back from the S24: the anchors' own characters were read as
     * typed text and every second press put a zero-width space into the shell instead of a deletion.
     */
    @Test
    fun `repeated backspaces each send exactly one DEL`() {
        val field = Field()

        val sent = (1..6).map { field.resetLands().backspace() }

        assertEquals(List(6) { DEL }, sent)
    }

    /** Held Backspace repeating faster than the field is refilled is still one DEL per press. */
    @Test
    fun `backspaces delivered between two resets each send one DEL`() {
        val field = Field()

        val sent = listOf(field.backspace(), field.backspace(), field.resetLands().backspace())

        assertEquals(List(3) { DEL }, sent)
    }

    /**
     * A backspace over a character the reset has not cleared yet is still one deletion — here.
     * `BasicTextField` may never deliver this edit at all when it lands exactly on the value the
     * field was composed with; that layer is pinned in `ImeFunnelFieldTest` and explained on
     * [ImeFunnelField]. This test owns the arithmetic, not the delivery.
     */
    @Test
    fun `a backspace before the reset lands sends one DEL`() {
        val field = Field()
        field.type("1")

        assertEquals(DEL, field.backspace())
    }

    /**
     * A gesture that empties the field in one edit — "clear", swipe-to-delete-word — is one
     * deletion, not one per anchor the funnel put there. At a login prompt the difference is how
     * much of the password the far side loses.
     */
    @Test
    fun `clearing the whole field sends one DEL`() {
        val field = Field()

        assertEquals(DEL, field.clear())
    }

    /**
     * An edit that reports back exactly what the funnel asked the field to hold — a
     * `finishComposingText` arriving after the reset landed, a cursor-control gesture, focus loss —
     * is not a deletion of the character before it.
     */
    @Test
    fun `an edit reporting the reset value sends nothing`() {
        val field = Field()
        assertEquals("1", field.type("1"))

        assertEquals("", field.resetLands().deliverAgain())
    }

    @Test
    fun `Enter arrives as a carriage return`() {
        val field = Field()

        assertEquals("\r", field.type("\n"))
    }

    /** Whatever the diff makes of the anchors, an anchor itself never reaches the session. */
    @Test
    fun `the anchor is never sent`() {
        val field = Field()

        val sent = (1..6).map { field.resetLands().type("x") } +
            (1..3).map { field.resetLands().backspace() } +
            listOf(field.clear(), field.resetLands().type("y"), field.type("z"))

        assertFalse(sent.any { it.contains(ANCHOR) }, "a zero-width space reached the session")
    }

    /** Glide typing ending on the return key: one edit carrying text and the newline together. */
    @Test
    fun `text and Enter in one edit arrive together`() {
        val field = Field()

        assertEquals("ls\r", field.type("ls\n"))
    }

    /** A word arriving in one edit (paste, glide typing) is not taken apart. */
    @Test
    fun `text delivered in one edit arrives whole`() {
        val field = Field()

        assertEquals("ls -la", field.type("ls -la"))
        // And again with the caret among the anchors rather than at their end, which is where the
        // diff has to match from both sides to keep the anchors behind it out of the output.
        assertEquals("ls -la", field.resetLands().type("ls -la"))
    }


    /**
     * A gesture that takes several typed characters at once — swipe-to-delete-word, a selection
     * replaced — is one deletion per character the far side already has, not one for the gesture.
     */
    @Test
    fun `deleting several typed characters in one edit sends one DEL each`() {
        val field = Field()
        field.type("ls")

        assertEquals(DEL + DEL, field.deleteBack(2))
    }

    /** And a clear that takes several typed characters with it pays for each of them. */
    @Test
    fun `clearing a field with two typed characters sends one DEL each`() {
        val field = Field()
        field.type("ls")

        assertEquals(DEL + DEL, field.clear())
    }

    /**
     * The same gesture arriving before the reset lands takes the anchors with it. Charging the
     * anchor group its own Backspace on top would erase a character the user never typed — one
     * gesture would send a different number of bytes depending on whether a frame happened to run.
     */
    @Test
    fun `a bulk deletion before the reset lands sends one DEL per typed character`() {
        val field = Field()
        field.type("1")

        assertEquals(DEL, field.clear())
    }

    /**
     * An edit that differs from the baseline at both ends carries the anchors between the two
     * changed regions. They are the funnel's own scaffolding: a zero-width space on a shell line is
     * invisible in the command the production guard shows and in the history it is saved to.
     */
    @Test
    fun `the anchors between two changed regions are not sent`() {
        val field = Field()

        val sent = field.edit("x" + FUNNEL_TEXT + "y", FUNNEL_TEXT.length + 2)

        assertEquals(DEL + "xy", sent)
    }

    /**
     * The anchors are headroom for a Backspace repeating faster than the field is refilled: each
     * press takes one, and the field must not run out before a composition puts them back.
     */
    @Test
    fun `six backspaces before the reset lands each send one DEL`() {
        val field = Field()

        assertEquals(List(6) { DEL }, (1..6).map { field.backspace() })
    }

    /**
     * The trace as it was reported: `1234567` reached the shell as `11233455677`, because the
     * keyboard delivered every other digit twice and the reset only landed on some of them.
     */
    @Test
    fun `the digits of the reported trace arrive once each`() {
        val field = Field()

        val out = buildString {
            "1234567".forEachIndexed { i, digit ->
                if (i % 2 == 0) field.resetLands()
                append(field.type(digit.toString()))
                append(field.deliverAgain()) // the keyboard's twin edit, no composition between
            }
        }

        assertEquals("1234567", out)
    }

    /** The anchor has to be invisible: it is in the field a screen reader and a braille line read. */
    @Test
    fun `the anchor is a zero-width space`() {
        assertEquals(0x200b, ANCHOR.code)
    }

    /** Typing resumes after the field was emptied past its anchors. */
    @Test
    fun `a character typed after the field was emptied reaches the session`() {
        val field = Field()
        field.clear()

        assertEquals("5", field.type("5"))
    }
}

private val DEL = Char(0x7f).toString()

/**
 * The editor on the other side of the funnel: the buffer the IME edits, and the composition that
 * resets it.
 *
 * [resetLands] is the composition — it refills the buffer and tells the funnel so. Without it the
 * buffer keeps whatever the last edit left, which is what happens when the keyboard delivers two
 * edits inside one frame.
 */
private class Field {
    private val funnel = ImeFunnel()
    private var buffer = funnel.text
    private var caret = funnel.caret

    fun resetLands(): Field {
        buffer = funnel.text
        caret = funnel.caret
        funnel.resetLanded()
        return this
    }

    fun type(text: String): String {
        buffer = buffer.substring(0, caret) + text + buffer.substring(caret)
        caret += text.length
        return funnel.accept(buffer)
    }

    fun backspace(): String {
        if (caret == 0) return "" // nothing before the caret: the IME reports no edit at all
        buffer = buffer.removeRange(caret - 1, caret)
        caret--
        return funnel.accept(buffer)
    }

    fun deleteBack(n: Int): String {
        buffer = buffer.removeRange(caret - n, caret)
        caret -= n
        return funnel.accept(buffer)
    }

    /** An edit of any shape the IME cares to report. */
    fun edit(value: String, at: Int): String {
        buffer = value
        caret = at
        return funnel.accept(buffer)
    }

    fun clear(): String {
        buffer = ""
        caret = 0
        return funnel.accept(buffer)
    }

    /** The same edit delivered a second time, with no composition in between. */
    fun deliverAgain(): String = funnel.accept(buffer)
}
