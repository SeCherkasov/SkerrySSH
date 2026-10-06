package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RecordingPolicyCacheTest {
    @Test
    fun `policy floor is isolated by origin and refuses same-revision equivocation`() {
        val store = TeamKeyStore(FakeVault())
        val ref = TeamScopeRef("team", "scope")
        store.put("team", "Team", TeamRole.OWNER, DataKey(ByteArray(32)))
        val policy = CachedRecordingPolicy(7, 0, 30, "cipher", "signature", RecordingMode.REQUIRED)
        store.rememberRecordingPolicy(ref, policy, "server-a")
        assertEquals(policy, store.recordingPolicy(ref, "server-a"))
        assertNull(store.recordingPolicy(ref, "server-b"))
        assertFailsWith<IllegalArgumentException> { store.rememberRecordingPolicy(ref, policy.copy(revision = 6), "server-a") }
        assertFailsWith<IllegalArgumentException> { store.rememberRecordingPolicy(ref, policy.copy(mode = RecordingMode.OFF), "server-a") }
        store.rememberRecordingPolicy(ref, policy.copy(revision = 1), "server-b")
        assertEquals(7L, store.recordingPolicy(ref, "server-a")?.revision)
    }
}
