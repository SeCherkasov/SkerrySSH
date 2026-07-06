package app.skerry.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePrefsTest {

    private fun temp(): Path = Files.createTempDirectory("skerry-prefs")

    @Test
    fun boolRoundTripsAndDefaultsWhenMissing() {
        val prefs = FilePrefs(temp())
        // No file yet, so the default is returned as-is.
        assertTrue(prefs.bool("info_panel", true))
        assertFalse(prefs.bool("terminal_show_title", false))
        prefs.set("info_panel", false)
        assertFalse(prefs.bool("info_panel", true))
        prefs.set("terminal_show_title", true)
        assertTrue(prefs.bool("terminal_show_title", false))
    }

    @Test
    fun boolKeepsLegacyGarbageSemantics() {
        val dir = temp()
        val prefs = FilePrefs(dir)
        Files.writeString(dir.resolve("flag"), "garbage")
        // Legacy read* semantics: default=true reads `!= "0"` (garbage -> true),
        // default=false reads `== "1"` (garbage -> false).
        assertTrue(prefs.bool("flag", true))
        assertFalse(prefs.bool("flag", false))
    }

    @Test
    fun intRoundTripsAndDefaultsOnGarbage() {
        val dir = temp()
        val prefs = FilePrefs(dir)
        assertEquals(8, prefs.int("recent_limit", 8))
        prefs.set("recent_limit", 3)
        assertEquals(3, prefs.int("recent_limit", 8))
        Files.writeString(dir.resolve("recent_limit"), "not-a-number")
        assertEquals(8, prefs.int("recent_limit", 8))
    }

    @Test
    fun idParsesAndFallsBackWhenParseThrows() {
        val prefs = FilePrefs(temp())
        prefs.set("auto_lock", "5m")
        assertEquals("5m", prefs.id("auto_lock", "1m") { it })
        // No file -> default; parse throws -> default.
        assertEquals("1m", prefs.id("missing", "1m") { it })
        prefs.set("auto_lock", "junk")
        assertEquals("1m", prefs.id("auto_lock", "1m") { if (it == "junk") error("unknown") else it })
    }

    @Test
    fun linesRoundTripsTrimmingBlanksAndOrder() {
        val prefs = FilePrefs(temp())
        assertEquals(emptyList(), prefs.lines("recent_connections"))
        prefs.setLines("recent_connections", listOf("b", "a", "c"))
        assertEquals(listOf("b", "a", "c"), prefs.lines("recent_connections"))
        prefs.setLines("recent_connections", emptyList())
        assertEquals(emptyList(), prefs.lines("recent_connections"))
    }

    @Test
    fun setLinesDropsValuesWithNewlines() {
        val prefs = FilePrefs(temp())
        prefs.setLines("collapsed_groups", listOf("ok", "bad\nsplit", "bad\rtoo", "fine"))
        assertEquals(listOf("ok", "fine"), prefs.lines("collapsed_groups"))
    }

    @Test
    fun writesCreateDirectoryAndSwallowFailures() {
        val dir = temp().resolve("nested")
        val prefs = FilePrefs(dir)
        prefs.set("info_panel", true) // directory doesn't exist yet, gets created
        assertTrue(prefs.bool("info_panel", false))
        // Writing over an unreadable path doesn't throw (best-effort).
        val filePrefs = FilePrefs(dir.resolve("info_panel")) // "directory" is actually an existing file
        filePrefs.set("x", 1)
        assertEquals(7, filePrefs.int("x", 7))
    }
}
