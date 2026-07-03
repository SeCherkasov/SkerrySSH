package app.skerry.shared.ssh

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Интерактивный shell-канал sshj: чтение PTY в output, запись/resize/close поверх [session].
 * Каркас read-цикла/close — в базе [StreamShellChannel]; read sshj-очереди реагирует на
 * Thread.interrupt, поэтому unblockReadOnCancel не нужен.
 */
internal class SshjShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) : StreamShellChannel(unblockReadOnCancel = false) {

    override val isOpen: Boolean
        get() = session.isOpen

    override fun readBlocking(buffer: ByteArray): Int = shell.inputStream.read(buffer)

    override fun closeSource() {
        // Закрываем входной поток первым, чтобы разблокировать read в output;
        // только потом рвём сам канал. Цикл сбора в output читает лишь shell.inputStream
        // и не обращается к session, поэтому session.close() безопасен даже до того,
        // как read разблокировался. runCatching — teardown не должен бросать наружу.
        runCatching { shell.inputStream.close() }
        runCatching { session.close() }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(data)
            shell.outputStream.flush()
            countBytesUp(data.size)
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
}
