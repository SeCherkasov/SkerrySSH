package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.share.ShareUiState
import kotlinx.coroutines.delay

/**
 * The only thing session sharing draws over the terminal itself: a caret marking where a colleague
 * is typing ([CollaboratorCaret]), at the cell their keystrokes are landing in. Everything the host or a viewer can *do*
 * about the share — allowing input, answering a request for control, stopping — lives in the share
 * panel behind the toolbar's cast button, not over the screen someone is working on.
 *
 * Returns null when this pane is not the shared one or nobody is typing, so the caller can skip the
 * slot entirely.
 */
@Composable
internal fun rememberTypingHint(paneId: String): (@Composable (Modifier) -> Unit)? {
    val share = LocalSessionShare.current ?: return null
    val live = share.state as? ShareUiState.Live ?: return null
    if (share.sharedPaneId != paneId) return null
    val typingBy = live.typingBy
    // The hint fades on its own: the controller holds no clock, so the tick lives with the view.
    LaunchedEffect(typingBy) {
        if (typingBy != null) {
            delay(TYPING_HINT_TICK_MS)
            share.expireTypingHint(TYPING_HINT_TICK_MS)
        }
    }
    if (typingBy == null) return null
    return { modifier -> CollaboratorCaret(typingBy, modifier = modifier) }
}

/** How long the typing hint stays up after the last keystroke from a viewer. */
private const val TYPING_HINT_TICK_MS = 2_000L
