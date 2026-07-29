package app.skerry.ui.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteScreenshotNameTest {

    @Test
    fun a_host_name_becomes_a_file_name() {
        assertEquals("skerry-web-01.lan-20260729-101500.png", screenshotFileName("web-01.lan", "20260729-101500"))
    }

    @Test
    fun path_separators_and_spaces_cannot_escape_the_name() {
        val name = screenshotFileName("../../etc/passwd desktop", "1")
        assertTrue('/' !in name && ' ' !in name, name)
        assertEquals("skerry-etc-passwd-desktop-1.png", name)
    }

    @Test
    fun a_long_name_is_cut_without_leaving_a_dangling_separator() {
        // The cut lands on the dash this name is padded with; a name ending in one would read as
        // "skerry-host--20260729-101500.png".
        val name = screenshotFileName("host" + "-x".repeat(40), "1")
        assertTrue(name.length <= "skerry-".length + 48 + "-1.png".length, name)
        assertTrue("--" !in name && "-." !in name, name)
    }

    @Test
    fun a_nameless_desktop_still_gets_a_file() {
        assertEquals("skerry-desktop-1.png", screenshotFileName("///", "1"))
    }
}
