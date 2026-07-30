package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_container_err_auth
import app.skerry.ui.generated.resources.conn_container_err_command
import app.skerry.ui.generated.resources.conn_container_err_connection
import app.skerry.ui.generated.resources.conn_container_err_host_key
import app.skerry.ui.generated.resources.conn_container_incomplete
import org.jetbrains.compose.resources.stringResource

/** Localized reason a container listing failed (shown under the container field). */
@Composable
fun containerBrowseFailureText(problem: ContainerBrowseProblem): String = when (problem) {
    ContainerBrowseProblem.AuthenticationFailed -> stringResource(Res.string.conn_container_err_auth)
    is ContainerBrowseProblem.HostKeyRejected ->
        problem.refusal?.let { hostKeyRefusalLine(it, hop = false) }
            ?: stringResource(Res.string.conn_container_err_host_key)
    ContainerBrowseProblem.ConnectionFailed -> stringResource(Res.string.conn_container_err_connection)
    ContainerBrowseProblem.IncompleteForm -> stringResource(Res.string.conn_container_incomplete)
    ContainerBrowseProblem.CommandFailed -> stringResource(Res.string.conn_container_err_command)
}
