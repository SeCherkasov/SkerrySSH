package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform wrappers for CLIPBOARD <-> plain text over the Compose suspend clipboard
 * ([androidx.compose.ui.platform.Clipboard]), replacing the deprecated `ClipboardManager.getText/setText`.
 *
 * Builds a [ClipEntry] from plain text for writing to the system clipboard (selection copy, OSC 52).
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/**
 * Extracts plain text from a system-clipboard [ClipEntry] (for paste), or `null` if there is none.
 * On desktop, only AWT's `stringFlavor` is requested (no scanning other formats); on Android, the
 * text of the first ClipData item is used.
 */
internal expect fun ClipEntry.readPlainText(): String?

/**
 * Direct CLIPBOARD read path bypassing Compose/AWT, needed on Wayland: reading via AWT with a foreign
 * serialized flavor (per IntelliJ) unconditionally prints a JDK stack trace to System.err. Reads via
 * `wl-paste` instead, touching no AWT, so nothing is logged. Returns `null` when no direct path exists
 * (X11/Windows/macOS/Android), so the caller falls back to the regular Compose clipboard, and also when
 * the clipboard is simply empty. Throws when the path exists but could not answer — a caller that shows
 * that as "nothing to paste" claims a read that never happened.
 */
internal expect fun readSystemClipboardDirect(): String?

/**
 * Direct CLIPBOARD write path bypassing Compose/AWT (Wayland, `wl-copy`), paired with
 * [readSystemClipboardDirect] so Wayland reads and writes go through the same buffer (`wl-clipboard`)
 * instead of mixing with XWayland-AWT. Returns `true` if written via the direct path; `false` means no
 * direct path exists and the caller should write via the Compose clipboard.
 */
internal expect fun writeSystemClipboardDirect(text: String): Boolean

/**
 * Whether the direct path owns CLIPBOARD entirely, in both directions. When `true` (Wayland with
 * `wl-clipboard`), the caller must not fall back to Compose/AWT — not on an empty read, where a
 * non-text clipboard would trigger the noisy JDK trace again (AWT `getContents`), and not on a failed
 * write, which would leave the text in the XWayland buffer while reads keep coming from `wl-paste`
 * (#282). The first call may block (resolving utilities) — call off the UI thread.
 */
internal expect fun systemClipboardDirectOwnsClipboard(): Boolean

/**
 * System CLIPBOARD text (terminal paste, `${{clipboard}}` snippet variable). On Wayland the direct
 * path takes over reading (wl-paste, bypassing AWT) and never falls back to Compose even on an
 * empty result — a non-text clipboard would raise a noisy JDK trace. The subprocess and utility
 * resolution stay off the UI thread (Default); `getClipEntry` (suspend, waits on the UI thread)
 * runs on the caller context.
 */
private suspend fun fetchSystemClipboardText(
    clipboard: Clipboard,
    direct: DirectClipboard = PlatformDirectClipboard,
): String? =
    withContext(Dispatchers.Default) {
        if (direct.owns()) direct.read() else null
    } ?: if (direct.owns()) null else clipboard.getClipEntry()?.readPlainText()

/**
 * System CLIPBOARD text in both directions, as the app has to talk to it: the direct path
 * (`wl-copy`/`wl-paste`) where one exists, the Compose clipboard everywhere else. A type rather than
 * two more free functions because the callers are composables — a bridge that silently talked to AWT
 * under Wayland is exactly the bug this hides (#282), and only an injectable clipboard lets a test
 * that runs in neither session kind see which path was taken.
 */
internal interface SystemClipboard {

    /**
     * CLIPBOARD text, or `null` when there is none (or it is not text). Throws whatever the underlying
     * clipboard throws — an empty clipboard and one that refused to answer are different facts.
     */
    suspend fun read(): String?

    /** Puts [text] on CLIPBOARD. Throws whatever the underlying clipboard throws. */
    suspend fun write(text: String)
}

/**
 * The platform's own clipboard where it has one Compose/AWT cannot see — `wl-clipboard` on Wayland,
 * nothing anywhere else. A type in front of the `expect` functions so a test can drive both branches:
 * they answer differently on a Wayland desktop and on a headless CI, so nothing written against them
 * directly would mean the same thing twice.
 */
internal interface DirectClipboard {

    /** Whether this path owns CLIPBOARD outright. May block on the first call — ask it off the UI thread. */
    fun owns(): Boolean

    /** Text on the clipboard, `null` if there is none. Throws if the path could not answer at all. */
    fun read(): String?

    /** `true` if the text landed. */
    fun write(text: String): Boolean
}

/** The direct path this platform actually has. */
internal object PlatformDirectClipboard : DirectClipboard {
    override fun owns(): Boolean = systemClipboardDirectOwnsClipboard()
    override fun read(): String? = readSystemClipboardDirect()
    override fun write(text: String): Boolean = writeSystemClipboardDirect(text)
}

/** [SystemClipboard] over the Compose [clipboard], with [direct] owning it wherever it exists. */
internal fun systemClipboard(
    clipboard: Clipboard,
    direct: DirectClipboard = PlatformDirectClipboard,
): SystemClipboard = object : SystemClipboard {

    override suspend fun read(): String? = fetchSystemClipboardText(clipboard, direct)

    override suspend fun write(text: String) {
        // Where the direct path owns the clipboard it owns failures too: writing through Compose/AWT
        // after a refused `wl-copy` would put the text in the XWayland buffer while reads keep coming
        // from `wl-paste` — the split #282 is about — and would report it as a copy that worked.
        val landed = withContext(Dispatchers.Default) { if (direct.owns()) direct.write(text) else null }
        when (landed) {
            null -> clipboard.setClipEntry(plainTextClipEntry(text))
            false -> error("the platform clipboard refused the text")
            true -> Unit
        }
    }
}

/**
 * A clipboard standing in for the platform's, for the composables that reach for one deep inside a
 * screen rather than taking it as a parameter ([TerminalScreen]'s copy/paste, the assistant's Copy).
 * `null` — the real one. Ambient rather than threaded through: the screens are public API of the
 * module and [SystemClipboard] is not.
 */
internal val LocalSystemClipboard = staticCompositionLocalOf<SystemClipboard?> { null }

/** The system clipboard of the current composition, remembered per [LocalClipboard]. */
@Composable
internal fun rememberSystemClipboard(): SystemClipboard {
    LocalSystemClipboard.current?.let { return it }
    val clipboard = LocalClipboard.current
    return remember(clipboard) { systemClipboard(clipboard) }
}
