package app.skerry.shared.ssh

import app.skerry.shared.sftp.SftpClient

/**
 * База для соединений с единственным байтовым потоком (Telnet, serial), встроенных под контракт
 * [SshConnection]. Возможности SSH, которых у таких протоколов нет (exec, SFTP, проброс портов),
 * собраны здесь и бросают [UnsupportedOperationException] с именем протокола [protocolName] —
 * наследники реализуют только openShell/disconnect/isConnected.
 */
abstract class StreamOnlyConnection(private val protocolName: String) : SshConnection {

    final override suspend fun exec(command: String): ExecResult =
        throw UnsupportedOperationException("$protocolName не поддерживает exec-каналы")

    final override suspend fun openSftp(): SftpClient =
        throw UnsupportedOperationException("$protocolName не поддерживает SFTP")

    final override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName не поддерживает проброс портов")

    final override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName не поддерживает проброс портов")

    final override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        throw UnsupportedOperationException("$protocolName не поддерживает проброс портов")
}
