package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_jump_error_cycle
import app.skerry.ui.generated.resources.conn_jump_error_missing
import app.skerry.ui.generated.resources.conn_jump_error_no_credential
import app.skerry.ui.generated.resources.conn_jump_error_not_ssh
import app.skerry.ui.generated.resources.conn_jump_error_ok
import app.skerry.ui.generated.resources.conn_jump_error_title
import org.jetbrains.compose.resources.stringResource

/** Localized explanation of a [JumpChainProblem] (connect dialogs, test-connection status). */
@Composable
fun jumpProblemText(problem: JumpChainProblem): String = stringResource(
    when (problem) {
        JumpChainProblem.MISSING_HOST -> Res.string.conn_jump_error_missing
        JumpChainProblem.NOT_SSH -> Res.string.conn_jump_error_not_ssh
        JumpChainProblem.NO_CREDENTIAL -> Res.string.conn_jump_error_no_credential
        JumpChainProblem.CYCLE -> Res.string.conn_jump_error_cycle
    },
)

/**
 * "Jump host unavailable" notice: shown instead of connecting when [resolveJumpChain] failed —
 * the session must not silently fall back to a direct connection. Shared by desktop and mobile.
 */
@Composable
fun JumpErrorDialog(problem: JumpChainProblem, onDismiss: () -> Unit) {
    NoticeDialog(
        title = stringResource(Res.string.conn_jump_error_title),
        message = jumpProblemText(problem),
        buttonLabel = stringResource(Res.string.conn_jump_error_ok),
        onDismiss = onDismiss,
    )
}
