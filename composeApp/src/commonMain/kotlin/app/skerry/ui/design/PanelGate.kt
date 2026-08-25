package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Ties a panel's own open flag to the condition that feeds it: when [available] goes false, [close]
 * runs.
 *
 * The flag and the condition are two different things, and the gap between them is invisible until
 * it bites. A palette opened over a session that then drops is hidden by the render guard beside
 * it — but the flag is still set, so the moment the pane reconnects the panel springs back over the
 * shell the user just got their caret in, unasked. Every popup and sheet that can outlive its
 * session needs this, on the desktop toolbar and on the phone's session screen alike, which is why
 * it is written once here rather than a fourth time at the next call site.
 */
@Composable
fun CloseWhenUnavailable(available: Boolean, close: () -> Unit) {
    LaunchedEffect(available) { if (!available) close() }
}
