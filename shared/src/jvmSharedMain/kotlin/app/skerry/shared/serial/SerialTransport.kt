package app.skerry.shared.serial

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
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
 * Интерактивный поток последовательного порта: блокирующий [SerialPortHandle.read] крутится на
 * [Dispatchers.IO] через [runInterruptible] (отмена корутины прерывает чтение), прочитанные байты
 * идут в [output]. Записи сериализованы [writeLock]. У serial нет размера окна — [resize] no-op.
 */
private class SerialShellChannel(private val handle: SerialPortHandle) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    override val bytesUp: Long get() = _bytesUp.get()
    override val bytesDown: Long get() = _bytesDown.get()

    private val writeLock = Mutex()

    override val isOpen: Boolean get() = handle.isOpen

    override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output поддерживает только одного сборщика"
        }
        // Нативный serial-read не реагирует на Thread.interrupt: закрываем порт из обработчика
        // завершения Job, чтобы отмена сбора разблокировала read (порт закрыт → read вернёт -1/ошибку).
        val disposable = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { handle.close() } }
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                // Только IOException = обрыв порта (штатное завершение цикла); CancellationException
                // от runInterruptible должна пройти наружу — это отмена сбора, а не конец данных.
                val read = try {
                    runInterruptible(Dispatchers.IO) { handle.read(buffer) }
                } catch (_: IOException) {
                    break
                }
                if (read < 0) break // порт закрыт/устройство отключено
                if (read == 0) continue
                _bytesDown.addAndGet(read.toLong())
                emit(buffer.copyOf(read))
            }
        } finally {
            disposable?.dispose()
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                handle.write(data)
                _bytesUp.addAndGet(data.size.toLong())
                Unit
            } catch (e: IOException) {
                throw SshConnectionException("Запись в последовательный порт не удалась", e)
            }
        }
    }

    override suspend fun resize(size: PtySize) { /* у последовательного порта нет размера окна */ }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        runCatching { handle.close() } // разблокирует read в output
        Unit
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
