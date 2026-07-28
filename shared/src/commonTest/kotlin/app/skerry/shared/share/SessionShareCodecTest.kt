package app.skerry.shared.share

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionShareCodecTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()

    private fun cryptoTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `output frame round-trips through the relay`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        val blob = codec.seal(key, ShareFrame.Output("hello[0m".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)
        val opened = codec.open(key, blob, ShareDirection.HOST_TO_GUEST)

        assertTrue(opened is ShareFrame.Output, "opened=$opened")
        assertContentEquals("hello[0m".encodeToByteArray(), opened.bytes)
    }

    @Test
    fun `input and resize frames round-trip`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        val input = codec.open(
            key,
            codec.seal(key, ShareFrame.Input("ls\n".encodeToByteArray(), sender = 7, seq = 3), ShareDirection.GUEST_TO_HOST),
            ShareDirection.GUEST_TO_HOST,
        )
        val resize = codec.open(
            key,
            codec.seal(key, ShareFrame.Resize(cols = 120, rows = 40), ShareDirection.HOST_TO_GUEST),
            ShareDirection.HOST_TO_GUEST,
        )
        val end = codec.open(
            key,
            codec.seal(key, ShareFrame.End, ShareDirection.HOST_TO_GUEST),
            ShareDirection.HOST_TO_GUEST,
        )

        assertTrue(input is ShareFrame.Input)
        assertContentEquals("ls\n".encodeToByteArray(), input.bytes)
        assertEquals(7L, input.sender)
        assertEquals(3L, input.seq)
        assertEquals(ShareFrame.Resize(120, 40), resize)
        assertEquals(ShareFrame.End, end)
    }

    @Test
    fun `a guest cannot replay host output back as input`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        // The relay is untrusted: a guest that echoes a host frame back must not be able to have it
        // accepted as keystrokes on the host's shell.
        val hostFrame = codec.seal(key, ShareFrame.Output("rm -rf /\n".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)

        assertNull(codec.open(key, hostFrame, ShareDirection.GUEST_TO_HOST))
    }

    @Test
    fun `a session label cannot be swapped for a captured output frame`() = cryptoTest {
        // The relay hands the label back in the share directory, where every member of the team sees
        // it — including ones who never joined. Sealing it in the frames' own AAD domain would let a
        // captured output frame be served as the name, putting real shell output in front of them.
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")
        val captured = codec.seal(key, ShareFrame.Output("AWS_SECRET=hunter2".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)

        assertNull(crypto.open(key, captured, shareMetaAad("share-1")))
        // And the label itself is not a frame either, so it can't be injected into the stream.
        val label = crypto.seal(key, "root@prod".encodeToByteArray(), shareMetaAad("share-1"))
        assertNull(codec.open(key, label, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `hello, control request and control state round-trip`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        val hello = codec.open(
            key,
            codec.seal(key, ShareFrame.Hello(42, "mate@x.io"), ShareDirection.GUEST_TO_HOST),
            ShareDirection.GUEST_TO_HOST,
        )
        val request = codec.open(
            key,
            codec.seal(key, ShareFrame.ControlRequest(42), ShareDirection.GUEST_TO_HOST),
            ShareDirection.GUEST_TO_HOST,
        )
        val granted = codec.open(
            key,
            codec.seal(key, ShareFrame.ControlState(granted = true), ShareDirection.HOST_TO_GUEST),
            ShareDirection.HOST_TO_GUEST,
        )

        assertTrue(hello is ShareFrame.Hello)
        assertEquals(42L, hello.sender)
        assertEquals("mate@x.io", hello.accountId)
        assertEquals(42L, (request as ShareFrame.ControlRequest).sender)
        assertEquals(ShareFrame.ControlState(true), granted)
    }

    @Test
    fun `a hello without a name and a truncated control frame are rejected`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")
        val aad = shareAad("share-1", ShareDirection.GUEST_TO_HOST)
        val namelessHello = crypto.seal(key, byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0, 1), aad)
        val shortRequest = crypto.seal(key, byteArrayOf(6, 0, 0), aad)

        assertNull(codec.open(key, namelessHello, ShareDirection.GUEST_TO_HOST))
        assertNull(codec.open(key, shortRequest, ShareDirection.GUEST_TO_HOST))
    }

    @Test
    fun `a frame from another share does not open`() = cryptoTest {
        val key = crypto.newDataKey()
        val blob = SessionShareCodec(crypto, "share-1")
            .seal(key, ShareFrame.Output("x".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)

        assertNull(SessionShareCodec(crypto, "share-2").open(key, blob, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `a frame under another key does not open`() = cryptoTest {
        val codec = SessionShareCodec(crypto, "share-1")
        val blob = codec.seal(crypto.newDataKey(), ShareFrame.Output("x".encodeToByteArray()), ShareDirection.HOST_TO_GUEST)

        assertNull(codec.open(crypto.newDataKey(), blob, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `garbage from the relay is rejected without throwing`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        assertNull(codec.open(key, ByteArray(0), ShareDirection.HOST_TO_GUEST))
        assertNull(codec.open(key, ByteArray(8) { 0x41 }, ShareDirection.HOST_TO_GUEST))
        assertNull(codec.open(key, ByteArray(200) { it.toByte() }, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `an empty plaintext and an unknown frame type are rejected`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")
        val aad = shareAad("share-1", ShareDirection.HOST_TO_GUEST)

        // Forward compatibility: a frame type a newer peer introduced is dropped, not misread.
        val unknownType = crypto.seal(key, byteArrayOf(0x7F, 1, 2, 3), aad)
        val empty = crypto.seal(key, ByteArray(0), aad)

        assertNull(codec.open(key, unknownType, ShareDirection.HOST_TO_GUEST))
        assertNull(codec.open(key, empty, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `an input frame without its sender and sequence header is rejected`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")
        val blob = crypto.seal(key, byteArrayOf(2, 1, 2, 3), shareAad("share-1", ShareDirection.GUEST_TO_HOST))

        assertNull(codec.open(key, blob, ShareDirection.GUEST_TO_HOST))
    }

    @Test
    fun `a truncated resize payload is rejected`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")
        val blob = crypto.seal(key, byteArrayOf(3, 0, 80), shareAad("share-1", ShareDirection.HOST_TO_GUEST))

        assertNull(codec.open(key, blob, ShareDirection.HOST_TO_GUEST))
    }

    @Test
    fun `a sealed output chunk stays within the relay frame limit`() = cryptoTest {
        val key = crypto.newDataKey()
        val codec = SessionShareCodec(crypto, "share-1")

        val chunks = chunkShareOutput(ByteArray(100_000) { 0x61 })

        assertTrue(chunks.all { it.size <= SHARE_MAX_CHUNK_BYTES }, "chunk larger than the plaintext cap")
        chunks.forEach { chunk ->
            val blob = codec.seal(key, ShareFrame.Output(chunk), ShareDirection.HOST_TO_GUEST)
            assertTrue(blob.size <= SHARE_MAX_FRAME_BYTES, "sealed frame ${blob.size} > $SHARE_MAX_FRAME_BYTES")
        }
    }

    @Test
    fun `chunking preserves the byte stream and drops nothing`() = cryptoTest {
        val payload = ByteArray(SHARE_MAX_CHUNK_BYTES * 2 + 7) { (it % 251).toByte() }

        val rejoined = chunkShareOutput(payload).fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        assertContentEquals(payload, rejoined)
        assertTrue(chunkShareOutput(ByteArray(0)).isEmpty(), "an empty write produces no frames")
    }
}
