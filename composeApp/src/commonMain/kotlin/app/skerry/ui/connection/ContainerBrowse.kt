package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.container.ContainerEntry
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.container.containerListCommandLine
import app.skerry.shared.container.parseContainerList
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Why "Browse containers" came back empty-handed. Typed, not a message string, so the view renders
 * localized text (`containerBrowseFailureText`) — same rule as [ConnectionTestProblem].
 * [COMMAND_FAILED] covers the runtime side: no `docker`/`kubectl` on the host, no permission on the
 * socket, no cluster context — all indistinguishable from here and all fixed on the host.
 */
enum class ContainerBrowseProblem {
    AUTHENTICATION_FAILED,
    HOST_KEY_REJECTED,
    CONNECTION_FAILED,
    INCOMPLETE_FORM,
    COMMAND_FAILED,
}

/**
 * State of the container picker: [Idle] — not opened yet; [Loading] — probe in flight; [Loaded] —
 * the host's list (possibly empty: nothing is running); [Failure] — with a typed [problem].
 */
sealed interface ContainerBrowseStatus {
    data object Idle : ContainerBrowseStatus
    data object Loading : ContainerBrowseStatus
    data class Loaded(val entries: List<ContainerEntry>) : ContainerBrowseStatus
    data class Failure(val problem: ContainerBrowseProblem) : ContainerBrowseStatus
}

/**
 * One-shot listing of what can be entered on [target]'s host: connect, run the runtime's list
 * command, parse, disconnect. The probe dials as PLAIN SSH — a container session refuses exec
 * channels on purpose (they'd act on the host), and the listing is exactly such a host-level call.
 *
 * Transport exceptions map to typed problems by category, never to raw text (it would leak
 * library/host detail into the UI); [CancellationException] is rethrown, and the temporary
 * connection is closed unconditionally ([NonCancellable]) so a cancelled probe leaves no socket
 * behind. Same shape as [runConnectionTest], and covered by
 * [app.skerry.ui.connection.ContainerBrowseTest].
 */
suspend fun listContainers(
    transport: SshTransport,
    target: SshTarget,
    auth: SshAuth,
    spec: ContainerSpec,
): ContainerBrowseStatus = try {
    val conn = transport.connect(
        target.copy(connectionType = ConnectionType.SSH, container = null, shellCommand = null),
        auth,
    )
    try {
        val result = conn.exec(containerListCommandLine(spec))
        // A non-zero exit means the CLI itself refused (missing binary, denied socket, no context):
        // the stdout we might have is not a listing.
        if (result.exitCode != null && result.exitCode != 0) {
            ContainerBrowseStatus.Failure(ContainerBrowseProblem.COMMAND_FAILED)
        } else {
            ContainerBrowseStatus.Loaded(parseContainerList(spec.runtime, result.stdout))
        }
    } finally {
        withContext(NonCancellable) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
                // swallow the temporary connection's close error
            }
        }
    }
} catch (e: SshAuthenticationException) {
    ContainerBrowseStatus.Failure(ContainerBrowseProblem.AUTHENTICATION_FAILED)
} catch (e: SshHostKeyRejectedException) {
    ContainerBrowseStatus.Failure(ContainerBrowseProblem.HOST_KEY_REJECTED)
} catch (e: SshConnectionException) {
    ContainerBrowseStatus.Failure(ContainerBrowseProblem.CONNECTION_FAILED)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ContainerBrowseStatus.Failure(ContainerBrowseProblem.CONNECTION_FAILED)
}

/**
 * Compose wrapper over [listContainers]: holds [status] as state and runs the probe on [scope].
 * A repeat [load] cancels the previous one; [reset] returns to [ContainerBrowseStatus.Idle] (the
 * picker closed, or the host/auth fields changed and the old list is no longer this host's).
 */
@Stable
class ContainerBrowseController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
) {
    var status: ContainerBrowseStatus by mutableStateOf(ContainerBrowseStatus.Idle)
        private set

    private var job: Job? = null

    fun load(target: SshTarget, auth: SshAuth, spec: ContainerSpec) {
        job?.cancel()
        status = ContainerBrowseStatus.Loading
        job = scope.launch {
            status = listContainers(transport, target, auth, spec)
        }
    }

    /** Report a pre-connect failure (form without host/credentials) without dialing anything. */
    fun fail(problem: ContainerBrowseProblem) {
        job?.cancel()
        status = ContainerBrowseStatus.Failure(problem)
    }

    fun reset() {
        job?.cancel()
        status = ContainerBrowseStatus.Idle
    }
}
