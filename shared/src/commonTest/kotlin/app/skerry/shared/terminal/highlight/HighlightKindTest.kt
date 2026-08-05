package app.skerry.shared.terminal.highlight

import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.UnderlineStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HighlightKindTest {

    @Test
    fun `none returns the very same style`() {
        val style = TermStyle(bold = true)
        assertSame(style, HighlightKind.None.applyTo(style))
    }

    @Test
    fun `command is green and bold`() {
        val style = HighlightKind.Command.applyTo(TermStyle())
        assertEquals(TermColor.Indexed(2), style.fg)
        assertTrue(style.bold)
    }

    @Test
    fun `subcommand is green without bold`() {
        val style = HighlightKind.Subcommand.applyTo(TermStyle())
        assertEquals(TermColor.Indexed(2), style.fg)
        assertFalse(style.bold)
    }

    @Test
    fun `every command-line category maps to a palette color or dim`() {
        val kinds = listOf(
            HighlightKind.Command, HighlightKind.Subcommand, HighlightKind.Option,
            HighlightKind.StringLit, HighlightKind.PathLit, HighlightKind.Operator,
            HighlightKind.Variable, HighlightKind.Comment,
        )
        for (kind in kinds) {
            val style = kind.applyTo(TermStyle())
            val changed = style.fg != TermColor.Default || style.dim
            assertTrue(changed, "$kind changed nothing")
            // Only palette indices — an Rgb literal would ignore the active theme.
            assertTrue(style.fg is TermColor.Indexed || style.fg == TermColor.Default, "$kind used a literal color")
        }
    }

    @Test
    fun `comment dims without recoloring`() {
        val style = HighlightKind.Comment.applyTo(TermStyle())
        assertTrue(style.dim)
        assertEquals(TermColor.Default, style.fg)
    }

    @Test
    fun `base attributes survive`() {
        val base = TermStyle(
            bg = TermColor.Indexed(4),
            italic = true,
            underlineStyle = UnderlineStyle.Curly,
            strikethrough = true,
        )
        val style = HighlightKind.Option.applyTo(base)
        assertEquals(TermColor.Indexed(4), style.bg)
        assertTrue(style.italic)
        assertEquals(UnderlineStyle.Curly, style.underlineStyle)
        assertTrue(style.strikethrough)
    }

    @Test
    fun `error level is red and bold`() {
        val style = HighlightKind.LevelError.applyTo(TermStyle())
        assertEquals(TermColor.Indexed(1), style.fg)
        assertTrue(style.bold)
    }

    @Test
    fun `timestamp is dimmed`() {
        assertTrue(HighlightKind.Timestamp.applyTo(TermStyle()).dim)
    }
}
