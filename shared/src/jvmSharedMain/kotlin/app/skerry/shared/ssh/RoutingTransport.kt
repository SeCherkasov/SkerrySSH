package app.skerry.shared.ssh

import app.skerry.shared.container.ContainerTransport
import app.skerry.shared.local.LocalShellTransport
import app.skerry.shared.mosh.MoshTransport
import app.skerry.shared.serial.SerialTransport
import app.skerry.shared.telnet.TelnetTransport

/**
 * Transport router: delegates connection setup to the right implementation — SSH (sshj), Mosh,
 * Telnet, Serial, a container exec or a local shell — based on [SshTarget.connectionType]. Keeps the
 * session/terminal/reconnect stack (`ConnectionController` in the UI layer) working over any of them
 * through a single call site.
 *
 * [ssh] is injected from outside (carries its own [HostKeyVerifier]/known-hosts); Mosh and container
 * exec both bootstrap over that same SSH transport, so host-key trust and ProxyJump behave
 * identically. Telnet/Serial/Local are stateless and default-constructed, but can be swapped in
 * tests too.
 */
class RoutingTransport(
    private val ssh: SshTransport,
    private val telnet: SshTransport = TelnetTransport(),
    private val serial: SshTransport = SerialTransport(),
    private val mosh: SshTransport = MoshTransport(ssh),
    private val local: SshTransport = LocalShellTransport(),
    private val container: SshTransport = ContainerTransport(ssh),
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        when (target.connectionType) {
            ConnectionType.SSH -> ssh.connect(target, auth)
            ConnectionType.MOSH -> mosh.connect(target, auth)
            ConnectionType.TELNET -> telnet.connect(target, auth)
            ConnectionType.SERIAL -> serial.connect(target, auth)
            ConnectionType.LOCAL -> local.connect(target, auth)
            ConnectionType.CONTAINER -> container.connect(target, auth)
            // The remote-desktop protocols are framebuffer-and-input, not shell/terminal — each has
            // its own transport and never reaches this SSH-shaped router.
            ConnectionType.VNC ->
                throw IllegalArgumentException("VNC is not an SSH transport; use VncTransport")

            ConnectionType.RDP ->
                throw IllegalArgumentException("RDP is not an SSH transport; use RdpTransport")
        }
}
