package app.skerry.shared.share

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedSessionViewerTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val codec = SessionShareCodec(crypto, "s-1")

    private fun cryptoTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    private class FakeChannel : ShareChannel {
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        val events = Channel<ShareEvent>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(frame: ByteArray) { sent.send(frame) }
        override suspend fun receive(): ShareEvent? = events.receiveCatching().getOrNull()
        override suspend fun close() {
            closed = true
            events.close()
            sent.close()
        }
    }

    // The viewer's reader waits for a subscriber before it reads the socket (it must not drop the
    // catch-up frames), so it belongs to the background scope: tests that never collect [output]
    // would otherwise hang on a coroutine that is behaving exactly as designed.
    private fun TestScope.viewer(channel: FakeChannel, key: DataKey, accountId: String = "mate@x.io") =
        SharedSessionViewer(codec, key, channel, backgroundScope, accountId)

    private fun hostFrame(key: DataKey, frame: ShareFrame) =
        ShareEvent.Data(codec.seal(key, frame, ShareDirection.HOST_TO_GUEST))

    @Test
    fun `the host's output is decrypted into the viewer's terminal`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)
        val seen = Channel<ByteArray>(Channel.UNLIMITED)

        val collector = launch { viewer.output.collect { seen.send(it) } }
        channel.events.send(hostFrame(key, ShareFrame.Output("hello".encodeToByteArray())))

        assertContentEquals("hello".encodeToByteArray(), seen.receive())
        collector.cancel()
        channel.close()
    }

    @Test
    fun `the viewer follows the host's grid, not its own window`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        val collector = launch { viewer.output.collect { } }
        channel.events.send(hostFrame(key, ShareFrame.Resize(cols = 132, rows = 43)))
        val geometry = viewer.geometry.first { it != null }

        assertEquals(ShareFrame.Resize(132, 43), geometry)
        // A local resize must not travel back: the shell belongs to the host's window.
        viewer.resize(PtySize(80, 24, 0, 0))
        assertTrue(channel.sent.tryReceive().isFailure, "the viewer sent a resize to the host")
        collector.cancel()
        channel.close()
    }

    @Test
    fun `typing is sealed as guest input`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        viewer.send("ls\n".encodeToByteArray())

        val frame = codec.open(key, channel.sent.receive(), ShareDirection.GUEST_TO_HOST)
        assertIs<ShareFrame.Input>(frame)
        assertContentEquals("ls\n".encodeToByteArray(), frame.bytes)
        channel.close()
    }

    @Test
    fun `the host ending the share closes the viewer's session`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        val collector = launch { viewer.output.collect { } }
        channel.events.send(hostFrame(key, ShareFrame.End))

        val state = viewer.state.first { it is TerminalState.Closed }
        assertEquals(TerminalState.Closed(cleanExit = true), state)
        collector.cancel()
        channel.close()
    }

    @Test
    fun `losing the relay socket closes the viewer's session as a drop`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        val collector = launch { viewer.output.collect { } }
        channel.close()

        assertEquals(TerminalState.Closed(cleanExit = false), viewer.state.first { it is TerminalState.Closed })
        collector.cancel()
    }

    @Test
    fun `frames that do not authenticate are dropped, the stream continues`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)
        val seen = Channel<ByteArray>(Channel.UNLIMITED)

        val collector = launch { viewer.output.collect { seen.send(it) } }
        channel.events.send(ShareEvent.Data(ByteArray(64) { 0x2A }))
        channel.events.send(
            ShareEvent.Data(
                codec.seal(crypto.newDataKey(), ShareFrame.Output("spoof".encodeToByteArray()), ShareDirection.HOST_TO_GUEST),
            ),
        )
        channel.events.send(hostFrame(key, ShareFrame.Output("real".encodeToByteArray())))

        assertContentEquals("real".encodeToByteArray(), seen.receive())
        collector.cancel()
        channel.close()
    }

    @Test
    fun `the viewer names itself and can ask for control`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        viewer.announce()
        viewer.requestControl()

        val hello = codec.open(key, channel.sent.receive(), ShareDirection.GUEST_TO_HOST)
        val request = codec.open(key, channel.sent.receive(), ShareDirection.GUEST_TO_HOST)
        assertIs<ShareFrame.Hello>(hello)
        assertEquals("mate@x.io", hello.accountId)
        assertIs<ShareFrame.ControlRequest>(request)
        assertEquals(hello.sender, request.sender, "both frames must come from the same socket id")
        channel.close()
    }

    @Test
    fun `the view stays read-only until the host grants control`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        val collector = launch { viewer.output.collect { } }
        assertEquals(false, viewer.controlGranted.value)
        channel.events.send(hostFrame(key, ShareFrame.ControlState(granted = true)))

        assertEquals(true, viewer.controlGranted.first { it })
        collector.cancel()
        channel.close()
    }

    @Test
    fun `leaving closes the relay socket`() = cryptoTest {
        val key = crypto.newDataKey()
        val channel = FakeChannel()
        val viewer = viewer(channel, key)

        viewer.close()

        assertTrue(channel.closed)
        assertEquals(TerminalState.Closed(cleanExit = false), viewer.state.value)
    }
}
