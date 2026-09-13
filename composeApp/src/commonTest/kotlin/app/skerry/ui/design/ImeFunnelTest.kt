package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The funnel's arithmetic: what a given edit owes the session, in bytes.
 *
 * Nothing here pins that the field is reverted before the next edit is read — [Field] assumes it,
 * because production does it inside the edit and this file has no Compose to observe it with. The
 * revert itself is pinned in `ImeFunnelFieldTest`, at the layer that can break it. What is left is
 * the diff, and a keyboard's delivery pattern is visible in it: a field trace from a Galaxy S24
 * showed Samsung Keyboard delivering part of the number row as two edits ~2 ms apart carrying the
 * same contents (`commitText` then `finishComposingText`, composition already null), and the second
 * one has to be worth nothing.
 */
class ImeFunnelTest {

    /**
     * The keyboard's twin edit for one keypress: nothing changed since the funnel reverted the
     * first one, so nothing is owed. Reading it against a stale value put the digit in twice.
     */
    @Test
    fun `an edit reporting the resting value sends nothing`() {
        val field = Field()
        assertEquals("1", field.type("1"))

        assertEquals("", field.deliverAgain())
    }

    /** The rule above must not cost a repeated digit its second press. */
    @Test
    fun `the same character typed four times reaches the shell four times`() {
        val field = Field()

        val sent = (1..4).map { field.type("1") }

        assertEquals(List(4) { "1" }, sent)
    }

    /**
     * A keypress that repeats a character already sent is a keypress, not a deletion of it: `aca`
     * reached the shell as `ac` when an edit was read against what the previous one left behind.
     */
    @Test
    fun `a repeated character is not read as a deletion`() {
        val field = Field()

        val sent = listOf(field.type("a"), field.type("c"), field.type("a"), field.type("a"))

        assertEquals(listOf("a", "c", "a", "a"), sent)
    }

    /**
     * Deleting an anchor is how a Backspace becomes visible at all: nothing the user typed is ever
     * left in the field for the IME to delete.
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

        val sent = (1..6).map { field.backspace() }

        assertEquals(List(6) { DEL }, sent)
    }

    /** And an edit that empties the field outright leaves the diff nothing to match from either end. */
    @Test
    fun `clearing the whole field sends one DEL`() {
        val field = Field()

        assertEquals(DEL, field.clear())
    }

    /** A character typed after the field was emptied past its anchors still reaches the session. */
    @Test
    fun `a character typed after the field was emptied reaches the session`() {
        val field = Field()
        field.clear()

        assertEquals("5", field.type("5"))
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

        val sent = (1..6).map { field.type("x") } +
            (1..3).map { field.backspace() } +
            listOf(field.clear(), field.type("y"), field.type("z"))

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
        assertEquals("ls -la", field.edit(FUNNEL_TEXT.take(2) + "ls -la" + FUNNEL_TEXT.drop(2)))
    }

    /**
     * An edit that differs from the anchors at both ends carries the anchors between the two
     * changed regions. They are the funnel's own scaffolding: a zero-width space on a shell line is
     * invisible in the command the production guard shows and in the history it is saved to.
     */
    @Test
    fun `the anchors between two changed regions are not sent`() {
        val field = Field()

        val sent = field.edit("x" + FUNNEL_TEXT + "y")

        assertEquals(DEL + "xy", sent)
    }

    /**
     * A gesture that takes several characters at once — swipe-to-delete-word, a selection replaced,
     * a batch of held-Backspace repeats — is one deletion. All it can take is anchors, so the count
     * says nothing about how many presses it was, and charging per anchor would erase characters
     * nobody typed. At a login prompt the difference is how much of the password the far side loses.
     */
    @Test
    fun `an edit deleting several anchors is one DEL`() {
        val field = Field()

        assertEquals(DEL, field.deleteBack(3))
        assertEquals(DEL, field.deleteBack(FUNNEL_TEXT.length))
    }

    /**
     * The trace as it was reported: `1234567` reached the shell as `11233455677`, because the
     * keyboard delivered every other digit twice and each twin was read against a stale value.
     */
    @Test
    fun `the digits of the reported trace arrive once each`() {
        val field = Field()

        val out = buildString {
            "1234567".forEach { digit ->
                append(field.type(digit.toString()))
                append(field.deliverAgain()) // the keyboard's twin edit for the same keypress
            }
        }

        assertEquals("1234567", out)
    }

    /** The anchor has to be invisible: it is in the field a screen reader and a braille line read. */
    @Test
    fun `the anchor is a zero-width space`() {
        assertEquals(0x200b, ANCHOR.code)
    }
}

private val DEL = Char(0x7f).toString()

/**
 * The editor on the other side of the funnel, as the transformation sees it: every edit starts from
 * the anchors, because the previous one was reverted inside itself.
 */
private class Field {
    private val caret = FUNNEL_TEXT.length

    private fun commit(value: String): String = editBetween(FUNNEL_TEXT, value)

    fun type(text: String): String =
        commit(FUNNEL_TEXT.substring(0, caret) + text + FUNNEL_TEXT.substring(caret))

    fun backspace(): String = deleteBack(1)

    fun deleteBack(n: Int): String = commit(FUNNEL_TEXT.removeRange(caret - n, caret))

    /** An edit of any shape the IME cares to report. */
    fun edit(value: String): String = commit(value)

    fun clear(): String = commit("")

    /** The same keypress delivered a second time, after the funnel reverted the first one. */
    fun deliverAgain(): String = commit(FUNNEL_TEXT)
}
