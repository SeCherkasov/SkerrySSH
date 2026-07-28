package app.skerry.shared.share

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionShareHostTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val shareId = "s-1"
    private val codec = SessionShareCodec(crypto, shareId)

    private fun cryptoTest(block: suspend CoroutineScope.() -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    /** Stands in for the relay socket: what the host sent, and what it is handed back. */
    private class FakeChannel : ShareChannel {
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        val events = Channel<ShareEvent>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(frame: ByteArray) {
            sent.send(frame)
        }

        override suspend fun receive(): ShareEvent? = events.receiveCatching().getOrNull()

        override suspend fun close() {
            closed = true
            events.close()
            sent.close()
        }
    }

    private fun host(
        channel: FakeChannel,
        key: DataKey,
        output: MutableSharedFlow<ByteArray> = MutableSharedFlow(),
        allowInput: () -> Boolean = { false },
        toShell: suspend (ByteArray) -> Unit = {},
        onViewers: (List<String>) -> Unit = {},
        geometry: () -> ShareFrame.Resize = { ShareFrame.Resize(cols = 100, rows = 30) },
        onTyping: (String) -> Unit = {},
        onControlRequest: (String) -> Unit = {},
    ) = SessionShareHost(
        codec = codec,
        teamKey = key,
        channel = channel,
        output = output,
        toShell = toShell,
        geometry = geometry,
        allowInput = allowInput,
        onViewers = onViewers,
        onTyping = onTyping,
        onControlRequest = onControlRequest,
    )

    /** Next frame the host put on the wire, decoded; `null` once the socket is closed. */
    private suspend fun FakeChannel.nextFrame(key: DataKey): ShareFrame? =
        sent.receiveCatching().getOrNull()?.let { codec.open(key, it, ShareDirection.HOST_TO_GUEST) }

    private fun ByteArray.sealedInput(key: DataKey, sender: Long = 1, seq: Long = 1) =
        ShareEvent.Data(codec.seal(key, ShareFrame.Input(this, sender, seq), ShareDirection.GUEST_TO_HOST))

    @Test
    fun `terminal output is sealed and sent to the relay in frame-sized chunks`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val output = MutableSharedFlow<ByteArray>()
        val session = host(channel, key, output)
        val written = ByteArray(SHARE_MAX_CHUNK_BYTES + 10) { 0x61 }

        val job = launch { session.run() }
        output.subscriptionCount.first { it > 0 }
        output.emit(written)

        val first = channel.sent.receive()
        val second = channel.sent.receive()
        assertTrue(first.size <= SHARE_MAX_FRAME_BYTES && second.size <= SHARE_MAX_FRAME_BYTES, "frame over the relay cap")
        val payload = listOf(first, second)
            .mapNotNull { codec.open(key, it, ShareDirection.HOST_TO_GUEST) as? ShareFrame.Output }
            .fold(ByteArray(0)) { acc, frame -> acc + frame.bytes }
        assertContentEquals(written, payload)

        channel.close()
        job.join()
    }

    @Test
    fun `a read-only share does not deliver keystrokes to the shell`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val typed = mutableListOf<ByteArray>()
        val session = host(channel, key, allowInput = { false }, toShell = { typed += it })

        val job = launch { session.run() }
        channel.events.send("no\n".encodeToByteArray().sealedInput(key))
        channel.close()
        job.join()

        assertTrue(typed.isEmpty(), "a viewer typed into a read-only share")
    }

    @Test
    fun `a viewer's keystrokes reach the shell once input is allowed`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val typed = mutableListOf<ByteArray>()
        val session = host(channel, key, allowInput = { true }, toShell = { typed += it })

        val job = launch { session.run() }
        channel.events.send("yes\n".encodeToByteArray().sealedInput(key))
        channel.close()
        job.join()

        assertEquals(1, typed.size)
        assertContentEquals("yes\n".encodeToByteArray(), typed.single())
    }

    @Test
    fun `a keystroke frame the relay replays is typed only once`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val typed = mutableListOf<ByteArray>()
        val session = host(channel, key, allowInput = { true }, toShell = { typed += it })

        val job = launch { session.run() }
        // The relay sees every ciphertext it forwards; handing the same one back re-authenticates
        // under the team key, so only freshness stops it running on the host's machine twice.
        val frame = "reboot\n".encodeToByteArray().sealedInput(key, sender = 5, seq = 1)
        channel.events.send(frame)
        channel.events.send(frame)
        // A viewer that reconnects gets a new sender id and starts over at 1; that must still land.
        channel.events.send("ls\n".encodeToByteArray().sealedInput(key, sender = 6, seq = 1))
        channel.close()
        job.join()

        assertEquals(2, typed.size, "a replayed keystroke frame reached the shell")
        assertContentEquals("reboot\n".encodeToByteArray(), typed.first())
        assertContentEquals("ls\n".encodeToByteArray(), typed.last())
    }

    @Test
    fun `frames the host cannot authenticate are dropped and the session keeps running`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val typed = mutableListOf<ByteArray>()
        val session = host(channel, key, allowInput = { true }, toShell = { typed += it })

        val job = launch { session.run() }
        // Garbage, a frame under a different key, and the host's own output echoed back by a
        // malicious relay — none of them may be typed into the shell.
        channel.events.send(ShareEvent.Data(ByteArray(64) { 0x2A }))
        channel.events.send(
            ShareEvent.Data(
                SessionShareCodec(crypto, shareId)
                    .seal(crypto.newDataKey(), ShareFrame.Input("x".encodeToByteArray(), 1, 1), ShareDirection.GUEST_TO_HOST),
            ),
        )
        channel.events.send(
            ShareEvent.Data(codec.seal(key, ShareFrame.Output("rm -rf /\n".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)),
        )
        channel.events.send("ok\n".encodeToByteArray().sealedInput(key, seq = 9))
        channel.close()
        job.join()

        assertEquals(1, typed.size, "only the authentic guest frame may be typed")
        assertContentEquals("ok\n".encodeToByteArray(), typed.single())
    }

    @Test
    fun `a viewer that named itself is reported by name when it types or asks for control`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val typing = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val session = host(channel, key, allowInput = { true }, onTyping = { typing += it }, onControlRequest = { asked += it })

        val job = launch { session.run() }
        // The name comes from the viewer's own sealed hello, not from the relay: the host must not
        // put a name the server made up next to "is typing" on its shell.
        channel.events.send(ShareEvent.Data(codec.seal(key, ShareFrame.Hello(5, "mate@x.io"), ShareDirection.GUEST_TO_HOST)))
        channel.events.send("ls\n".encodeToByteArray().sealedInput(key, sender = 5, seq = 1))
        channel.events.send(ShareEvent.Data(codec.seal(key, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST)))
        // An unknown socket produces no name at all rather than a wrong one.
        channel.events.send("x".encodeToByteArray().sealedInput(key, sender = 99, seq = 1))
        channel.close()
        job.join()

        assertEquals(listOf("mate@x.io"), typing)
        assertEquals(listOf("mate@x.io"), asked)
    }

    @Test
    fun `answering a control request tells every viewer`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val session = host(channel, key)

        val job = launch { session.run() }
        session.announceControl(granted = true)

        assertEquals(ShareFrame.ControlState(true), channel.nextFrame(key))
        channel.close()
        job.join()
    }

    @Test
    fun `a joining viewer is sent the host's screen geometry`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val counts = mutableListOf<List<String>>()
        val session = host(channel, key, onViewers = { counts += it })

        val job = launch { session.run() }
        channel.events.send(ShareEvent.Viewers(1, listOf("mate@x.io")))

        assertEquals(ShareFrame.Resize(100, 30), channel.nextFrame(key))
        channel.close()
        job.join()
        assertEquals(listOf(listOf("mate@x.io")), counts, "the host is told who joined, not just how many")
    }

    @Test
    fun `a viewer leaving does not re-announce the geometry`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val session = host(channel, key)

        val job = launch { session.run() }
        channel.events.send(ShareEvent.Viewers(2, listOf("a@x.io", "b@x.io")))
        assertEquals(ShareFrame.Resize(100, 30), channel.nextFrame(key))
        channel.events.send(ShareEvent.Viewers(1, listOf("a@x.io")))
        channel.events.close()
        job.join()

        // Only the End frame follows: a viewer count going down announces nothing.
        assertEquals(ShareFrame.End, channel.nextFrame(key))
        assertNull(channel.nextFrame(key))
    }

    @Test
    fun `stopping the share tells the viewers it ended and releases the socket`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val session = host(channel, key)

        val job = launch { session.run() }
        session.stop()
        job.join()

        assertEquals(ShareFrame.End, channel.nextFrame(key))
        assertTrue(channel.closed, "the relay socket is released when sharing stops")
    }

    @Test
    fun `a resize of the host's terminal reaches the viewers`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        var size = ShareFrame.Resize(80, 24)
        val session = host(channel, key, geometry = { size })

        val job = launch { session.run() }
        size = ShareFrame.Resize(132, 43)
        session.announceGeometry()

        assertEquals(ShareFrame.Resize(132, 43), channel.nextFrame(key))
        channel.close()
        job.join()
    }
}
