package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamPeerStore
import app.skerry.shared.team.cached
import app.skerry.shared.team.accountKeyFingerprint
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.RecordingPolicy
import app.skerry.shared.team.RecordingPolicyException
import app.skerry.shared.team.RecordingUpload
import app.skerry.shared.team.RemoteRecording
import app.skerry.shared.team.SignedRecordingPolicy
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRecordingClient
import app.skerry.shared.team.TeamRecordingCrypto
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeGrantEntry
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopeSummary
import app.skerry.shared.team.TeamSessionKind
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real encrypted account/space files, with only the server transport replaced. */
class TeamsRecordingCoordinatorTest {
    private val crypto = IonspinVaultCrypto()
    private val self = "alice@example.com"
    private val teamId = "team-rec"
    private val ref = TeamScopeRef(teamId)

    private class FakeTeams(
        private val self: String,
        private val teamId: String,
        private val others: List<Pair<String, AccountKeys>>,
    ) : TeamClient {
        var serverEpoch: Long = 0
        var advertisedOwner: String = self
        val publishedKeys = others.toMap().toMutableMap()
        val rekeyCalls = mutableListOf<Long>()
        /** The envelopes of the last committed rekey, by account. */
        var committedEnvelopes: Map<String, ByteArray> = emptyMap()
        /** Whether listTeams hands this account its envelope from the last rekey. */
        var serveOwnEnvelope = false
        /** Runs once the server has committed a rekey, before the call returns. */
        var afterCommit: () -> Unit = {}
        val removed = mutableListOf<String>()
        /** Front of the deque decides each rekey: true = accept, false = throw CONFLICT. Empty = accept. */
        val rekeyOutcomes = ArrayDeque<Boolean>()

        private val store = linkedMapOf<String, Pair<RemoteRecord, Long>>()
        private var seq = 0L

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = advertisedOwner, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 1 + others.size,
                envelope = null, keyEpoch = serverEpoch,
                keyEnvelope = if (serveOwnEnvelope) committedEnvelopes[self] else null,
            ),
        )

        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> =
            listOf(TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0)) +
                others.map { TeamMember(it.first, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0) }

        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? =
            publishedKeys[accountId]

        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            rekeyCalls += newEpoch
            val accept = if (rekeyOutcomes.isEmpty()) true else rekeyOutcomes.removeFirst()
            if (!accept) throw SyncException(SyncException.Kind.CONFLICT, "stale epoch")
            serverEpoch = newEpoch
            committedEnvelopes = envelopes
            afterCommit()
        }

        fun serverRecord(id: String): RemoteRecord? = store[id]?.first

        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) {
            removed += accountId
        }

        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage {
            val page = store.values.filter { it.second > since }.sortedBy { it.second }
            return RecordPage(page.map { it.first }, page.lastOrNull()?.second ?: since)
        }

        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage {
            val result = records.map { rec ->
                val existing = store[rec.id]?.first
                val wins = existing == null || rec.version > existing.version ||
                    (rec.version == existing.version && rec.deviceId > existing.deviceId)
                if (wins) { seq += 1; store[rec.id] = rec to seq; rec } else existing
            }
            return RecordPage(result, seq)
        }

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun reportSessionEvent(
            session: SyncSession,
            teamId: String,
            recordId: String,
            kind: TeamSessionKind,
            durationSec: Long?,
        ) = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> = emptyList()
        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> = emptyList()
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
    }

    private class FakeRecordings : TeamRecordingClient {
        var policy: SignedRecordingPolicy? = null
        var failure = false
        var loseCompleteResponse = false
        var retired = false
        var reserveCalls = 0
        var chunkCalls = 0
        var completeCalls = 0
        var lookupCalls = 0
        var upload: RecordingUpload? = null
        var ready: RemoteRecording? = null
        val chunks = mutableMapOf<Int, ByteArray>()
        override suspend fun recordingPolicy(session: SyncSession, ref: TeamScopeRef) = policy
        override suspend fun putRecordingPolicy(session: SyncSession, ref: TeamScopeRef, policy: SignedRecordingPolicy) {
            this.policy = policy
        }
        override suspend fun reserveRecording(session: SyncSession, upload: RecordingUpload) {
            reserveCalls++
            if (retired) throw SyncException(SyncException.Kind.NOT_FOUND, "recording retired", status = 410)
            if (failure) throw SyncException(SyncException.Kind.NETWORK, "offline")
            this.upload = upload
        }
        override suspend fun uploadRecordingChunk(session: SyncSession, ref: TeamScopeRef,
            recordingId: String, index: Int, ciphertext: ByteArray) {
            chunkCalls++
            chunks[index] = ciphertext.copyOf()
        }
        override suspend fun completeRecording(session: SyncSession, ref: TeamScopeRef, recordingId: String) {
            completeCalls++
            val item = checkNotNull(upload)
            ready = RemoteRecording(item.identity, item.wrappedKey, item.encryptedManifest, item.chunks.size,
                item.durationSec, 1, Long.MAX_VALUE, item.wrapEpoch)
            if (loseCompleteResponse) throw SyncException(SyncException.Kind.NETWORK, "response lost")
        }
        override suspend fun recording(session: SyncSession, ref: TeamScopeRef, recordingId: String): RemoteRecording? {
            lookupCalls++
            return ready
        }
        override suspend fun listRecordings(session: SyncSession, ref: TeamScopeRef, offset: Long) = listOfNotNull(ready)
        override suspend fun downloadRecordingChunk(session: SyncSession, ref: TeamScopeRef, recordingId: String,
            index: Int) = checkNotNull(chunks[index]).copyOf()
        override suspend fun deleteRecording(session: SyncSession, ref: TeamScopeRef, recordingId: String) = error("unused")
        override suspend fun stageRecordingWrap(session: SyncSession, ref: TeamScopeRef, recordingId: String,
            nextEpoch: Long, wrappedKey: ByteArray) = error("unused")
    }

    private inner class Fixture(val root: Path) {
        val vault = FileVault(root / "account.vault", crypto, "dev-a", FileSystem.SYSTEM, now = { NOW })
        val teamVaults = TeamVaults(root / "teams", crypto, "dev-a", FileSystem.SYSTEM, now = { NOW })
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val teams = FakeTeams(self, teamId, emptyList())
        val api = FakeRecordings()
        val session = SyncSession(self, "access", "refresh")
        var link = TeamLink(session, teams, "origin-a", api)
        var nextId: () -> String = { "rec-1" }
        val keyStore = TeamKeyStore(vault)
        val coordinator = TeamsCoordinator({ link }, vault, crypto, teamVaults,
            InMemorySyncStateStore(), newId = { nextId() }, scope = scope)
        val outbox get() = teamVaults.recordingOutbox()
        init {
            vault.create("master".toCharArray())
            val key = crypto.newDataKey()
            try {
                keyStore.put(teamId, "Ops", TeamRole.OWNER, key, epoch = 0)
                teamVaults.open(ref, key)!!.put("host-1", RecordType.HOST, "host".encodeToByteArray())
                val identity = TeamIdentityStore(vault, crypto).ensure()
                api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, identity.signing,
                    RecordingPolicy(ref, RecordingMode.REQUIRED, 1, 0, 30))
            } finally { key.zeroize() }
        }
        fun entryFiles(): List<Path> = FileSystem.SYSTEM.listRecursively(root).filter { it.name == "entry.json" }.toList()
        suspend fun queue(): RecordingUpload {
            val prepared = assertNotNull(coordinator.recordings.prepareHostRecording("host-1", "encrypted title"))
            val capture = assertNotNull(prepared.capture)
            capture.record("secret output\r\n".encodeToByteArray())
            return capture.finish(3)
        }
        suspend fun retryUntil(predicate: () -> Boolean) {
            coordinator.recordings.retryRecordingUploads()
            withTimeout(5_000) { job.children.toList().forEach { it.join() } }
            assertTrue(predicate(), "recording operation did not reach expected state")
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        initializeVaultCrypto()
        val root = Files.createTempDirectory("skerry-recording-coordinator").toString().toPath()
        val f = Fixture(root)
        try { block(f) } finally {
            f.job.cancelAndJoin()
            f.teamVaults.lockAll()
            f.vault.lock()
            FileSystem.SYSTEM.deleteRecursively(root)
        }
    }

    @Test
    fun `server cannot downgrade required policy by substituting an already pinned member as owner`() = runBlocking {
        fixture { f ->
            val accepted = assertNotNull(f.coordinator.recordings.recordingPolicy(ref))
            assertEquals(RecordingMode.REQUIRED, accepted.mode)
            val memberId = "bob@example.com"
            val member = crypto.newSigningKeyPair()
            val sharing = crypto.newSharingKeyPair()
            val keys = AccountKeys(sharing.publicKey, member.publicKey)
            f.teams.publishedKeys[memberId] = keys
            TeamPeerStore(f.vault).confirm(memberId, accountKeyFingerprint(keys.sharing, keys.signing))
            val spaceKey = assertNotNull(f.keyStore.get(teamId)?.dataKey())
            try {
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(spaceKey, member,
                    RecordingPolicy(ref, RecordingMode.OFF, accepted.revision + 1, 0, 30))
                f.teams.advertisedOwner = memberId
                assertFailsWith<RecordingPolicyException> {
                    f.coordinator.recordings.prepareHostRecording("host-1", null)
                }
                assertEquals(RecordingMode.REQUIRED, f.keyStore.recordingPolicy(ref, f.link.linkKey)?.mode)
                assertTrue(f.entryFiles().isEmpty())
            } finally { spaceKey.zeroize() }
        }
    }

    @Test
    fun `new local owner explicitly approves authority and resigns preserved required policy`() = runBlocking {
        fixture { f ->
            val oldOwner = "bob@example.com"
            val signing = crypto.newSigningKeyPair()
            val sharing = crypto.newSharingKeyPair()
            val keys = AccountKeys(sharing.publicKey, signing.publicKey)
            f.teams.publishedKeys[oldOwner] = keys
            TeamPeerStore(f.vault).confirm(oldOwner, accountKeyFingerprint(keys.sharing, keys.signing))
            val key = assertNotNull(f.keyStore.get(teamId)?.dataKey())
            try {
                f.teams.advertisedOwner = oldOwner
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, signing,
                    RecordingPolicy(ref, RecordingMode.REQUIRED, 7, 0, 90))
                val bootstrap = assertFailsWith<RecordingAuthorityRequired> {
                    f.coordinator.recordings.recordingPolicy(ref)
                }
                f.coordinator.recordings.approveRecordingAuthority(bootstrap.decision)
                assertEquals(7, f.coordinator.recordings.recordingPolicy(ref)?.revision?.toInt())
                f.teams.advertisedOwner = self
                val required = assertFailsWith<RecordingAuthorityRequired> {
                    f.coordinator.recordings.recordingPolicy(ref)
                }
                assertEquals(oldOwner, f.keyStore.recordingPolicy(ref, f.link.linkKey)?.authority?.accountId)
                f.coordinator.recordings.approveRecordingAuthority(required.decision)
                val accepted = assertNotNull(f.coordinator.recordings.recordingPolicy(ref))
                assertEquals(RecordingMode.REQUIRED, accepted.mode)
                assertEquals(90, accepted.retentionDays)
                assertEquals(8L, accepted.revision)
                assertEquals(self, f.keyStore.recordingPolicy(ref, f.link.linkKey)?.authority?.accountId)
                assertFailsWith<IllegalStateException> {
                    f.coordinator.recordings.approveRecordingAuthority(required.decision)
                }
            } finally { key.zeroize() }
        }
    }

    @Test
    fun `new local owner without cache explicitly establishes required policy on own device`() = runBlocking {
        fixture { f ->
            val key = assertNotNull(f.keyStore.get(teamId)?.dataKey())
            try {
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, crypto.newSigningKeyPair(),
                    RecordingPolicy(ref, RecordingMode.OFF, 12, 0, 1))
                assertFailsWith<RecordingPolicyException> { f.coordinator.recordings.recordingPolicy(ref) }
                val decision = f.coordinator.recordings.requestOwnRecordingAuthority(ref)
                assertEquals(null, f.keyStore.recordingPolicy(ref, f.link.linkKey))
                f.coordinator.recordings.approveRecordingAuthority(decision)
                val policy = assertNotNull(f.coordinator.recordings.recordingPolicy(ref))
                assertEquals(RecordingMode.REQUIRED, policy.mode)
                assertEquals(30, policy.retentionDays)
                assertEquals(13L, policy.revision)
            } finally { key.zeroize() }
        }
    }

    @Test
    fun `first sight remote pin does not approve recording policy authority`() = runBlocking {
        fixture { f ->
            val peer = "bob@example.com"
            val signing = crypto.newSigningKeyPair()
            val sharing = crypto.newSharingKeyPair()
            f.teams.publishedKeys[peer] = AccountKeys(sharing.publicKey, signing.publicKey)
            f.teams.advertisedOwner = peer
            val key = assertNotNull(f.keyStore.get(teamId)?.dataKey())
            try {
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, signing,
                    RecordingPolicy(ref, RecordingMode.REQUIRED, 1, 0, 30))
                assertFailsWith<RecordingOwnerKeysRequired> { f.coordinator.recordings.recordingPolicy(ref) }
                assertEquals(null, f.keyStore.recordingPolicy(ref, f.link.linkKey))
            } finally { key.zeroize() }
        }
    }

    @Test
    fun `legacy policy cache requires explicit authority approval preserving revision floor`() = runBlocking {
        fixture { f ->
            val legacy = assertNotNull(f.api.policy).cached(RecordingMode.REQUIRED)
            f.keyStore.rememberRecordingPolicy(ref, legacy, f.link.linkKey)
            val required = assertFailsWith<RecordingAuthorityRequired> {
                f.coordinator.recordings.recordingPolicy(ref)
            }
            assertEquals(legacy, f.keyStore.recordingPolicy(ref, f.link.linkKey))
            f.coordinator.recordings.approveRecordingAuthority(required.decision)
            val accepted = assertNotNull(f.keyStore.recordingPolicy(ref, f.link.linkKey))
            assertEquals(legacy.revision, accepted.revision)
            assertEquals(legacy.ciphertext, accepted.ciphertext)
            assertEquals(self, accepted.authority?.accountId)
            assertFailsWith<IllegalArgumentException> {
                f.keyStore.rememberRecordingPolicy(ref,
                    accepted.copy(revision = accepted.revision + 1, authority = null), f.link.linkKey)
            }
        }
    }

    @Test
    fun `locked recording preparation refuses the connection instead of returning an off plan`() = runBlocking {
        fixture { f ->
            f.vault.lock()
            assertFailsWith<IllegalStateException> {
                f.coordinator.recordings.prepareHostRecording("host-1", null)
            }
            assertTrue(f.entryFiles().isEmpty())
        }
    }

    @Test
    fun `forged owner downgrade and policy rollback prevent arming`() = runBlocking {
        fixture { f ->
            val key = assertNotNull(f.keyStore.get(teamId)?.dataKey())
            try {
                val owner = TeamIdentityStore(f.vault, crypto).ensure()
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, owner.signing,
                    RecordingPolicy(ref, RecordingMode.REQUIRED, 2, 0, 30))
                assertEquals(2L, f.coordinator.recordings.recordingPolicy(ref)!!.revision)
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, crypto.newSigningKeyPair(),
                    RecordingPolicy(ref, RecordingMode.OFF, 3, 0, 30))
                assertFailsWith<RecordingPolicyException> { f.coordinator.recordings.prepareHostRecording("host-1", null) }
                f.api.policy = TeamRecordingCrypto(crypto).sealPolicy(key, owner.signing,
                    RecordingPolicy(ref, RecordingMode.OFF, 1, 0, 30))
                assertFailsWith<RecordingPolicyException> { f.coordinator.recordings.prepareHostRecording("host-1", null) }
                assertTrue(f.entryFiles().isEmpty())
            } finally { key.zeroize() }
        }
    }

    @Test
    fun `required capture is durable before caller opens connection and stores first output`() = runBlocking {
        fixture { f ->
            val prepared = assertNotNull(f.coordinator.recordings.prepareHostRecording("host-1", "title"))
            assertEquals(RecordingMode.REQUIRED, prepared.mode)
            assertEquals(1, f.entryFiles().size)
            assertEquals(0, f.api.reserveCalls)
            val capture = assertNotNull(prepared.capture)
            capture.record("first PTY output".encodeToByteArray())
            capture.finish(1)
            f.retryUntil { f.api.completeCalls == 1 }
            val cast = f.coordinator.recordings.openRecording(ref, assertNotNull(f.api.ready).identity.recordingId)
            assertEquals("first PTY output", cast.event(0).data)
            assertTrue(f.entryFiles().isEmpty())
        }
    }

    @Test
    fun `cancellation during arm removes capture for a shell that never opened`() = runBlocking {
        fixture { f ->
            val caller = Job()
            f.nextId = { caller.cancel(); "rec-cancel" }
            assertFailsWith<CancellationException> {
                withContext(caller) { f.coordinator.recordings.prepareHostRecording("host-1", null) }
            }
            assertTrue(f.entryFiles().isEmpty())
            assertEquals(0, f.api.reserveCalls)
        }
    }

    @Test
    fun `failed origin A upload is retained and never sent to origin B`() = runBlocking {
        fixture { f ->
            f.queue()
            f.api.failure = true
            f.retryUntil { f.coordinator.recordings.recordingUploadError.value }
            assertEquals(1, f.outbox.pendingUploads("origin-a").size)
            val other = FakeRecordings().also { it.policy = f.api.policy }
            f.link = TeamLink(f.session, f.teams, "origin-b", other)
            f.retryUntil { !f.coordinator.recordings.recordingUploadsPending.value }
            assertEquals(0, other.reserveCalls)
            assertEquals(0, other.lookupCalls)
            assertEquals(1, f.outbox.pendingUploads("origin-a").size)
            f.link = TeamLink(f.session, f.teams, "origin-a", f.api)
            f.api.failure = false
            f.retryUntil { f.api.completeCalls == 1 }
            assertFalse(f.coordinator.recordings.recordingUploadError.value)
            assertTrue(f.outbox.pendingUploads("origin-a").isEmpty())
        }
    }

    @Test
    fun `lost complete response checks ready manifest and cleans queue without second upload`() = runBlocking {
        fixture { f ->
            f.queue()
            f.api.loseCompleteResponse = true
            f.retryUntil { f.coordinator.recordings.recordingUploadError.value }
            assertEquals(1, f.outbox.pendingUploads("origin-a").size)
            f.retryUntil { !f.coordinator.recordings.recordingUploadsPending.value }
            assertEquals(1, f.api.reserveCalls)
            assertEquals(1, f.api.chunkCalls)
            assertEquals(1, f.api.completeCalls)
            assertTrue(f.outbox.pendingUploads("origin-a").isEmpty())
            assertFalse(f.coordinator.recordings.recordingUploadError.value)
        }
    }

    @Test
    fun `retired ready receipt acknowledges queue after lost complete response without recreating recording`() = runBlocking {
        fixture { f ->
            f.queue()
            f.api.loseCompleteResponse = true
            f.retryUntil { f.coordinator.recordings.recordingUploadError.value }
            assertEquals(1, f.outbox.pendingUploads("origin-a").size)
            f.api.ready = null
            f.api.retired = true
            f.retryUntil { f.outbox.pendingUploads("origin-a").isEmpty() }
            assertEquals(1, f.api.completeCalls)
            assertEquals(1, f.api.chunkCalls)
            assertFalse(f.coordinator.recordings.recordingUploadError.value)
            assertFalse(f.coordinator.recordings.recordingUploadsPending.value)
        }
    }

    @Test
    fun `adopting own rotated key preserves queued cast and re-signs required policy`() = runBlocking {
        fixture { f ->
            f.queue()
            f.api.failure = true
            f.retryUntil { f.coordinator.recordings.recordingUploadError.value }
            val next = crypto.newDataKey()
            try {
                val own = TeamIdentityStore(f.vault, crypto).ensure()
                val envelope = TeamInviteCodec(crypto).seal(own.sharing.publicKey, own.signing,
                    self, self, teamId, next, "Ops", 1)
                f.teams.serverEpoch = 1
                f.teams.serveOwnEnvelope = true
                f.teams.committedEnvelopes = mapOf(self to envelope)
                f.coordinator.refresh()
                assertEquals(1, f.keyStore.get(teamId)!!.epoch)
                val policy = TeamRecordingCrypto(crypto).openPolicy(next, own.signing.publicKey,
                    assertNotNull(f.api.policy), ref, 1)
                assertEquals(RecordingMode.REQUIRED, policy.mode)
                assertEquals(2L, policy.revision)
                assertEquals(1L, policy.keyEpoch)
                val queued = f.outbox.pendingUploads("origin-a").single()
                assertEquals(1L, queued.wrapEpoch)
                assertEquals(0L, queued.identity.keyEpoch)
                f.api.failure = false
                f.retryUntil { f.api.completeCalls == 1 }
                val cast = f.coordinator.recordings.openRecording(ref, assertNotNull(f.api.ready).identity.recordingId)
                assertEquals("secret output\r\n", cast.event(0).data)
                assertTrue(f.entryFiles().isEmpty())
            } finally { next.zeroize() }
        }
    }

    private companion object { const val NOW = "2026-07-13T00:00:00Z" }
}
