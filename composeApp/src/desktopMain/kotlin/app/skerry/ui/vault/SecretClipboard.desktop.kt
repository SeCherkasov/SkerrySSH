package app.skerry.ui.vault

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Desktop mirrors the Android password path: the clip is auto-cleared after
 * [CLIPBOARD_CLEAR_SECONDS] (best-effort — only if it still holds our string, so we never wipe what
 * the user copied next), and it carries a KDE password-manager hint so Klipper keeps it out of
 * clipboard history. Linux and Windows do have OS-level clipboard managers (Klipper, GPaste, CopyQ,
 * Win+V), so leaving a password on the clipboard indefinitely is a real leak.
 */
actual fun copyPasswordToClipboard(password: String) {
    writePasswordToClipboard(
        Toolkit.getDefaultToolkit().systemClipboard,
        password,
        CLIPBOARD_CLEAR_SECONDS,
        ::scheduleClipboardClear,
    )
}

/** Non-secret text uses the same clipboard path as the password (no sensitivity hint, no clear). */
actual fun copyTextToClipboard(text: String) {
    // setContents can throw IllegalStateException when the clipboard is momentarily unavailable
    // (X11 selection contention); a failed copy must not blow up the click handler.
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

/** Single daemon thread; the app never needs more than one pending clipboard clear at a time. */
private val clipboardClearScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "secret-clipboard-clear").apply { isDaemon = true }
}

private fun scheduleClipboardClear(delaySeconds: Long, task: () -> Unit) {
    clipboardClearScheduler.schedule(task, delaySeconds, TimeUnit.SECONDS)
}

/**
 * Places [password] on [clipboard] with a sensitivity hint, and — when [clearAfterSeconds] is
 * positive — arms [schedule] to clear it later. Extracted from the actual so it can run against a
 * headless [Clipboard] in tests.
 */
internal fun writePasswordToClipboard(
    clipboard: Clipboard,
    password: String,
    clearAfterSeconds: Int,
    schedule: (delaySeconds: Long, task: () -> Unit) -> Unit,
) {
    // setContents can throw IllegalStateException when the clipboard is momentarily unavailable
    // (X11 selection contention with Klipper/GPaste, Windows clipboard busy). Fail closed and don't
    // arm the clear timer for a write that never landed.
    val wrote = runCatching {
        clipboard.setContents(SensitiveStringSelection(password), null)
    }.isSuccess
    if (wrote && clearAfterSeconds > 0) {
        schedule(clearAfterSeconds.toLong()) { clearClipboardIfStillOurs(clipboard, password) }
    }
}

/** Clears [clipboard] only if it still carries [password], so we don't wipe unrelated content. */
internal fun clearClipboardIfStillOurs(clipboard: Clipboard, password: String) {
    val current = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
    if (current == password) {
        runCatching { clipboard.setContents(StringSelection(""), null) }
    }
}

/** X11 selection target KDE's Klipper checks (value `secret`) to exclude an entry from history. */
private const val KDE_PASSWORD_HINT_NATIVE = "x-kde-passwordManagerHint"

/**
 * The [DataFlavor] backing [KDE_PASSWORD_HINT_NATIVE], registered on the system flavor map so AWT
 * exports it as the exact X11 target Klipper looks for. Null if the platform rejects the mapping
 * (e.g. headless with no flavor map) — in which case we simply omit the hint.
 */
internal val kdePasswordHintFlavor: DataFlavor? = runCatching {
    val flavor = DataFlavor("application/x-kde-passwordManagerHint;class=java.lang.String")
    (SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap).apply {
        addUnencodedNativeForFlavor(flavor, KDE_PASSWORD_HINT_NATIVE)
        addFlavorForUnencodedNative(KDE_PASSWORD_HINT_NATIVE, flavor)
    }
    flavor
}.getOrNull()

/**
 * A clipboard payload that offers the password as plain text plus, where registration succeeded, the
 * KDE password-manager hint. Managers that don't understand the hint just see a string.
 */
internal class SensitiveStringSelection(private val text: String) : Transferable {
    private val hint = kdePasswordHintFlavor

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        listOfNotNull(DataFlavor.stringFlavor, hint).toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.stringFlavor || (hint != null && flavor == hint)

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        DataFlavor.stringFlavor -> text
        hint -> "secret"
        else -> throw UnsupportedFlavorException(flavor)
    }
}
