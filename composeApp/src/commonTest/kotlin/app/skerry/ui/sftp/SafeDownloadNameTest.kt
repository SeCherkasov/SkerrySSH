package app.skerry.ui.sftp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name a download's "save to…" dialog is seeded with.
 *
 * It is the one hop where a name the remote side chose becomes part of a local file name, so it
 * carries a directory part it must lose and formatting it must not keep — a preset the user accepts
 * without reading is the whole point of a preset.
 *
 * Written as escapes, never as the characters themselves.
 */
class SafeDownloadNameTest {

    @Test
    fun `an ordinary name is kept`() {
        assertEquals("invoice.pdf", safeDownloadName("invoice.pdf"))
    }

    @Test
    fun `a directory part is dropped, posix and windows alike`() {
        assertEquals("authorized_keys", safeDownloadName("../../.ssh/authorized_keys"))
        assertEquals("evil.exe", safeDownloadName("..\\windows\\evil.exe"))
    }

    @Test
    fun `a bidi override never reaches the dialog`() {
        assertEquals("invoicegnp.exe", safeDownloadName("invoice\u202Egnp.exe"))
    }

    /** A leading dot would save a file the file manager then hides. */
    @Test
    fun `a leading dot is dropped`() {
        assertEquals("bashrc", safeDownloadName(".bashrc"))
    }

    @Test
    fun `a name with nothing left falls back to something savable`() {
        assertEquals("download", safeDownloadName("\u202E\u200B"))
        assertEquals("download", safeDownloadName("/"))
    }
}
