package app.skerry.shared.team

import app.skerry.shared.terminal.StreamingCastRecorder
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.JsonFileStore
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.atomicWriteUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.Path
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Durable ciphertext journal. Output reaches the terminal only after its journal write succeeds. */
class TeamRecordingOutbox(
    private val dir: Path,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit,
    crypto: VaultCrypto,
    private val journalAllocationLimit: Long = MAX_CAST_BUDGET,
    private val maxJournalEntries: Int = MAX_JOURNAL_ENTRIES,
) {
    private val codec = TeamRecordingCrypto(crypto)
    private val keyFactory = crypto
    private val lock = SynchronizedObject()
    private val active = mutableMapOf<Path, Capture>()

    init {
        require(journalAllocationLimit in 1..MAX_CAST_BUDGET)
        require(maxJournalEntries in 1..MAX_JOURNAL_ENTRIES)
    }

    @Serializable
    internal data class Pending(
        val teamId: String,
        val scopeId: String,
        val recordingId: String,
        val hostId: String,
        val actorId: String,
        val keyEpoch: Long,
        val wrappedKey: String,
        val wrapEpoch: Long = keyEpoch,
        val stagedWrappedKey: String? = null,
        val stagedWrapEpoch: Long? = null,
        val encryptedManifest: String? = null,
        val chunks: List<RecordingChunkHash> = emptyList(),
        val durationSec: Long = 0,
        val linkKey: String = "",
        val encryptedDraft: String? = null,
        val journalCount: Int = 0,
        val journalBudget: Long = 0,
        val uploaded: Boolean = false,
    ) {
        fun identity() = RecordingIdentity(TeamScopeRef(teamId, scopeId), recordingId, hostId, actorId, keyEpoch)
        fun matchesLink(origin: String?) = origin == null || linkKey == origin
        fun belongsTo(ref: TeamScopeRef, origin: String?) = !uploaded && identity().ref == ref && matchesLink(origin)
        fun upload(): RecordingUpload? = if (uploaded) null else encryptedManifest?.decodeBase64()?.toByteArray()?.let {
            RecordingUpload(identity(), wrappedKey.decodeBase64()?.toByteArray() ?: return null, it, chunks,
                durationSec, wrapEpoch)
        }
    }

    /** Persist recovery metadata and a wrapped DEK before opening the shell. */
    fun arm(
        identity: RecordingIdentity,
        spaceKey: DataKey,
        columns: Int = 80,
        rows: Int = 24,
        title: String? = null,
        startedAtSeconds: Long,
        now: () -> Long,
        linkKey: String = "",
    ): Capture {
        require(columns in 1..1000 && rows in 1..500)
        val key = keyFactory.newDataKey()
        var transferred = false
        try {
            val draft = codec.sealDraft(key, identity, RecordingDraft(columns, rows, title?.take(256), startedAtSeconds))
            val wrapped = codec.wrapKey(spaceKey, key, identity)
            val pending = Pending(identity.ref.teamId, identity.ref.scopeId, identity.recordingId,
                identity.hostId, identity.actorId, identity.keyEpoch, wrapped.toByteString().base64(),
                linkKey = linkKey, encryptedDraft = draft.toByteString().base64())
            return synchronized(lock) {
                val path = recordingDir(identity, linkKey)
                check(!fileSystem.exists(path)) { "recording id already exists" }
                fileSystem.createDirectories(path)
                store(path).write(pending)
                Capture(path, pending, key, now).also { active[path] = it; transferred = true }
            }
        } finally { if (!transferred) key.zeroize() }
    }

    inner class Capture internal constructor(
        private val path: Path,
        initial: Pending,
        private val key: DataKey,
        private val now: () -> Long,
    ) {
        private var state = initial
        private val began = now()
        private var lastElapsed = 0L
        private var finished = false

        suspend fun record(chunk: ByteArray) {
            synchronized(lock) {
                check(!finished)
                if (chunk.isEmpty()) return@synchronized
                val elapsed = (now() - began).coerceAtLeast(lastElapsed)
                var offset = 0
                while (offset < chunk.size) {
                    val end = minOf(chunk.size, offset + MAX_JOURNAL_FEED)
                    // Include small-file allocation and inode overhead, as well as worst-case
                    // JSON escaping. Tiny feeds must not exhaust disk through millions of files.
                    val budget = maxOf((end - offset).toLong() * 6 + 128, MIN_JOURNAL_ALLOCATION)
                    check(state.journalCount < maxJournalEntries) { "recording journal file limit reached" }
                    check(state.journalBudget + budget <= journalAllocationLimit) { "recording size limit reached" }
                    val plaintext = chunk.copyOfRange(offset, end)
                    val encrypted = try { codec.sealJournal(key, state.identity(), state.journalCount, elapsed, plaintext) }
                    finally { plaintext.fill(0) }
                    try {
                        atomicWriteUtf8(fileSystem, journalPath(path, state.journalCount), encrypted.toByteString().base64(), harden)
                    } finally { encrypted.fill(0) }
                    state = state.copy(journalCount = state.journalCount + 1,
                        journalBudget = state.journalBudget + budget, durationSec = elapsed / 1000)
                    store(path).write(state)
                    offset = end
                }
                lastElapsed = elapsed
            }
        }

        suspend fun finish(durationSec: Long): RecordingUpload {
            synchronized(lock) { check(!finished); finished = true }
            try {
                return compact(path, key, durationSec.coerceAtLeast(0))
            } finally {
                synchronized(lock) { active.remove(path) }
                key.zeroize()
            }
        }

        /** Persistence failed or the owner stopped: retain committed output for recovery. */
        fun abandon() = synchronized(lock) {
            if (!finished) { finished = true; active.remove(path); key.zeroize() }
        }

        /** Only for a shell that never opened. Once output exists it must remain recoverable. */
        fun abort() = synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            active.remove(path)
            try {
                if (state.journalCount == 0 && !fileSystem.exists(journalPath(path, 0))) cleanup(path)
            } finally { key.zeroize() }
        }

        internal fun rewrap(oldKey: DataKey, newKey: DataKey, nextEpoch: Long) {
            state = rewrapState(path, checkNotNull(store(path).read()), oldKey, newKey, nextEpoch)
        }

        internal fun activate(epoch: Long) { state = activateState(path, checkNotNull(store(path).read()), epoch) }
    }

    /** Complete inactive journals; [resolveKey] transfers a key copy which this call wipes. */
    suspend fun recover(linkKey: String, resolveKey: (RecordingIdentity, Long) -> DataKey?) {
        var failed = false
        for (path in paths()) {
            try {
                recoverEntry(path, linkKey, resolveKey)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        check(!failed) { "recording outbox recovery failed" }
    }

    private suspend fun recoverEntry(path: Path, linkKey: String, resolveKey: (RecordingIdentity, Long) -> DataKey?) {
        val state = recoverableState(path) ?: return
        if (!state.matchesLink(linkKey)) return
        if (state.uploaded) { synchronized(lock) { cleanup(path) }; return }
        if (state.encryptedManifest != null) return
        val spaceKey = resolveKey(state.identity(), state.wrapEpoch) ?: error("recording recovery key unavailable")
        val dek = try { openWrap(spaceKey, state, state.wrappedKey, state.wrapEpoch) }
            finally { spaceKey.zeroize() }
        checkNotNull(dek) { "recording recovery key authentication failed" }
        try { compact(path, dek, state.durationSec) } finally { dek.zeroize() }
    }

    private fun recoverableState(path: Path): Pending? = synchronized(lock) {
        if (active.containsKey(path)) return@synchronized null
        if (fileSystem.list(path).isEmpty()) {
            fileSystem.delete(path, mustExist = false)
            return@synchronized null
        }
        store(path).read() ?: error("unreadable recording outbox entry")
    }

    /** Compact a restartable raw journal into independently authenticated, bounded cast chunks. */
    private suspend fun compact(path: Path, key: DataKey, durationSec: Long): RecordingUpload {
        val initial = synchronized(lock) { checkNotNull(store(path).read()) }
        val draftEnvelope = initial.encryptedDraft?.decodeBase64()?.toByteArray()
            ?: error("recording recovery draft missing")
        val draft = try { codec.openDraft(key, initial.identity(), draftEnvelope) } finally { draftEnvelope.fill(0) }
        val chunks = mutableListOf<RecordingChunkHash>()
        var elapsed = 0L
        val stream = StreamingCastRecorder(draft.columns, draft.rows, draft.startedAtSeconds, draft.title,
            now = { elapsed }, writeChunk = { plaintext ->
                val index = chunks.size
                val sealed = try { codec.sealChunk(key, initial.identity(), index, plaintext) }
                    finally { plaintext.fill(0) }
                try {
                    atomicWriteUtf8(fileSystem, chunkPath(path, index), sealed.toByteString().base64(), harden)
                    chunks += RecordingChunkHash(index, sealed.size, sealed.toByteString().sha256().hex())
                } finally { sealed.fill(0) }
            })
        var index = 0
        // A journal write may commit just before entry.json fails. Include its authenticated
        // orphan on recovery; absence inside the committed prefix is corruption, not EOF.
        while (fileSystem.exists(journalPath(path, index))) {
            val sealed = readEnvelope(journalPath(path, index))
            val feed = try { codec.openJournal(key, initial.identity(), index, sealed) } finally { sealed.fill(0) }
            try {
                check(feed.elapsedMillis >= elapsed) { "recording journal time rollback" }
                elapsed = feed.elapsedMillis
                stream.record(feed.bytes)
            } finally { feed.bytes.fill(0) }
            index++
        }
        check(index >= initial.journalCount) { "recording journal incomplete" }
        stream.finish()
        val duration = maxOf(durationSec, elapsed / 1000)
        val manifest = RecordingManifest(initial.identity().ref, initial.recordingId, initial.hostId,
            initial.actorId, initial.keyEpoch, draft.columns, draft.rows, draft.title, duration, chunks.map { it.sha256 })
        val sealed = codec.sealManifest(key, manifest)
        return try {
            synchronized(lock) {
                // Rotation can move the wrap while compaction runs; preserve its latest value.
                val latest = checkNotNull(store(path).read())
                val ready = latest.copy(encryptedManifest = sealed.toByteString().base64(), chunks = chunks,
                    journalCount = index, durationSec = duration)
                store(path).write(ready)
                checkNotNull(ready.upload())
            }
        } finally { sealed.fill(0) }
    }

    /** Stage next-epoch wraps; an adopted remote rotation may skip several epochs. */
    fun rewrapSpace(ref: TeamScopeRef, oldKey: DataKey, newKey: DataKey, nextEpoch: Long, linkKey: String? = null) = synchronized(lock) {
        for (path in paths()) {
            val state = store(path).read() ?: continue
            if (!state.belongsTo(ref, linkKey)) continue
            val live = active[path]
            if (live != null) live.rewrap(oldKey, newKey, nextEpoch)
            else rewrapState(path, state, oldKey, newKey, nextEpoch)
        }
    }

    fun activateSpaceWrap(ref: TeamScopeRef, epoch: Long, linkKey: String? = null) = synchronized(lock) {
        for (path in paths()) {
            val state = store(path).read() ?: continue
            if (!state.belongsTo(ref, linkKey)) continue
            val live = active[path]
            if (live != null) live.activate(epoch) else activateState(path, state, epoch)
        }
    }

    private fun activateState(path: Path, state: Pending, epoch: Long): Pending {
        if (state.wrapEpoch == epoch) return state
        check(state.stagedWrapEpoch == epoch && state.stagedWrappedKey != null) { "outbox wrap for current epoch unavailable" }
        return state.copy(wrapEpoch = epoch, wrappedKey = state.stagedWrappedKey,
            stagedWrappedKey = null, stagedWrapEpoch = null).also { store(path).write(it) }
    }

    private fun rewrapState(path: Path, state: Pending, oldKey: DataKey, newKey: DataKey, nextEpoch: Long): Pending {
        if (state.wrapEpoch == nextEpoch) return state
        require(nextEpoch > state.wrapEpoch) { "outbox wrap epoch mismatch" }
        val dek = openSpaceKey(oldKey, state)
        checkNotNull(dek) { "outbox wrap authentication failed" }
        try {
            val rewrapped = codec.wrapKey(newKey, dek, state.identity().copy(keyEpoch = nextEpoch))
            return state.copy(stagedWrappedKey = rewrapped.toByteString().base64(), stagedWrapEpoch = nextEpoch).also { store(path).write(it) }
        } finally { dek.zeroize() }
    }

    private fun openSpaceKey(key: DataKey, state: Pending): DataKey? {
        openWrap(key, state, state.wrappedKey, state.wrapEpoch)?.let { return it }
        val wrapped = state.stagedWrappedKey ?: return null
        val epoch = state.stagedWrapEpoch ?: return null
        return openWrap(key, state, wrapped, epoch)
    }

    private fun openWrap(key: DataKey, state: Pending, wrapped: String, epoch: Long): DataKey? {
        val envelope = wrapped.decodeBase64()?.toByteArray() ?: error("outbox wrap corrupt")
        return try { codec.openKey(key, envelope, state.identity().copy(keyEpoch = epoch)) }
            finally { envelope.fill(0) }
    }

    fun pendingUploads(linkKey: String? = null): List<RecordingUpload> = synchronized(lock) {
        paths().mapNotNull { path ->
            val state = store(path).read() ?: return@mapNotNull null
            if (linkKey != null && state.linkKey != linkKey) null else try { state.upload() }
            catch (_: IllegalArgumentException) { null }
        }
    }

    fun chunk(identity: RecordingIdentity, index: Int, linkKey: String? = null): ByteArray = synchronized(lock) {
        require(index in 0 until TeamRecordingCrypto.MAX_CHUNKS)
        val path = findPath(identity, linkKey) ?: error("recording outbox entry missing")
        val state = checkNotNull(store(path).read())
        val expected = state.chunks.getOrNull(index) ?: error("recording chunk missing")
        val bytes = readEnvelope(chunkPath(path, index))
        if (bytes.size != expected.length || bytes.toByteString().sha256().hex() != expected.sha256) {
            bytes.fill(0)
            error("recording chunk hash mismatch")
        }
        bytes
    }

    /** The uploaded tombstone commits before deletion, making interrupted cleanup repeatable. */
    fun markUploaded(identity: RecordingIdentity, linkKey: String? = null) = synchronized(lock) {
        val path = findPath(identity, linkKey) ?: return@synchronized
        val state = checkNotNull(store(path).read())
        check(state.uploaded || state.upload() != null) { "recording not finalized" }
        if (!state.uploaded) store(path).write(state.copy(uploaded = true))
        cleanup(path)
    }

    private fun cleanup(path: Path) {
        if (!fileSystem.exists(path)) return
        // entry.json goes last: a partial cleanup still has the uploaded tombstone on restart.
        fileSystem.list(path).filter { it.name != "entry.json" }.forEach { fileSystem.delete(it, mustExist = false) }
        store(path).clear()
        fileSystem.delete(path, mustExist = false)
    }

    private fun findPath(identity: RecordingIdentity, linkKey: String?): Path? {
        val matches = paths().filter { path ->
            store(path).read()?.let { it.identity() == identity && (linkKey == null || it.linkKey == linkKey) } == true
        }
        check(matches.size <= 1) { "recording origin is ambiguous" }
        return matches.singleOrNull()
    }

    private fun paths(): List<Path> = if (!fileSystem.exists(dir)) emptyList()
        else fileSystem.list(dir).filter { fileSystem.metadata(it).isDirectory }

    private fun recordingDir(identity: RecordingIdentity, linkKey: String): Path {
        val fields = listOf(linkKey, identity.ref.teamId, identity.ref.scopeId, identity.recordingId)
        val name = fields.joinToString("") { "${it.length}:$it" }.encodeToByteArray().toByteString().sha256().hex()
        return dir / "rec-$name"
    }

    private fun readEnvelope(path: Path): ByteArray {
        require((fileSystem.metadata(path).size ?: 0) <= TeamRecordingCrypto.MAX_CHUNK_CIPHERTEXT.toLong() * 4 / 3 + 4) {
            "recording envelope exceeds limit"
        }
        return fileSystem.read(path) { readUtf8() }.decodeBase64()?.toByteArray() ?: error("recording envelope corrupt")
    }
    private fun store(path: Path) = JsonFileStore(path / "entry.json", fileSystem, Pending.serializer(), harden)
    private fun chunkPath(path: Path, index: Int) = path / "chunk-$index"
    private fun journalPath(path: Path, index: Int) = path / "journal-$index"

    private companion object {
        const val MAX_JOURNAL_FEED = 256 * 1024
        const val MIN_JOURNAL_ALLOCATION = 8_192L
        const val MAX_JOURNAL_ENTRIES = 32_768
        const val MAX_CAST_BUDGET = 1L * TeamRecordingCrypto.MAX_CHUNKS * TeamRecordingCrypto.MAX_CHUNK_PLAINTEXT - 65_536
    }
}
