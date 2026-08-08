package app.skerry.shared.io

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNamesTest {

    @Test
    fun separators_and_traversal_collapse_to_dashes() {
        assertEquals("etc-passwd", safeFileStem("../../etc/passwd", fallback = "x"))
        assertEquals("a-b-c", safeFileStem("a/b\\c", fallback = "x"))
    }

    @Test
    fun dots_survive_only_when_asked_for_and_never_two_in_a_row() {
        assertEquals("web-01-lan", safeFileStem("web-01.lan", fallback = "x"))
        assertEquals("web-01.lan", safeFileStem("web-01.lan", fallback = "x", keepDots = true))
        assertEquals("a-b", safeFileStem("a..b", fallback = "x", keepDots = true))
    }

    @Test
    fun a_stem_with_nothing_printable_falls_back() {
        assertEquals("session", safeFileStem("   ", fallback = "session"))
        assertEquals("session", safeFileStem("///", fallback = "session"))
        assertEquals("session", safeFileStem("", fallback = "session"))
    }

    @Test
    fun the_cut_never_leaves_a_dangling_separator() {
        val stem = safeFileStem("host" + "-x".repeat(40), fallback = "x")
        assertEquals(48, stem.length)
        assertEquals(stem.trim('-', '.'), stem)
    }

    @Test
    fun lowercasing_and_dots_compose() {
        assertEquals("web-01.lan", safeFileStem("WEB-01.LAN", fallback = "x", keepDots = true, lowercase = true))
    }

    @Test
    fun underscores_are_part_of_a_name_not_a_separator() {
        assertEquals("deploy_box", safeFileStem("deploy_box", fallback = "x"))
    }

    @Test
    fun a_windows_device_name_is_pushed_out_of_the_way() {
        // "aux.pem" is the AUX device on Windows, whatever the extension.
        assertEquals("aux-key", safeFileStem("AUX", fallback = "key", lowercase = true))
        assertEquals("com1-key", safeFileStem("com1", fallback = "key"))
        // Only the stem before the first dot decides; "auxiliary" is an ordinary name.
        assertEquals("auxiliary", safeFileStem("auxiliary", fallback = "key"))
    }
}
