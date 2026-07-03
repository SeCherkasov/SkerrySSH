package app.skerry.shared.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Транспортный контракт SSH-ядра. Платформенные реализации подставляются снаружи:
 * на desktop — sshj (JVM), на мобильных — своя реализация позже.
 */
interface SshTransport {
    /**
     * @throws SshConnectionException сетевая ошибка или обрыв транспорта
     * @throws SshHostKeyRejectedException ключ хоста отвергнут [HostKeyVerifier]
     * @throws SshAuthenticationException сервер не принял учётные данные
     */
    suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection
}

/**
 * Цель подключения. [connectionType] выбирает транспорт ([RoutingTransport] диспетчеризует по нему):
 * для [ConnectionType.SSH] значимы все поля; для [ConnectionType.TELNET] — только [host]/[port]
 * ([username]/auth не используются); для [ConnectionType.SERIAL] [host] несёт имя устройства
 * (`/dev/ttyUSB0`, `COM3`), а [port] — скорость (baud). Дефолт [ConnectionType.SSH] сохраняет
 * поведение всех прежних вызовов, строящих таргет без указания типа.
 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val connectionType: ConnectionType = ConnectionType.SSH,
)

sealed interface SshAuth {
    // Секрет как String: на JVM не обнуляется, переход на затираемый буфер — отдельный шаг.
    data class Password(val secret: String) : SshAuth {
        override fun toString(): String = "Password(redacted)"
    }

    /**
     * Аутентификация по приватному ключу: [privateKeyPem] — содержимое PEM (OpenSSH/PKCS),
     * [passphrase] расшифровывает ключ (null — ключ без passphrase). Секрет берётся из vault
     * ([app.skerry.shared.vault.CredentialSecret.PrivateKey]).
     */
    data class PublicKey(val privateKeyPem: String, val passphrase: String? = null) : SshAuth {
        override fun toString(): String = "PublicKey(redacted)"
    }

    /**
     * Аутентификация по SSH-сертификату: клиент предъявляет [certificate] (строка `*-cert.pub`,
     * выданная CA) и доказывает владение приватным ключом [privateKeyPem] (PEM, [passphrase]
     * расшифровывает его при необходимости). Секрет берётся из vault
     * ([app.skerry.shared.vault.CredentialSecret.Certificate]).
     */
    data class Certificate(
        val privateKeyPem: String,
        val certificate: String,
        val passphrase: String? = null,
    ) : SshAuth {
        override fun toString(): String = "Certificate(redacted)"
    }
}

/**
 * Решение о доверии ключу хоста. Fingerprint — в формате OpenSSH
 * (`SHA256:` + base64 без паддинга), keyType — идентификатор алгоритма
 * (`ssh-ed25519`, `rsa-sha2-512`, …). Персистентный known-hosts появится
 * вместе с менеджером хостов.
 */
fun interface HostKeyVerifier {
    fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean
}

interface SshConnection {
    val isConnected: Boolean

    /**
     * Согласованный при установке соединения симметричный шифр (направление client→server) в нотации
     * SSH (`chacha20-poly1305@openssh.com`, `aes256-gcm@openssh.com`, `aes256-ctr`, …), либо `null`,
     * если транспорт его не сообщает. Статичен на всё время жизни соединения. Реализация по умолчанию —
     * `null` (фейки/тесты), реальный транспорт перекрывает.
     */
    val cipher: String? get() = null

    /**
     * Идентификационная строка сервера (remote ident) в полной форме `SSH-2.0-<software>`, напр.
     * `SSH-2.0-OpenSSH_8.9p1`, либо `null`, если транспорт её не сообщает. Статична на всё время
     * жизни соединения. Реализация по умолчанию — `null` (фейки/тесты), реальный транспорт перекрывает.
     */
    val serverVersion: String? get() = null

    /**
     * Замерить round-trip до сервера (мс): шлёт keep-alive-запрос с ожиданием ответа и возвращает
     * время до отклика, либо `null`, если соединение мертво/ответ не пришёл в разумный срок. Каждый
     * вызов — один round-trip (попутно держит соединение живым). Реализация по умолчанию — `null`
     * (фейки/тесты), реальный транспорт перекрывает. Периодичность задаёт вызывающий (поллер UI).
     */
    suspend fun measureRoundTrip(): Long? = null

    /** Одноразовый exec-канал для неинтерактивных команд. */
    suspend fun exec(command: String): ExecResult

    /**
     * Интерактивный shell с PTY.
     * @throws SshConnectionException канал открыть не удалось
     */
    suspend fun openShell(size: PtySize = PtySize(), term: String = "xterm-256color"): ShellChannel

    /**
     * Открыть SFTP-подсистему поверх этого соединения. Каждый вызов — отдельный канал;
     * закрывать через [app.skerry.shared.sftp.SftpClient.close]. Соединение остаётся открытым.
     * @throws SshConnectionException SFTP-подсистему открыть не удалось
     */
    suspend fun openSftp(): app.skerry.shared.sftp.SftpClient

    /**
     * Поднять локальный проброс портов (`-L`) поверх этого соединения. Слушатель живёт, пока не
     * вызван [PortForward.close]; соединение остаётся открытым. См. [LocalForwardSpec].
     * @throws PortForwardException слушатель не поднялся (порт занят) или обрыв канала
     */
    suspend fun forwardLocal(spec: LocalForwardSpec): PortForward

    /**
     * Поднять обратный проброс портов (`-R`) поверх этого соединения. Сервер слушает у себя, пока не
     * вызван [PortForward.close]; соединение остаётся открытым. См. [RemoteForwardSpec].
     * @throws PortForwardException сервер отверг запрос или обрыв канала
     */
    suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward

    /**
     * Поднять динамический проброс (`-D`) поверх этого соединения: на нашей машине запускается
     * SOCKS5-прокси, а адрес назначения каждый клиент задаёт сам. Слушатель живёт, пока не вызван
     * [PortForward.close]; соединение остаётся открытым. См. [DynamicForwardSpec].
     * @throws PortForwardException слушатель не поднялся (порт занят)
     */
    suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward

    suspend fun disconnect()
}

/** Размер PTY; пиксельные размеры опциональны (0 — не сообщать). */
data class PtySize(
    val cols: Int = 80,
    val rows: Int = 24,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

interface ShellChannel {
    val isOpen: Boolean

    /**
     * Суммарно записано в PTY (ввод/отчёты) и прочитано из PTY (вывод) за время жизни канала, в байтах.
     * Монотонно растут. Нужны для индикатора скорости в статус-баре (дельту/период считает
     * `ThroughputController` в UI-слое; ссылкой не сошлёшься — модуль shared про UI не знает).
     * Реализация по умолчанию — `0` (фейки/тесты).
     */
    val bytesUp: Long get() = 0L
    val bytesDown: Long get() = 0L

    /**
     * Сырой вывод PTY (stdout и stderr слиты, как в реальном терминале).
     * Холодный flow с единственным разрешённым сборщиком: повторный collect
     * бросает [IllegalStateException]. Завершается на EOF канала.
     */
    val output: Flow<ByteArray>

    /**
     * После завершения [output]: true, если канал дошёл до штатного EOF (сервер закрыл shell сам —
     * например, по команде `exit`), false — если [output] оборвался ошибкой транспорта либо канал
     * закрыли через [close]. До завершения [output] значение не определено. По умолчанию false
     * (фейки/тесты, не сообщающие причину закрытия). Нужно, чтобы отличать штатный выход (→ закрыть
     * сессию) от обрыва (→ авто-реконнект); см. [app.skerry.shared.terminal.TerminalState.Closed].
     */
    val endedWithEof: Boolean get() = false

    /**
     * Подавлен ли эхо-ответ сервера прямо сейчас (ввод пароля / line-mode): по нему верхний слой
     * НЕ отслеживает набранную строку и не пишет её в историю автодополнения, чтобы секреты не
     * оседали в памяти/подсказках. По умолчанию `false` (эхо есть): SSH-транспорт не сообщает
     * termios-состояние, поэтому у него всегда `false` (остаточный риск для in-session паролей задокументирован),
     * а Telnet перекрывает по согласованной опции ECHO.
     */
    val echoSuppressed: Boolean get() = false

    /** @throws SshConnectionException канал закрыт или обрыв транспорта */
    suspend fun write(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

data class ExecResult(
    /** null, если сервер закрыл канал без статуса. */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SshConnectionException(message: String, cause: Throwable? = null) : SshException(message, cause)

class SshHostKeyRejectedException(message: String) : SshException(message)

class SshAuthenticationException(message: String, cause: Throwable? = null) : SshException(message, cause)
