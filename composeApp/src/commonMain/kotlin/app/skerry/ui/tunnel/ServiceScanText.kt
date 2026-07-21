package app.skerry.ui.tunnel

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_service_port
import org.jetbrains.compose.resources.stringResource

/** Text under a failed scan: the typed reason if there is one, else the transport message. */
@Composable
fun serviceScanFailureText(state: ServiceScanState.Failed): String =
    state.reason?.let { tunnelUnavailableText(it) } ?: state.message

/** Name of a discovered service: the owning process, or the port when the host didn't disclose it. */
@Composable
fun serviceLabel(service: ListeningService): String =
    service.process ?: stringResource(Res.string.ports_service_port, service.port)
