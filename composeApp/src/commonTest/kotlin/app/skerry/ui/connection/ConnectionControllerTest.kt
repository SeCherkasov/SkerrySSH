package app.skerry.ui.connection

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")

    private fun TestScope.controllerWith(
        transport: SshTransport,
        maxReconnectAttempts: Int = 0,
        // По умолчанию без backoff (детерминизм). Тест отмены реконнекта задаёт ненулевую задержку,
        // чтобы попытка реально «зависла» на delay и её можно было отменить (delay(0) не приостанавливает).
        reconnectDelayMillis: (Int) -> Long = { 0L },
    ): Pair<ConnectionController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = ConnectionController(
            transport = transport,
            scope = scope,
            newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectDelayMillis = reconnectDelayMillis,
        )
        return controller to scope
    }

    @Test
    fun `starts in Form state`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connect transitions to Connected and streams shell output`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        channel.emit("hi".encodeToByteArray())
        assertEquals("hi", state.terminal.output)
        scope.cancel()
    }

    @Test
    fun `connect invokes onConnected once with the live terminal`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        var calls = 0
        var received: Any? = null

        controller.connect(target, SshAuth.Password("pw")) { t -> calls++; received = t }

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        assertEquals(1, calls)
        assertSame(state.terminal, received)
        scope.cancel()
    }

    @Test
    fun `auto-reconnect does not re-invoke onConnected`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        var calls = 0

        controller.connect(target, SshAuth.Password("pw")) { calls++ }
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls)

        ch1.close() // обрыв → авто-реконнект восстанавливает сессию
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls) // команда «Run on host» не повторяется при реконнекте
        scope.cancel()
    }

    @Test
    fun `connect failure transitions to Error with message`() = runTest {
        val transport = FakeSshTransport(error = SshAuthenticationException("нет доступа"))
        val (controller, scope) = controllerWith(transport)

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals("нет доступа", state.message)
        scope.cancel()
    }

    @Test
    fun `connect shows Connecting while in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel()), gate = gate))

        controller.connect(target, SshAuth.Password("pw"))
        assertEquals(ConnectionUiState.Connecting, controller.uiState)

        gate.complete(Unit)
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        scope.cancel()
    }

    @Test
    fun `disconnect returns to Form and disconnects connection`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        assertTrue(conn.disconnected)
        scope.cancel()
    }

    @Test
    fun `losing the shell transitions Connected to Disconnected keeping the frozen terminal`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // Сервер закрыл канал / обрыв транспорта (не наш disconnect): EOF завершает сбор вывода.
        channel.close()

        val lost = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(lost)
        // Экран замораживается на момент потери — это тот же TerminalScreenState, что был у Connected.
        assertSame(connected.terminal, lost.terminal)
        scope.cancel()
    }

    @Test
    fun `clean shell exit closes the session without auto-reconnect`() = runTest {
        val channel = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(channel))))
        // Даже с разрешённым реконнектом штатный exit его НЕ запускает.
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        channel.exit() // пользователь сам завершил shell (`exit`): EOF, не обрыв
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertTrue(st.cleanExit)
        assertFalse(st.reconnecting)
        assertSame(connected.terminal, st.terminal) // экран застыл на финальном выводе (logout)
        assertEquals(1, transport.connectCalls) // ни одной попытки реконнекта
        scope.cancel()
    }

    @Test
    fun `our disconnect goes to Form and never flips to Disconnected on channel close`() = runTest {
        val channel = FakeShellChannel()
        val conn = FakeSshConnection(channel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect() // отменяет session-scope раньше, чем дождались бы Closed
        channel.close()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `auto-reconnect restores Connected after the shell drops, reusing target and auth`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        val auth = SshAuth.Password("pw")
        controller.connect(target, auth)
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // обрыв со стороны сервера
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(2, transport.connectCalls) // первичный + один успешный реконнект
        assertEquals(target, transport.targets[1]) // тот же хост
        assertEquals(auth, transport.auths[1]) // та же учётка
        scope.cancel()
    }

    @Test
    fun `auto-reconnect gives up after the attempt limit and stays Disconnected`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException("down")),
                Result.failure(IllegalStateException("down")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 2)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // попытки исчерпаны
        assertSame(connected.terminal, st.terminal) // экран остался застывшим
        assertEquals(3, transport.connectCalls) // 1 первичный + 2 неуспешные попытки
        scope.cancel()
    }

    @Test
    fun `non-SSH drop does not auto-reconnect`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            // Вторая попытка НЕ должна состояться — реконнекта для Telnet/Serial нет.
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        val telnetTarget = SshTarget(host = "h", port = 23, username = "", connectionType = ConnectionType.TELNET)
        controller.connect(telnetTarget, SshAuth.Password(""))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        ch1.close() // обрыв со стороны сервера
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // для Telnet/Serial авто-реконнекта нет
        assertSame(connected.terminal, st.terminal) // экран остался застывшим
        assertEquals(1, transport.connectCalls) // только первичный, попыток реконнекта не было
        scope.cancel()
    }

    @Test
    fun `disconnect during reconnect cancels further attempts and returns to Form`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 5, reconnectDelayMillis = { 1_000L })
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // запускает реконнект (висит на backoff-delay до advance)
        controller.disconnect() // отменяем его прежде, чем дойдёт до второй попытки
        advanceUntilIdle()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        assertEquals(1, transport.connectCalls) // вторая попытка не состоялась
        scope.cancel()
    }

    @Test
    fun `clearReconnectCredentials stops auto-reconnect after a drop without killing the live session`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 5)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        controller.clearReconnectCredentials() // lock vault: запрет реконнекта, сессия ещё жива
        assertIs<ConnectionUiState.Connected>(controller.uiState) // сокет не тронут

        ch1.close() // обрыв уже на запертом vault
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // без сохранённой учётки реконнект не запускается
        assertEquals(1, transport.connectCalls) // повторной аутентификации не было
        scope.cancel()
    }

    @Test
    fun `connect is a no-op while already connected`() = runTest {
        val firstChannel = FakeShellChannel()
        val conn = CountingSshConnection(firstChannel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // Повторный connect из Connected не должен открывать второй shell/сессию.
        controller.connect(target, SshAuth.Password("pw"))

        assertEquals(connected, controller.uiState)
        assertEquals(1, conn.openShellCalls)
        scope.cancel()
    }

    @Test
    fun `openSftp opens a channel on the live connection`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        val opened = controller.openSftp()

        assertSame(sftp, opened)
        scope.cancel()
    }

    @Test
    fun `openSftp without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openSftp() }
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator caches one coordinator and opens a single channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Кэш на соединение: двухпанельный SFTP переживает переключение view, поэтому повторный
        // вызов отдаёт тот же координатор и не открывает второй канал.
        val first = controller.openTransferCoordinator(FakeFileBrowser(), "host")
        val second = controller.openTransferCoordinator(FakeFileBrowser(), "host")

        assertSame(first, second)
        assertEquals(1, conn.openSftpCalls)
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openTransferCoordinator(FakeFileBrowser(), "host") }
        scope.cancel()
    }

    @Test
    fun `disconnect closes the opened sftp channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        controller.openTransferCoordinator(FakeFileBrowser(), "host")
        assertTrue(!sftp.closed)

        controller.disconnect()

        assertTrue(sftp.closed)
        scope.cancel()
    }

    @Test
    fun `openPortForwards returns the same controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Кэш на соединение: туннели должны пережить переключение вкладок UI, поэтому каждый
        // openPortForwards возвращает один и тот же контроллер, а не новый.
        val first = controller.openPortForwards()
        val second = controller.openPortForwards()

        assertSame(first, second)
        scope.cancel()
    }

    @Test
    fun `openPortForwards without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openPortForwards() }
        scope.cancel()
    }

    @Test
    fun `openMetrics caches one controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Кэш на соединение: info-панель переживает переключение вкладок, повторный вызов отдаёт
        // тот же контроллер (и тот же polling job), а не поднимает второй.
        val first = controller.openMetrics()
        val second = controller.openMetrics()

        assertSame(first, second)
        controller.disconnect()
        scope.cancel()
    }

    @Test
    fun `openMetrics without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openMetrics() }
        scope.cancel()
    }

}

/**
 * Транспорт, отдающий заранее заданную последовательность исходов (успех/ошибка) — по одному на
 * каждый [connect]. Считает вызовы и запоминает цели/учётки для проверки реконнекта.
 */
private class ScriptedTransport(private val outcomes: List<Result<SshConnection>>) : SshTransport {
    var connectCalls = 0
        private set
    val targets = mutableListOf<SshTarget>()
    val auths = mutableListOf<SshAuth>()

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        val index = connectCalls++
        targets += target
        auths += auth
        return outcomes[index].getOrThrow()
    }
}

/** Фейк-транспорт: опциональная задержка через gate, либо успех/ошибка. */
private class FakeSshTransport(
    private val connection: SshConnection? = null,
    private val error: Throwable? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        gate?.await()
        error?.let { throw it }
        return connection!!
    }
}

private class FakeSshConnection(
    private val channel: ShellChannel,
    private val sftp: SftpClient? = null,
) : SshConnection {
    var disconnected = false
        private set
    var openSftpCalls = 0
        private set

    override val isConnected: Boolean get() = !disconnected
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = channel
    override suspend fun openSftp(): SftpClient {
        openSftpCalls++
        return sftp ?: throw UnsupportedOperationException()
    }
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}

/** Заглушка SFTP-клиента: важна идентичность объекта и факт закрытия канала. */
private class RecordingSftpClient : SftpClient {
    var closed = false
        private set

    override suspend fun list(path: String): List<SftpEntry> = emptyList()
    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String = "/"
    override suspend fun read(path: String): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() {
        closed = true
    }
}

/** Заглушка локального файлового браузера для левой панели координатора: важна лишь идентичность. */
private class FakeFileBrowser : FileBrowser {
    override val label: String = "local"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
}

/** Считает вызовы openShell — для проверки, что повторный connect не открывает второй shell. */
private class CountingSshConnection(private val channel: ShellChannel) : SshConnection {
    var openShellCalls = 0
        private set

    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        openShellCalls++
        return channel
    }

    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()

    override suspend fun disconnect() {}
}

private class FakeShellChannel : ShellChannel {
    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    private var eof = false
    override val isOpen: Boolean = true
    override val endedWithEof: Boolean get() = eof
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    /** Штатный выход shell (`exit`): EOF канала — endedWithEof=true. */
    fun exit() {
        eof = true
        emissions.close()
    }

    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    /** Обрыв со стороны сервера / транспорта: канал завершается БЕЗ EOF (кандидат на реконнект). */
    override suspend fun close() {
        emissions.close()
    }
}
