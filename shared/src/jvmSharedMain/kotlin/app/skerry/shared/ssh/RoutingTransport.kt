package app.skerry.shared.ssh

import app.skerry.shared.serial.SerialTransport
import app.skerry.shared.telnet.TelnetTransport

/**
 * Маршрутизатор транспортов: по [SshTarget.connectionType] делегирует установку соединения нужной
 * реализации — SSH (sshj), Telnet или Serial. Так весь стек сессий/терминала/реконнекта
 * (`ConnectionController` в UI-слое) остаётся един и работает поверх любого из трёх,
 * а место выбора протокола — одно.
 *
 * [ssh] инжектируется снаружи (несёт свой [HostKeyVerifier]/known-hosts); Telnet/Serial без состояния,
 * поэтому создаются по умолчанию, но их тоже можно подменить в тестах.
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
