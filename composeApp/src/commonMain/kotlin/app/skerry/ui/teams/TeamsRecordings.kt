package app.skerry.ui.teams

import app.skerry.shared.team.TeamRecordingClient
import app.skerry.shared.team.RecordingUpload
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamPeerStore
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.TeamRecordingCrypto
import app.skerry.shared.team.TeamRecordingOutbox
import app.skerry.shared.team.RecordingIdentity
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.RecordingPolicy
import app.skerry.shared.team.RemoteRecording
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.PeerKeys
import app.skerry.shared.team.fetchPinned
import app.skerry.shared.team.cached
import app.skerry.shared.team.openTeamRecording
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.DataKey
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.epochMillis
import app.skerry.ui.sync.TeamLink
import app.skerry.ui.teams.TeamsCoordinator.PreparedRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Vault dependencies shared by recording policy, capture and rotation. */
internal data class RecordingStores(
    val vault: Vault,
    val crypto: VaultCrypto,
    val teamVaults: TeamVaults,
    val keyStore: TeamKeyStore,
    val identityStore: TeamIdentityStore,
    val peerStore: TeamPeerStore,
)

/** Recording policy, queue and playback orchestration for the two client shells. */
class TeamsRecordings internal constructor(
    private val live: () -> TeamLink?,
    stores: RecordingStores,
    private val spaces: TeamSpaces,
    private val newId: () -> String,
    private val opMutex: Mutex,
    private val scope: CoroutineScope,
) {
    private val vault = stores.vault
    private val crypto = stores.crypto
    private val teamVaults = stores.teamVaults
    private val keyStore = stores.keyStore
    private val identityStore = stores.identityStore
    private val peerStore = stores.peerStore
    private val recordingCrypto = TeamRecordingCrypto(crypto)
    private val authorities = RecordingPolicyAuthorities(live, stores, spaces, opMutex)
    private val recordingOutbox by lazy { teamVaults.recordingOutbox() }
    private val _recordingUploadError = MutableStateFlow(false)
    val recordingUploadError: StateFlow<Boolean> = _recordingUploadError
    private val _recordingUploadsPending = MutableStateFlow(false)
    val recordingUploadsPending: StateFlow<Boolean> = _recordingUploadsPending

    /** Resolve signed policy and arm durable capture before any SSH connection is opened. */
    suspend fun prepareHostRecording(
        hostId: String, title: String?, manual: Boolean = false, columns: Int = 80, rows: Int = 24,
    ): PreparedRecording? {
        var armed: TeamRecordingOutbox.Capture? = null
        return try {
            withContext(Dispatchers.Default) {
                opMutex.withLock {
                    prepareLocked(hostId, title, manual, columns, rows).also { armed = it?.capture }
                }
            }
        } catch (e: Exception) {
            armed?.abort()
            throw e
        }
    }

    private suspend fun prepareLocked(
        hostId: String, title: String?, manual: Boolean, columns: Int, rows: Int,
    ): PreparedRecording? {
        check(vault.isUnlocked) { "Unlock the vault before recording preparation" }
        val ref = spaces.holdingHost(hostId) ?: return null
        val link = live() ?: error("Teams recording requires a sync connection")
        checkNotNull(link.recordings) { "Teams recording API unavailable" }
        val key = spaces.key(ref) ?: error("team recording key missing")
        try {
            val epoch = spaces.epoch(ref).toLong()
            val policy = authorities.verifiedPolicy(link, ref, key, epoch)
            val mode = policy?.mode ?: RecordingMode.OFF
            if (manual && mode == RecordingMode.REQUIRED) error("required recording already active")
            val capture = if (mode == RecordingMode.REQUIRED || (manual && mode == RecordingMode.OPTIONAL)) {
                val identity = RecordingIdentity(ref, newId(), hostId, link.session.accountId, epoch)
                val began = kotlin.time.TimeSource.Monotonic.markNow()
                recordingOutbox.arm(identity, key, columns, rows, title, epochMillis() / 1000, now = {
                    began.elapsedNow().inWholeMilliseconds
                }, linkKey = link.linkKey)
            } else null
            return PreparedRecording(mode, capture) {
                _recordingUploadsPending.value = true
                retryRecordingUploads()
            }
        } finally { key.zeroize() }
    }

    suspend fun armOptionalRecording(hostId: String, title: String?, columns: Int, rows: Int): PreparedRecording? =
        prepareHostRecording(hostId, title, manual = true, columns = columns, rows = rows)
            ?.takeIf { it.mode == RecordingMode.OPTIONAL }

    /** The operation lock also serializes queue uploads against key rotation/adoption. */
    fun retryRecordingUploads() {
        scope.launch {
            opMutex.withLock {
                val link = live() ?: return@withLock
                val api = link.recordings ?: return@withLock
                if (!vault.isUnlocked) return@withLock
                try {
                    var failed = recoverQueue(link)
                    val uploads = recordingOutbox.pendingUploads(link.linkKey)
                        .filter { it.identity.actorId == link.session.accountId }
                    _recordingUploadsPending.value = uploads.isNotEmpty()
                    for (upload in uploads) {
                        try { uploadOne(link, api, upload) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                    }
                    _recordingUploadError.value = failed
                    _recordingUploadsPending.value = failed || recordingOutbox.pendingUploads(link.linkKey).isNotEmpty()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    _recordingUploadError.value = true
                    _recordingUploadsPending.value = true
                }
            }
        }
    }

    private suspend fun recoverQueue(link: TeamLink): Boolean {
        var failed = false
        try {
            recordingOutbox.recover(link.linkKey) { identity, epoch ->
                if (identity.actorId == link.session.accountId && spaces.epoch(identity.ref).toLong() == epoch)
                    spaces.key(identity.ref) else null
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        for (teamId in keyStore.list().keys) for (ref in spaces.held(teamId)) {
            try { recordingOutbox.activateSpaceWrap(ref, spaces.epoch(ref).toLong(), link.linkKey) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        return failed
    }

    private suspend fun uploadOne(link: TeamLink, api: TeamRecordingClient, upload: RecordingUpload) {
        check(live()?.linkKey == link.linkKey) { "Teams server changed during upload" }
        val ready = api.recording(link.session, upload.identity.ref, upload.identity.recordingId)
        if (ready != null) {
            check(ready.identity == upload.identity && ready.encryptedManifest.contentEquals(upload.encryptedManifest) &&
                ready.chunkCount == upload.chunks.size) { "completed recording conflict" }
        } else {
            try { api.reserveRecording(link.session, upload) }
            catch (e: SyncException) {
                if (e.status != 410) throw e
                recordingOutbox.markUploaded(upload.identity, link.linkKey)
                return
            }
            for (entry in upload.chunks) {
                val bytes = recordingOutbox.chunk(upload.identity, entry.index, link.linkKey)
                try {
                    api.uploadRecordingChunk(link.session, upload.identity.ref,
                        upload.identity.recordingId, entry.index, bytes)
                } finally { bytes.fill(0) }
            }
            api.completeRecording(link.session, upload.identity.ref, upload.identity.recordingId)
        }
        recordingOutbox.markUploaded(upload.identity, link.linkKey)
    }

    suspend fun recordingPolicy(ref: TeamScopeRef): RecordingPolicy? = withContext(Dispatchers.Default) {
        opMutex.withLock {
            val link = live() ?: error("Teams sync is offline")
            val key = spaces.key(ref) ?: error("team recording key missing")
            try { authorities.verifiedPolicy(link, ref, key, spaces.epoch(ref).toLong()) }
            finally { key.zeroize() }
        }
    }

    suspend fun requestOwnRecordingAuthority(ref: TeamScopeRef): RecordingAuthorityApproval =
        authorities.requestOwnRecordingAuthority(ref)

    suspend fun approveRecordingAuthority(decision: RecordingAuthorityApproval) =
        authorities.approveRecordingAuthority(decision)

    suspend fun setRecordingPolicy(ref: TeamScopeRef, mode: RecordingMode, retentionDays: Int) =
        withContext(Dispatchers.Default) {
            opMutex.withLock {
                val link = live() ?: error("Teams sync is offline")
                val api = link.recordings ?: error("Teams recording API unavailable")
                val key = spaces.key(ref) ?: error("team recording key missing")
                try {
                    val existing = api.recordingPolicy(link.session, ref)
                    val prior = keyStore.recordingPolicy(ref, link.linkKey)
                    val identity = identityStore.load() ?: error("owner identity missing")
                    val authority = authorities.authorityFor(link, link.session.accountId)
                    check(prior == null || prior.authority == authority) { "recording authority approval required" }
                    verifyPriorPolicy(key, identity.signing.publicKey, existing, prior, ref)
                    val revision = maxOf(existing?.revision ?: 0, keyStore.recordingPolicy(ref, link.linkKey)?.revision ?: 0) + 1
                    val sealed = recordingCrypto.sealPolicy(key, identity.signing,
                        RecordingPolicy(ref, mode, revision, spaces.epoch(ref).toLong(), retentionDays))
                    api.putRecordingPolicy(link.session, ref, sealed)
                    keyStore.rememberRecordingPolicy(ref, sealed.cached(mode, authority), link.linkKey)
                } finally { key.zeroize() }
            }
        }

    private fun verifyPriorPolicy(
        key: DataKey, signing: ByteArray, existing: app.skerry.shared.team.SignedRecordingPolicy?,
        prior: app.skerry.shared.team.CachedRecordingPolicy?, ref: TeamScopeRef,
    ) {
        if (existing == null) {
            check(prior == null) { "recording policy missing after prior observation" }
        } else if (existing.keyEpoch == spaces.epoch(ref).toLong()) {
            recordingCrypto.openPolicy(key, signing, existing, ref, prior?.revision ?: 0)
            if (prior != null && prior.revision == existing.revision) {
                check(existing.cached(prior.mode, prior.authority) == prior) { "recording policy equivocation" }
            }
        } else {
            check(prior != null && prior.revision == existing.revision && prior.keyEpoch == existing.keyEpoch &&
                prior.envelope().ciphertext.contentEquals(existing.ciphertext) &&
                prior.envelope().signature.contentEquals(existing.signature)) { "unverified policy before key rotation" }
        }
    }

    suspend fun listRecordings(ref: TeamScopeRef, offset: Long = 0): List<RemoteRecording> = withContext(Dispatchers.Default) {
        val link = live() ?: error("Teams sync is offline")
        (link.recordings ?: error("Teams recording API unavailable")).listRecordings(link.session, ref, offset)
    }

    suspend fun deleteRecording(ref: TeamScopeRef, id: String) = withContext(Dispatchers.Default) {
        val link = live() ?: error("Teams sync is offline")
        (link.recordings ?: error("Teams recording API unavailable")).deleteRecording(link.session, ref, id)
    }

    suspend fun deleteRecordings(ref: TeamScopeRef, ids: List<String>) = withContext(Dispatchers.Default) {
        val link = live() ?: error("Teams sync is offline")
        val api = link.recordings ?: error("Teams recording API unavailable")
        for (batch in ids.distinct().chunked(100)) api.deleteRecordings(link.session, ref, batch)
    }

    suspend fun openRecording(ref: TeamScopeRef, id: String): Asciicast {
        var opened: Asciicast? = null
        return try {
            withContext(Dispatchers.Default) {
                val link = live() ?: error("Teams sync is offline")
                val api = link.recordings ?: error("Teams recording API unavailable")
                val item = api.recording(link.session, ref, id) ?: error("recording unavailable")
                check(item.identity.ref == ref && item.identity.recordingId == id)
                openRecording(link, item).also { opened = it }
            }
        } catch (e: Exception) {
            opened?.source?.close()
            throw e
        }
    }

    private suspend fun openRecording(link: TeamLink, item: RemoteRecording): Asciicast {
        val api = link.recordings ?: error("Teams recording API unavailable")
        val key = spaces.key(item.identity.ref) ?: error("recording space key missing")
        try {
            return openTeamRecording(crypto, key, spaces.epoch(item.identity.ref).toLong(), item) { index ->
                check(live()?.linkKey == link.linkKey && live()?.session?.accountId == link.session.accountId) {
                    "Teams server changed during playback"
                }
                api.downloadRecordingChunk(link.session, item.identity.ref, item.identity.recordingId, index)
            }
        } finally { key.zeroize() }
    }

    fun activateSpace(ref: TeamScopeRef, epoch: Int) {
        val link = live() ?: error("Teams sync is offline")
        recordingOutbox.activateSpaceWrap(ref, epoch.toLong(), link.linkKey)
    }

    suspend fun adoptSpaceWrap(ref: TeamScopeRef, old: DataKey, next: DataKey, epoch: Int) {
        val link = live() ?: error("Teams sync is offline")
        val envelope = link.recordings?.recordingPolicy(link.session, ref)
        if (envelope != null) {
            val useNew = envelope.keyEpoch == epoch.toLong()
            authorities.verifiedPolicy(link, ref, if (useNew) next else old,
                if (useNew) epoch.toLong() else spaces.epoch(ref).toLong())
        }
        recordingOutbox.rewrapSpace(ref, old, next, epoch.toLong(), link.linkKey)
    }

    suspend fun afterAdoptSpace(ref: TeamScopeRef, epoch: Int) {
        activateSpace(ref, epoch)
        live()?.let { afterRecordingRotation(it.session, ref, epoch) }
    }

    suspend fun stageRecordingWraps(
        session: SyncSession, ref: TeamScopeRef, oldKey: DataKey, nextKey: DataKey, nextEpoch: Int,
    ) {
        val link = live() ?: error("Teams sync is offline during key rotation")
        check(link.session == session) { "Teams server changed during key rotation" }
        if (link.recordings != null) authorities.verifiedPolicy(link, ref, oldKey, (nextEpoch - 1).toLong())
        recordingOutbox.rewrapSpace(ref, oldKey, nextKey, nextEpoch.toLong(), link.linkKey)
        val api = link.recordings ?: return
        var offset = 0L
        while (true) {
            val page = try {
                api.listRecordings(session, ref, offset)
            } catch (e: SyncException) {
                if (e.kind == SyncException.Kind.NOT_FOUND && offset == 0L) return
                throw e
            }
            for (item in page) stageWrap(link, item, oldKey, nextKey, nextEpoch.toLong())
            if (page.size < 50) break
            offset += page.size
        }
    }

    private suspend fun stageWrap(link: TeamLink, item: RemoteRecording, oldKey: DataKey, nextKey: DataKey, nextEpoch: Long) {
        val epoch = nextEpoch - 1
        val wrapped = when (epoch) {
            item.wrapEpoch -> item.wrappedKey
            item.stagedWrapEpoch -> item.stagedWrappedKey ?: error("missing staged wrap")
            else -> error("recording cannot be rewrapped from current key")
        }
        val dek = recordingCrypto.openKey(oldKey, wrapped, item.identity.copy(keyEpoch = epoch))
            ?: error("recording wrap authentication failed")
        try {
            val resealed = recordingCrypto.wrapKey(nextKey, dek, item.identity.copy(keyEpoch = nextEpoch))
            checkNotNull(link.recordings).stageRecordingWrap(link.session, item.identity.ref,
                item.identity.recordingId, nextEpoch, resealed)
        } finally { dek.zeroize() }
    }

    suspend fun afterRecordingRotation(session: SyncSession, ref: TeamScopeRef, epoch: Int) {
        val link = live() ?: error("Teams sync is offline after key rotation")
        val cached = keyStore.recordingPolicy(ref, link.linkKey) ?: return
        val mode = cached.mode ?: error("recording policy mode unavailable after rotation")
        check(link.session == session)
        val owner = link.client.listTeams(session).firstOrNull { it.id == ref.teamId }?.ownerAccountId
            ?: error("team owner missing")
        if (owner != session.accountId) return // owner's device must re-sign its policy
        val api = link.recordings ?: error("Teams recording API unavailable")
        val key = spaces.key(ref) ?: error("new recording space key missing")
        val identity = identityStore.load() ?: error("owner identity missing")
        val authority = authorities.authorityFor(link, session.accountId)
        check(cached.authority == authority) { "recording authority approval required" }
        try {
            val remote = api.recordingPolicy(session, ref)
            if (remote != null && remote.keyEpoch == epoch.toLong()) {
                val verified = recordingCrypto.openPolicy(key, identity.signing.publicKey, remote, ref, cached.revision)
                keyStore.rememberRecordingPolicy(ref, remote.cached(verified.mode, authority), link.linkKey)
                return
            }
            check(remote == null || (remote.revision == cached.revision &&
                remote.ciphertext.contentEquals(cached.envelope().ciphertext) &&
                remote.signature.contentEquals(cached.envelope().signature))) { "policy changed during rotation" }
            val policy = RecordingPolicy(ref, mode, cached.revision + 1, epoch.toLong(), cached.retentionDays)
            val sealed = recordingCrypto.sealPolicy(key, identity.signing, policy)
            api.putRecordingPolicy(session, ref, sealed)
            keyStore.rememberRecordingPolicy(ref, sealed.cached(mode, authority), link.linkKey)
        } finally { key.zeroize() }
    }

}

