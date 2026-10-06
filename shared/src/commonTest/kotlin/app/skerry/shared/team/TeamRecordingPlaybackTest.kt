package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamRecordingPlaybackTest {
    @Test
    fun `playback authenticates every segment and only retains one segment`() = runTest {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val codec = TeamRecordingCrypto(crypto)
        val key = crypto.newDataKey()
        val dek = crypto.newDataKey()
        val id = RecordingIdentity(TeamScopeRef("team"), "recording", "host", "actor", 0)
        val chunks = listOf(
            "{\"version\":2,\"width\":80,\"height\":24}\n[0.5,\"o\",\"first\"]\n",
            "[1.5,\"o\",\"last\"]\n",
        ).mapIndexed { i, text -> codec.sealChunk(dek, id, i, text.encodeToByteArray()) }
        val manifest = RecordingManifest(id.ref, id.recordingId, id.hostId, id.actorId, 0,
            80, 24, "title", 2, chunks.map { it.toByteString().sha256().hex() })
        val remote = RemoteRecording(id, codec.wrapKey(key, dek, id), codec.sealManifest(dek, manifest),
            chunks.size, 2, 0, 100, 0)
        var reads = 0
        val cast = openTeamRecording(crypto, key, 0, remote) { reads++; chunks[it].copyOf() }
        assertEquals(2, reads)
        assertEquals(emptyList(), cast.events)
        assertEquals(2, cast.eventCount)
        assertEquals("first", cast.event(0).data)
        assertEquals("last", cast.event(1).data)
        cast.source?.close()
        assertFailsWith<IllegalStateException> { cast.event(0) }
        key.zeroize(); dek.zeroize()
    }

    @Test
    fun `tampered later chunk prevents opening the recording`() = runTest {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val codec = TeamRecordingCrypto(crypto)
        val key = crypto.newDataKey(); val dek = crypto.newDataKey()
        val id = RecordingIdentity(TeamScopeRef("team"), "rec", "host", "actor", 0)
        val clear = "{\"version\":2,\"width\":80,\"height\":24}\n[1,\"o\",\"data\"]\n".encodeToByteArray()
        val chunk = codec.sealChunk(dek, id, 0, clear)
        val manifest = RecordingManifest(id.ref, "rec", "host", "actor", 0, 80, 24, null, 1,
            listOf(chunk.toByteString().sha256().hex()))
        val remote = RemoteRecording(id, codec.wrapKey(key, dek, id), codec.sealManifest(dek, manifest), 1, 1, 0, 1, 0)
        assertFailsWith<IllegalStateException> {
            openTeamRecording(crypto, key, 0, remote) { chunk.copyOf().also { it[it.lastIndex] = 0 } }
        }
        key.zeroize(); dek.zeroize()
    }
}
