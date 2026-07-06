package app.skerry.shared.ssh

import app.skerry.shared.serial.SerialTransport
import app.skerry.shared.telnet.TelnetTransport

/**
 * Transport router: delegates connection setup to the right implementation — SSH (sshj), Telnet or
 * Serial — based on [SshTarget.connectionType]. Keeps the session/terminal/reconnect stack
 * (`ConnectionController` in the UI layer) working over any of the three through a single call site.
 *
 * [ssh] is injected from outside (carries its own [HostKeyVerifier]/known-hosts); Telnet/Serial are
 * stateless and default-constructed, but can be swapped in tests too.
 */
class RoutingTransport(
    private val ssh: SshTransport,
    private val telnet: SshTransport = TelnetTransport(),
    private val serial: SshTransport = SerialTransport(),
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        when (target.connectionType) {
            ConnectionType.SSH -> ssh.connect(target, auth)
            ConnectionType.TELNET -> telnet.connect(target, auth)
            ConnectionType.SERIAL -> serial.connect(target, auth)
        }
}
