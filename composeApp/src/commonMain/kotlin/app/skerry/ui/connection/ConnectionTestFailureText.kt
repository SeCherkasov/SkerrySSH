package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_test_err_auth
import app.skerry.ui.generated.resources.conn_test_err_connection
import app.skerry.ui.generated.resources.conn_test_err_host_key
import app.skerry.ui.generated.resources.conn_test_incomplete
import org.jetbrains.compose.resources.stringResource

/** Localized reason for a failed "Test connection" check (modal footer). */
@Composable
fun connectionTestFailureText(problem: ConnectionTestProblem): String = when (problem) {
    ConnectionTestProblem.AuthenticationFailed -> stringResource(Res.string.conn_test_err_auth)
    ConnectionTestProblem.HostKeyRejected -> stringResource(Res.string.conn_test_err_host_key)
    ConnectionTestProblem.ConnectionFailed -> stringResource(Res.string.conn_test_err_connection)
    ConnectionTestProblem.IncompleteForm -> stringResource(Res.string.conn_test_incomplete)
    is ConnectionTestProblem.Jump -> jumpProblemText(problem.problem)
}
