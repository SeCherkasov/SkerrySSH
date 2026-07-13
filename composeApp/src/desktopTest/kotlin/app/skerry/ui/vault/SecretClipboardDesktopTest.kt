package app.skerry.ui.vault

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretClipboardDesktopTest {

    private fun clipboard() = Clipboard("test")

    private fun contents(cb: Clipboard): String? =
        runCatching { cb.getData(DataFlavor.stringFlavor) as? String }.getOrNull()

    @Test
    fun writesPasswordAndSchedulesClearWithConfiguredDelay() {
        val cb = clipboard()
        var scheduledDelay: Long? = null
        writePasswordToClipboard(cb, "s3cret", clearAfterSeconds = 30) { delay, _ ->
            scheduledDelay = delay
        }
        assertEquals("s3cret", contents(cb))
        assertEquals(30L, scheduledDelay)
    }

    @Test
    fun scheduledTaskClearsWhenClipboardStillHoldsOurPassword() {
        val cb = clipboard()
        var task: (() -> Unit)? = null
        writePasswordToClipboard(cb, "s3cret", clearAfterSeconds = 30) { _, t -> task = t }
        assertEquals("s3cret", contents(cb))
        task!!.invoke()
        // Cleared to an empty payload — our secret no longer lingers.
        assertTrue(contents(cb).isNullOrEmpty())
    }

    @Test
    fun scheduledTaskLeavesClipboardUntouchedWhenContentsChanged() {
        val cb = clipboard()
        var task: (() -> Unit)? = null
        writePasswordToClipboard(cb, "s3cret", clearAfterSeconds = 30) { _, t -> task = t }
        // Something else took over the clipboard before the timer fired.
        cb.setContents(StringSelection("user typed this"), null)
        task!!.invoke()
        assertEquals("user typed this", contents(cb))
    }

    @Test
    fun doesNotScheduleWhenDelayIsNotPositive() {
        val cb = clipboard()
        var scheduled = false
        writePasswordToClipboard(cb, "s3cret", clearAfterSeconds = 0) { _, _ -> scheduled = true }
        assertEquals("s3cret", contents(cb))
        assertTrue(!scheduled)
    }

    @Test
    fun clearIfStillOursIgnoresEmptyClipboard() {
        val cb = clipboard()
        // Never wrote anything; clearing must not throw and must not fabricate content.
        clearClipboardIfStillOurs(cb, "s3cret")
        assertNull(contents(cb))
    }

    @Test
    fun passwordClipCarriesKdePasswordManagerHint() {
        // Hint registration is best-effort; if the platform rejected it there is nothing to assert.
        val hint = kdePasswordHintFlavor ?: return
        val cb = clipboard()
        writePasswordToClipboard(cb, "s3cret", clearAfterSeconds = 0) { _, _ -> }
        assertTrue(cb.isDataFlavorAvailable(hint))
        assertEquals("secret", cb.getData(hint))
        // Plain-text consumers still see the password.
        assertEquals("s3cret", contents(cb))
    }

    @Test
    fun sensitiveSelectionRejectsUnknownFlavor() {
        val sel = SensitiveStringSelection("s3cret")
        assertTrue(sel.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("s3cret", sel.getTransferData(DataFlavor.stringFlavor))
        assertFailsWith<UnsupportedFlavorException> {
            sel.getTransferData(DataFlavor.imageFlavor)
        }
    }
}
