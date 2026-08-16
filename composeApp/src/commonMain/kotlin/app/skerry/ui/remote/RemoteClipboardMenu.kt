package app.skerry.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.ClippedNotice
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Txt
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.sanitizedFits
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_clipboard_blank
import app.skerry.ui.generated.resources.rd_clipboard_copy_here
import app.skerry.ui.generated.resources.rd_clipboard_empty
import app.skerry.ui.generated.resources.rd_clipboard_failed
import app.skerry.ui.generated.resources.rd_clipboard_from_remote
import app.skerry.ui.generated.resources.rd_clipboard_send
import app.skerry.ui.generated.resources.rd_clipboard_share
import app.skerry.ui.generated.resources.rd_clipboard_unprintable
import app.skerry.ui.terminal.fetchSystemClipboardText
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.terminal.writeSystemClipboardDirect
import app.skerry.ui.theme.Skerry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The clipboard half of a remote desktop's menus: what the remote machine last put on its clipboard,
 * how it is drawn, and the two directions text can be moved in. Split out of the bar's own menus
 * because the drawing policy for a string a server chose is a subject of its own.
 */

/**
 * Whether text crosses at all, what the remote machine last put on its clipboard, and the two ways
 * to move it. The gate sits here rather than in the display menu: it is the clipboard's own switch,
 * and it has to be reachable from the button it turns off.
 */
@Composable
internal fun ClipboardMenu(screen: RemoteDesktopScreenState, actions: ClipboardActions) {
    val shared = screen.clipboardShared
    Column(Modifier.fillMaxWidth()) {
        CheckRow(stringResource(Res.string.rd_clipboard_share), shared, screen::toggleClipboardShared)
        if (shared) {
            HLine(modifier = Modifier.padding(vertical = 4.dp))
            val remote = screen.serverClipboard
            Column(
                Modifier.fillMaxWidth().padding(horizontal = ROW_PADDING, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(stringResource(Res.string.rd_clipboard_from_remote), color = Skerry.colors.faint, size = 10.sp)
                // The preview and what it does not show are one block, spaced on their own: the
                // notice keeps a node in the tree even when it says nothing (see [StatusAnnouncer]),
                // and as a sibling of the spaced column that silent node would push the buttons down
                // on every clipboard.
                Column {
                    // Text a server wrote, and on VNC it is on the local clipboard already — the
                    // bridge mirrors it as it arrives — so this line is not a gate, it is the user's
                    // one look at what they are carrying, and it has to read as the string that was
                    // copied. Sanitized like every other foreign string, and pinned left-to-right on
                    // top of that: the sanitizer drops the format characters, not the strong RTL
                    // letters that reorder a line on their own.
                    //
                    // Walked once per clipboard rather than once per recomposition: the menu
                    // recomposes on a failed copy and on hover, and the string can be megabytes of
                    // whatever the server chose to send.
                    val preview = remember(remote) { remote?.let(::clipboardPreview) }
                    // Whether the *drawing* ran out of room, which the filter cannot know: four
                    // lines of a 220dp panel hold far less than the cap it keeps.
                    var overflowed by remember(preview) { mutableStateOf(false) }
                    Txt(
                        when {
                            preview == null -> stringResource(Res.string.rd_clipboard_empty)
                            // Three ways to have nothing to draw, and they are not the same fact.
                            // Only the first is "nothing was copied"; in the other two the button
                            // below still hands over what the server sent, so saying so would lie.
                            preview.blank -> stringResource(Res.string.rd_clipboard_blank)
                            preview.drawn.isBlank() -> stringResource(Res.string.rd_clipboard_unprintable)
                            else -> preview.drawn
                        },
                        color = if (preview == null || preview.drawn.isBlank()) Skerry.colors.dim else Skerry.colors.text,
                        size = 11.5.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textDirection = TextDirection.Ltr,
                        // Remembered, not rebuilt per recomposition: the text node compares this
                        // callback by identity and re-measures its paragraph whenever it changes.
                        onTextLayout = remember(preview) { { layout -> overflowed = layout.hasVisualOverflow } },
                    )
                    // Asked of the filter as well as of the layout: the filter stops reading a long
                    // enough string before its end, so a clipboard padded past the scan bound would
                    // otherwise draw as a short, whole-looking line while the button carries the
                    // rest. Never over a whitespace-only clipboard — `blank` is asked of the whole
                    // string, so there is nothing behind what the filter cut; the length goes with
                    // it, which is deliberate: how many spaces there are is not a fact worth a line.
                    //
                    // Announced only for the filter's cut: the box's four lines cut what is *drawn*,
                    // and the node still carries the whole string, so a screen reader has already
                    // read what the notice would be claiming is missing.
                    ClippedNotice(
                        clipped = preview != null && !preview.blank && (!preview.whole || overflowed),
                        fullLength = remote?.length,
                        announce = preview?.whole == false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (remote != null) {
                        SmallButton(stringResource(Res.string.rd_clipboard_copy_here)) { actions.copyHere(remote) }
                    }
                    SmallButton(stringResource(Res.string.rd_clipboard_send), onClick = actions.sendMine)
                }
                if (actions.failed) {
                    Txt(stringResource(Res.string.rd_clipboard_failed), color = Skerry.colors.sunset, size = 10.5.sp)
                }
            }
        }
    }
}

/**
 * Moving text across, and whether the last attempt failed. Remembered by the screen for the same
 * reason as [ScreenshotAction]: the menu these buttons live in is a popup that closes on the click
 * that dismisses it, and a clipboard call launched in its scope would be cancelled on the way out.
 */
@Immutable
class ClipboardActions(val failed: Boolean, val copyHere: (String) -> Unit, val sendMine: () -> Unit)

@Composable
fun rememberClipboardActions(screen: RemoteDesktopScreenState?): ClipboardActions {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // The system clipboard refuses often enough to be worth saying so: a busy X11 owner, a sandboxed
    // Android app. Both directions are user-initiated presses, so silence would read as "nothing to
    // send" rather than "this did not work".
    var failed by remember { mutableStateOf(false) }
    // Counted, not just flagged: a second failure while the note is up is not a state change, so
    // without this the timer would keep the first one's schedule and the note would vanish right
    // after the user pressed again — the same reason [RemoteBarState.revealCount] exists.
    var failures by remember { mutableStateOf(0) }
    LaunchedEffect(failed, failures) {
        if (failed) {
            delay(SHOT_NOTE_MS)
            failed = false
        }
    }
    val report: (Boolean) -> Unit = { ok ->
        if (!ok) {
            failed = true
            failures++
        }
    }
    return remember(screen, scope, failed) {
        ClipboardActions(
            failed = failed,
            copyHere = { text ->
                scope.launch {
                    report(
                        try {
                            // Wayland reads go through wl-paste ([fetchSystemClipboardText]), so the
                            // write takes wl-copy first — otherwise the two ends of this menu would
                            // be on different buffers and "Send mine" would return older text.
                            if (!withContext(Dispatchers.Default) { writeSystemClipboardDirect(text) }) {
                                clipboard.setClipEntry(plainTextClipEntry(text))
                            }
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        },
                    )
                }
                Unit
            },
            sendMine = {
                if (screen != null) {
                    scope.launch {
                        report(
                            try {
                                fetchSystemClipboardText(clipboard)?.let(screen::onLocalClipboard)
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            },
                        )
                    }
                }
                Unit
            },
        )
    }
}

/**
 * What the clipboard menu draws for one remote string: the filtered line, whether the string was
 * nothing but whitespace to begin with (which the filter erases, and which is not the same fact as
 * "nothing that draws"), and whether the filter reached the end of it.
 */
@Immutable
private class ClipboardPreview(val drawn: String, val blank: Boolean, val whole: Boolean)

/** How much of a remote clipboard the preview keeps. */
private const val CLIPBOARD_PREVIEW = 400

private fun clipboardPreview(remote: String) = ClipboardPreview(
    drawn = sanitizeServerText(remote, CLIPBOARD_PREVIEW, allowNewlines = true),
    blank = remote.isBlank(),
    whole = sanitizedFits(remote, CLIPBOARD_PREVIEW, allowNewlines = true),
)
