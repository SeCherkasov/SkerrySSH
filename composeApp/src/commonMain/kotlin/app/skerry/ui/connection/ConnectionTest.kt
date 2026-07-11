package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Result of a "Test connection" check: a one-shot connect to the host without opening a session.
 * [Idle] — not run yet; [Checking] — connect in flight; [Success] — connection established (with
 * round-trip ms if the transport reported it, else `null`); [Failure] — with a human-readable
 * message describing the cause (auth/host key/network).
 */
sealed interface ConnectionTestStatus {
    data object Idle : ConnectionTestStatus
    data object Checking : ConnectionTestStatus
    data class Success(val roundTripMillis: Long?) : ConnectionTestStatus
    data class Failure(val message: String) : ConnectionTestStatus
}

/**
 * One-shot connectivity check: connect, measure round-trip (if available), then disconnect right
 * away — the connection is temporary, no session is opened. Transport exceptions are mapped to
 * [ConnectionTestStatus.Failure] with a friendly message by category (auth/host key/network); the
 * raw transport exception text is never surfaced in the UI (would leak library internals/host
 * address). A ping failure alone doesn't fail the test (the connection already succeeded).
 * [CancellationException] is rethrown (cooperative cancellation isn't masked); the temporary
 * connection is closed unconditionally ([NonCancellable]) so cancellation never leaves a socket
 * open. Pure suspend function — covered by [app.skerry.ui.connection.ConnectionTestTest].
 */
suspend fun runConnectionTest(
    transport: SshTransport,
    target: SshTarget,
    auth: SshAuth,
): ConnectionTestStatus = try {
    val conn = transport.connect(target, auth)
    try {
        val rtt = try {
            conn.measureRoundTrip()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // ping failed but the connect succeeded — still a success
        }
        ConnectionTestStatus.Success(rtt)
    } finally {
        // Close unconditionally: even if the coroutine was already cancelled, the temporary connection must not stay open.
        withContext(NonCancellable) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
                // swallow the temporary connection's close error
            }
        }
    }
} catch (e: SshAuthenticationException) {
    ConnectionTestStatus.Failure("Authentication failed")
} catch (e: SshHostKeyRejectedException) {
    ConnectionTestStatus.Failure("Host key rejected")
} catch (e: SshConnectionException) {
    ConnectionTestStatus.Failure("Connection failed")
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ConnectionTestStatus.Failure("Connection failed")
}

/**
 * Compose wrapper over [runConnectionTest]: holds [status] as state and runs the check on [scope].
 * A repeat [test] cancels the previous check; [reset] returns to [ConnectionTestStatus.Idle] (e.g.
 * when form fields are edited — the old result is no longer relevant).
 */
@Stable
class ConnectionTestController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
) {
    var status: ConnectionTestStatus by mutableStateOf(ConnectionTestStatus.Idle)
        private set

    private var job: Job? = null

    fun test(target: SshTarget, auth: SshAuth) {
        job?.cancel()
        status = ConnectionTestStatus.Checking
        job = scope.launch {
            status = runConnectionTest(transport, target, auth)
        }
    }

    /**
     * Report a pre-connect failure (e.g. the profile's jump chain didn't resolve) as the test's
     * result without dialing anything. [message] is already localized by the caller.
     */
    fun fail(message: String) {
        job?.cancel()
        status = ConnectionTestStatus.Failure(message)
    }

    fun reset() {
        job?.cancel()
        status = ConnectionTestStatus.Idle
    }
}
