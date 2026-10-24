package app.skerry.ui.connection

import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.securitykey.SecurityKeyException
import app.skerry.shared.securitykey.SecurityKeyFailure
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Local hardware failures must survive probes just as they survive a real session connect. */
class ConnectionProbeSecurityKeyFailureTest {
    private val target = SshTarget(host = "host", port = 22, username = "user")
    private val auth = SshAuth.PublicKey("stub consumed only by the fake")

    @Test
    fun testConnectionPreservesLocalPinFailure() = runTest {
        val transport = ProbeHardwareFailureTransport(SecurityKeyException(SecurityKeyFailure.PinInvalid))
        val result = assertIs<ConnectionTestStatus.Failure>(runConnectionTest(transport, target, auth))
        assertNotEquals(ConnectionTestProblem.ConnectionFailed, result.problem, "Local PIN failure needs its actionable reason")
    }

    @Test
    fun containerBrowsePreservesLocalTokenFailure() = runTest {
        val transport = ProbeHardwareFailureTransport(SecurityKeyException(SecurityKeyFailure.NoDevice))
        val result = assertIs<ContainerBrowseStatus.Failure>(listContainers(transport, target, auth, ContainerSpec()))
        assertNotEquals(ContainerBrowseProblem.ConnectionFailed, result.problem, "Missing hardware is not a generic connection failure")
    }

    @Test
    fun testConnectionPreservesWrappedHardwareFailure() = runTest {
        val error = SshAuthenticationException("authentication wrapper", SecurityKeyException(SecurityKeyFailure.PinBlocked))
        val result = assertIs<ConnectionTestStatus.Failure>(runConnectionTest(ProbeHardwareFailureTransport(error), target, auth))
        assertNotEquals(ConnectionTestProblem.AuthenticationFailed, result.problem, "A blocked local PIN needs local recovery advice")
    }

    @Test
    fun containerBrowsePreservesWrappedHardwareFailure() = runTest {
        val error = SshAuthenticationException("authentication wrapper", SecurityKeyException(SecurityKeyFailure.Disabled))
        val result = assertIs<ContainerBrowseStatus.Failure>(listContainers(ProbeHardwareFailureTransport(error), target, auth, ContainerSpec()))
        assertNotEquals(ContainerBrowseProblem.AuthenticationFailed, result.problem, "The local opt-in needs enable-setting advice")
    }
}

private class ProbeHardwareFailureTransport(private val error: Exception) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = throw error
}
