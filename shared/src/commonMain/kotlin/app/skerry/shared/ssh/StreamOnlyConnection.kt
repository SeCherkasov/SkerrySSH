package app.skerry.shared.ssh

import app.skerry.shared.sftp.SftpClient

/**
 * Base for single-byte-stream connections (Telnet, serial) fitted to the [SshConnection]
 * contract. SSH capabilities such protocols lack (exec, SFTP, port forwarding) are collected here
 * and throw [UnsupportedOperationException] naming the protocol [protocolName] — subclasses
 * implement only openShell/disconnect/isConnected.
 */
abstract class StreamOnlyConnection(private val protocolName: String) : SshConnection {

    final override suspend fun exec(command: String): ExecResult =
        throw UnsupportedOperationException("$protocolName does not support exec channels")

    final override suspend fun openSftp(): SftpClient =
        throw UnsupportedOperationException("$protocolName does not support SFTP")

    final override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName does not support port forwarding")

    final override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName does not support port forwarding")

    final override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName does not support port forwarding")
}
