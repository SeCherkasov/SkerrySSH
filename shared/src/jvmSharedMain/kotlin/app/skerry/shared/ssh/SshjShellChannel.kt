package app.skerry.shared.ssh

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/** Интерактивный shell-канал sshj: чтение PTY в [output], запись/resize/close поверх [session]. */
internal class SshjShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    // Выставляется циклом [output] при достижении EOF (read<0 = сервер закрыл shell штатно, напр.
    // `exit`). Обрыв транспорта/наш close() роняют read как IOException и флаг не трогают.
    private val eofReached = AtomicBoolean(false)
    override val endedWithEof: Boolean get() = eofReached.get()

    // Счётчики трафика канала (для индикатора скорости): пишутся из IO-потоков чтения/записи,
    // читаются из поллера на другой корутине — AtomicLong для потокобезопасной видимости.
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    override val bytesUp: Long get() = _bytesUp.get()
    override val bytesDown: Long get() = _bytesDown.get()

    override val isOpen: Boolean
        get() = session.isOpen

    override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output поддерживает только одного сборщика"
        }
        val stream = shell.inputStream
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            // runInterruptible: блокирующий read должен прерываться отменой корутины,
            // иначе IO-поток виснет в read навсегда и держит runBlocking. EOF либо
            // прерывание потока (close() закрывает stream) роняют read как IOException.
            val read = try {
                runInterruptible(Dispatchers.IO) { stream.read(buffer) }
            } catch (_: IOException) {
                break
            }
            if (read < 0) {
                eofReached.set(true) // штатный EOF: сервер закрыл shell (напр. `exit`)
                break
            }
            if (read > 0) {
                _bytesDown.addAndGet(read.toLong())
                emit(buffer.copyOf(read))
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(data)
            shell.outputStream.flush()
            _bytesUp.addAndGet(data.size.toLong())
            Unit
        } catch (e: IOException) {
            throw SshConnectionException("Запись в shell-канал не удалась", e)
        }
    }

    override suspend fun resize(size: PtySize) = withContext(Dispatchers.IO) {
        try {
            shell.changeWindowDimensions(size.cols, size.rows, size.widthPx, size.heightPx)
        } catch (e: IOException) {
            throw SshConnectionException("Не удалось изменить размер PTY", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // Идемпотентно: повторный close() (например, из close-обработчика и из
        // EOF-пути одновременно) не должен повторно дёргать teardown.
        if (!closed.compareAndSet(false, true)) return@withContext
        // Закрываем входной поток первым, чтобы разблокировать read в output;
        // только потом рвём сам канал. Цикл сбора в output читает лишь shell.inputStream
        // и не обращается к session, поэтому session.close() безопасен даже до того,
        // как read разблокировался. runCatching — teardown не должен бросать наружу.
        runCatching { shell.inputStream.close() }
        runCatching { session.close() }
        Unit
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
