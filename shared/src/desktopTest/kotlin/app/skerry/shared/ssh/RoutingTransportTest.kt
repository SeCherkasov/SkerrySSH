package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which transport an SSH target reaches: the jump host's shell only when the target says so. */
class RoutingTransportTest {

    private class Recording : SshTransport {
        val targets = mutableListOf<SshTarget>()
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
            targets += target
            return FakePooledConnection()
        }
    }

    @Test
    fun `an ssh target goes through its jump host's shell only when marked`() = runTest {
        val ssh = Recording()
        val jumpShell = Recording()
        val routing = RoutingTransport(ssh = ssh, jumpShell = jumpShell)
        val plain = SshTarget(host = "db", username = "me")
        routing.connect(plain, SshAuth.Interactive)
        routing.connect(plain.copy(jumpShell = true), SshAuth.Interactive)
        assertEquals(listOf(plain), ssh.targets)
        assertEquals(listOf(plain.copy(jumpShell = true)), jumpShell.targets)
    }
}
