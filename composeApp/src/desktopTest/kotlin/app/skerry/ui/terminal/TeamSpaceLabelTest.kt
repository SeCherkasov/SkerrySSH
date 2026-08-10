package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The label a shared team space gets in the sidebar.
 *
 * A team's name is written by whoever created it and travels inside the sealed envelope, so the
 * server never sees it and cannot validate it. The label is drawn on the folder header and is also
 * the collapse chevron's accessible name, which makes it the same spoofing surface as a server's
 * terminal title: a bidi override in it makes a folder announce as a space other than the one its
 * hosts belong to.
 *
 * Written as escapes, never as the characters themselves — they are invisible in a diff, and a
 * reviewer could not tell the fixture from the expectation.
 */
class TeamSpaceLabelTest {

    @Test
    fun `a bidi override in a peer's team name is dropped`() {
        val label = spaceLabel("ops\u202Eprod\u202C", fallback = FALLBACK)
        assertEquals("opsprod", label)
        assertFalse(label.any { it.category == CharCategory.FORMAT })
    }

    @Test
    fun `the zero-width formatters go too`() {
        assertEquals("ops team", spaceLabel("ops\u200B te\u200Dam", fallback = FALLBACK))
    }

    /** Control bytes go as well: the label also keys the collapsed-folder set. */
    @Test
    fun `control characters are dropped rather than kept`() {
        assertEquals("opsprod", spaceLabel("ops\u0000prod", fallback = FALLBACK))
    }

    /** Cut before filtering: a pathologically long name is not scanned in full to be thrown away. */
    @Test
    fun `a name longer than the cap is cut to it`() {
        assertEquals(MAX_SPACE_NAME_CHARS, spaceLabel("o".repeat(5_000), fallback = FALLBACK).length)
    }

    /**
     * The cap counts UTF-16 units, so it can fall between the halves of an astral character; the
     * label would then draw U+FFFD. Same case the OSC title path pins for its own cut.
     */
    @Test
    fun `the cut is not made through a surrogate pair`() {
        val name = "o".repeat(MAX_SPACE_NAME_CHARS - 1) + "\uD83D\uDE80"
        val label = spaceLabel(name, fallback = FALLBACK)
        assertEquals(MAX_SPACE_NAME_CHARS - 1, label.length)
        assertFalse(label.any { it.isSurrogate() }, "half of the emoji was left in the label")
    }

    /** A name made only of what the sanitizer drops would leave a blank, unidentifiable folder. */
    @Test
    fun `a name that sanitizes away falls back to something identifiable`() {
        assertEquals(FALLBACK, spaceLabel("\u202E\u200B\u200D", fallback = FALLBACK))
    }

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Платформа · прод", spaceLabel("Платформа · прод", fallback = FALLBACK))
    }
}

private const val FALLBACK = "t-4f2a91"
