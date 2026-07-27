package app.skerry.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import app.skerry.ui.terminal.MirroredInput
import app.skerry.ui.terminal.TerminalScreenState

/**
 * Keeps the synchronized-input wiring of every tab in step with its toggle
 * ([Tab.syncInput]): while it is on, each pane's terminal mirrors what it delivers into the
 * tab's other connected panes (tmux `synchronize-panes`); while it is off, the hooks are cleared.
 *
 * Lives at the app root next to [app.skerry.ui.host.ProdGuardSync] rather than in the pane
 * composable: a pane keeps its wiring while its tab is in the background, and a tab that is not
 * on screen has no composed terminal to hang it off.
 */
@Composable
fun PaneSyncBinder(sessions: SessionsController?) {
    val open = sessions?.tabs ?: return
    for (tab in open) {
        key(tab.id) {
            for (pane in tab.panes) {
                key(pane.id) {
                    val terminal = pane.liveTerminal
                    val on = tab.syncInput
                    // SideEffect for the same reason as the guard binding: a plain state write that
                    // must land with the composition that decided it, not a frame later.
                    SideEffect {
                        terminal?.inputMirror = if (on) { text, kind -> mirrorPaneInput(tab, pane.id, text, kind) } else null
                    }
                }
            }
        }
    }
}

/**
 * Deliver input typed in pane [originPaneId] to the tab's other connected panes.
 *
 * The copies are delivered with the guard off and mirroring off: the origin pane already held and
 * confirmed the command for the whole group (with synchronized input its guard runs under the
 * strictest policy of the panes, see [app.skerry.ui.host.ProdGuardSync]), and a mirrored keystroke
 * that mirrored again would bounce between panes forever.
 */
internal fun mirrorPaneInput(tab: Tab, originPaneId: String, text: String, kind: MirroredInput) {
    val origin = tab.pane(originPaneId)?.liveTerminal
    paneSyncTargets(origin, tab.syncTargetsFrom(originPaneId)).forEach { target ->
        when (kind) {
            MirroredInput.Typed -> target.typeInput(text, guarded = false, mirror = false)
            MirroredInput.Pasted -> target.paste(text, mirror = false)
        }
    }
}

/**
 * The panes input from [origin] may actually be mirrored into, out of [targets].
 *
 * While the origin is taking a secret ([TerminalScreenState.awaitingSecret]) only panes that are at
 * a prompt of their own qualify. Entering one sudo password across the group is what the toggle is
 * for, but the panes are independent sessions and reach their prompts at their own pace: a pane
 * sitting at an ordinary shell would echo the password on screen, write it into that host's command
 * history, and then run it as a command when the Enter arrives.
 */
internal fun paneSyncTargets(
    origin: TerminalScreenState?,
    targets: List<TerminalScreenState>,
): List<TerminalScreenState> =
    if (origin?.awaitingSecret == true) targets.filter { it.awaitingSecret } else targets
