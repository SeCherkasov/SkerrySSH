package app.skerry.shared.serial

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection
import app.skerry.shared.ssh.StreamShellChannel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Транспорт последовательного порта, встроенный под тот же контракт [SshTransport], что и SSH/Telnet,
 * чтобы весь стек терминала/сессий переиспользовался без изменений. Возможности SSH (SFTP, проброс,
 * exec) отсутствуют и бросают [UnsupportedOperationException].
 *
 * Конфигурация приходит через [SshTarget]: [SshTarget.host] — имя устройства, [SshTarget.port] —
 * скорость (baud). Аутентификации у serial нет — [SshAuth] игнорируется. Открытие делает платформенный
 * [SerialSystem]; [openPort] инъектируется для тестов (по умолчанию — реальный порт).
 */
class SerialTransport(
    private val openPort: (SerialConfig) -> SerialPortHandle = { SerialSystem.open(it) },
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val config = SerialConfig(portName = target.host, baudRate = target.port)
            val handle = try {
                openPort(config)
            } catch (e: SerialUnavailableException) {
                throw SshConnectionException(e.message ?: "Не удалось открыть порт ${target.host}", e)
            }
            SerialConnection(handle)
        }
}

/**
 * Соединение поверх одного открытого порта: единственный интерактивный поток. Отсутствующие у serial
 * возможности SSH (exec, SFTP, пробросы) бросает база [StreamOnlyConnection].
 */
private class SerialConnection(private val handle: SerialPortHandle) : StreamOnlyConnection("Serial") {

    private val shellOpened = AtomicBoolean(false)

    override val isConnected: Boolean get() = handle.isOpen

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Порт уже открыл свой поток" }
        return SerialShellChannel(handle)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { handle.close() } }
    }
}

/**
 * Интерактивный поток последовательного порта. Каркас read-цикла/close — в базе
 * [app.skerry.shared.ssh.StreamShellChannel]: нативный serial-read не реагирует на Thread.interrupt
 * (unblockReadOnCancel = true — отмена сбора закрывает порт), а `read < 0` — это отключение
 * устройства, не «сервер закрыл shell» (eofOnStreamEnd = false). Записи сериализованы [writeLock].
 * У serial нет размера окна — [resize] no-op.
 */
private class SerialShellChannel(private val handle: SerialPortHandle) :
    StreamShellChannel(unblockReadOnCancel = true, eofOnStreamEnd = false) {

    private val writeLock = Mutex()

    override val isOpen: Boolean get() = handle.isOpen

    override fun readBlocking(buffer: ByteArray): Int = handle.read(buffer)

    override fun closeSource() {
        runCatching { handle.close() } // разблокирует read в output
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                handle.write(data)
                countBytesUp(data.size)
            } catch (e: IOException) {
                throw SshConnectionException("Запись в последовательный порт не удалась", e)
            }
        }
    }

    override suspend fun resize(size: PtySize) { /* у последовательного порта нет размера окна */ }
}
