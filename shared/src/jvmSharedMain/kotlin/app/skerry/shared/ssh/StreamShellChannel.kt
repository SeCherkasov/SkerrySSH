package app.skerry.shared.ssh

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * База интерактивных каналов с блокирующим чтением (sshj / Telnet / Serial): общий каркас
 * read-цикла + guard единственного сборщика [output], идемпотентный [close], счётчики трафика и
 * флаг штатного EOF. Наследник реализует [readBlocking]/[closeSource] и при необходимости
 * перекрывает [transform] (протокольный декод) и write/resize.
 *
 * Семантика цикла: IOException из [readBlocking] = штатное завершение (обрыв транспорта или наш
 * [close]); CancellationException от runInterruptible проходит наружу — это отмена сбора, а не
 * конец данных.
 *
 * @param unblockReadOnCancel true для источников, чей блокирующий read НЕ реагирует на
 *   Thread.interrupt (сырой сокет, нативный serial): при завершении Job сборщика источник
 *   закрывается через [closeSource], роняя повисший read как IOException. У sshj read
 *   интерруптибелен — там false.
 * @param eofOnStreamEnd считать ли `read < 0` штатным EOF от сервера ([endedWithEof], например
 *   `exit`); у serial конец потока — отключение устройства, не «сервер закрыл shell».
 */
internal abstract class StreamShellChannel(
    private val unblockReadOnCancel: Boolean,
    private val eofOnStreamEnd: Boolean = true,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Выставляется циклом [output] при достижении EOF (read<0). Обрыв транспорта/наш close()
    // роняют read как IOException и флаг не трогают.
    private val eofReached = AtomicBoolean(false)
    final override val endedWithEof: Boolean get() = eofReached.get()

    // Счётчики трафика канала (для индикатора скорости): пишутся из IO-потоков чтения/записи,
    // читаются из поллера на другой корутине — AtomicLong для потокобезопасной видимости.
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    final override val bytesUp: Long get() = _bytesUp.get()
    final override val bytesDown: Long get() = _bytesDown.get()

    /** Блокирующее чтение очередного куска; крутится на Dispatchers.IO под runInterruptible. */
    protected abstract fun readBlocking(buffer: ByteArray): Int

    /** Закрыть источник (сокет/поток/порт) — разблокирует повисший [readBlocking]. Не бросает. */
    protected abstract fun closeSource()

    /**
     * Прикладные байты из сырого куска (Telnet здесь снимает IAC-неготиацию и шлёт ответы обратно).
     * Пустой результат не эмитится. По умолчанию — байты как есть.
     */
    protected open suspend fun transform(chunk: ByteArray): ByteArray = chunk

    /** Учёт исходящего трафика — вызывать из write-пути наследника. */
    protected fun countBytesUp(n: Int) {
        _bytesUp.addAndGet(n.toLong())
    }

    final override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output поддерживает только одного сборщика"
        }
        val disposable = if (unblockReadOnCancel) {
            currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { closeSource() } }
        } else {
            null
        }
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = try {
                    runInterruptible(Dispatchers.IO) { readBlocking(buffer) }
                } catch (_: IOException) {
                    break
                }
                if (read < 0) {
                    if (eofOnStreamEnd) eofReached.set(true)
                    break
                }
                if (read == 0) continue
                _bytesDown.addAndGet(read.toLong())
                val data = transform(buffer.copyOf(read))
                if (data.isNotEmpty()) emit(data)
            }
        } finally {
            disposable?.dispose()
        }
    }

    /** Идемпотентный teardown: только первый вызов закрывает источник через [closeSource]. */
    final override suspend fun close() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        closeSource()
        Unit
    }

    protected companion object {
        const val BUFFER_SIZE = 8192
    }
}
