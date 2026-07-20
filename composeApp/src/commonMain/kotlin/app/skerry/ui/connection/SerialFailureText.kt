package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.shared.serial.SerialProblem
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_serial_err_configure
import app.skerry.ui.generated.resources.conn_serial_err_not_found
import app.skerry.ui.generated.resources.conn_serial_err_open
import app.skerry.ui.generated.resources.conn_serial_err_permission
import app.skerry.ui.generated.resources.conn_serial_err_unsupported
import org.jetbrains.compose.resources.stringResource

/**
 * Localized explanation of a [SerialProblem]; [port] is the device name the problem refers to.
 * Same text on desktop and Android — the platforms report the same typed causes.
 */
@Composable
fun serialProblemText(problem: SerialProblem, port: String?): String {
    val name = port.orEmpty()
    return when (problem) {
        SerialProblem.UNSUPPORTED -> stringResource(Res.string.conn_serial_err_unsupported)
        SerialProblem.PORT_NOT_FOUND -> stringResource(Res.string.conn_serial_err_not_found, name)
        SerialProblem.PERMISSION_DENIED -> stringResource(Res.string.conn_serial_err_permission, name)
        SerialProblem.OPEN_FAILED -> stringResource(Res.string.conn_serial_err_open, name)
        SerialProblem.CONFIGURE_FAILED -> stringResource(Res.string.conn_serial_err_configure, name)
    }
}
