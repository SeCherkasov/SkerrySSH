package app.skerry.shared.team

import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.CastEvent
import app.skerry.shared.terminal.CastEventSource
import app.skerry.shared.terminal.parseAsciicast
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString

/** Verify the whole recording before playback; retain ciphertext metadata and one decoded segment. */
suspend fun openTeamRecording(
    crypto: VaultCrypto,
    spaceKey: DataKey,
    epoch: Long,
    item: RemoteRecording,
    download: suspend (Int) -> ByteArray,
): Asciicast {
    val codec = TeamRecordingCrypto(crypto)
    val wrapped = when (epoch) {
        item.wrapEpoch -> item.wrappedKey
        item.stagedWrapEpoch -> checkNotNull(item.stagedWrappedKey)
        else -> error("recording key epoch unavailable")
    }
    val dek = codec.openKey(spaceKey, wrapped, item.identity.copy(keyEpoch = epoch))
        ?: error("recording key authentication failed")
    var transferred = false
    try {
        val manifest = codec.openManifest(dek, item.identity, item.encryptedManifest)
            ?: error("recording manifest authentication failed")
        check(manifest.chunkHashes.size == item.chunkCount && manifest.durationSec == item.durationSec)
        val source = RecordingEvents(codec, dek, item.identity, manifest, download)
        source.verify()
        val cast = Asciicast(manifest.columns, manifest.rows, manifest.title, emptyList(), source = source)
        transferred = true
        return cast
    } finally { if (!transferred) dek.zeroize() }
}

private class RecordingEvents(
    private val codec: TeamRecordingCrypto,
    private val key: DataKey,
    private val identity: RecordingIdentity,
    private val manifest: RecordingManifest,
    private val download: suspend (Int) -> ByteArray,
) : CastEventSource {
    private val mutex = Mutex()
    private val keyLock = SynchronizedObject()
    private var closed = false
    private val ends = mutableListOf<Int>()
    private val previousTimes = mutableListOf<Double>()
    private var cachedIndex = -1
    private var cachedEvents = emptyList<CastEvent>()
    override var duration = 0.0
        private set
    override val size get() = ends.lastOrNull() ?: 0

    suspend fun verify() {
        var total = 0
        for (index in manifest.chunkHashes.indices) {
            previousTimes += duration
            val events = read(index)
            total += events.size
            ends += total
            if (events.isNotEmpty()) duration = events.last().at
        }
        check(duration <= manifest.durationSec + 1.0) { "recording duration mismatch" }
    }

    override suspend fun event(index: Int): CastEvent = mutex.withLock {
        synchronized(keyLock) { check(!closed) { "recording source closed" } }
        require(index in 0 until size)
        val chunk = ends.indexOfFirst { index < it }
        if (cachedIndex != chunk) {
            cachedEvents = read(chunk)
            cachedIndex = chunk
        }
        cachedEvents[index - if (chunk == 0) 0 else ends[chunk - 1]]
    }

    private suspend fun read(index: Int): List<CastEvent> {
        val encrypted = download(index)
        try {
            check(encrypted.size in 40..TeamRecordingCrypto.MAX_CHUNK_CIPHERTEXT)
            check(encrypted.toByteString().sha256().hex() == manifest.chunkHashes[index]) {
                "recording chunk hash mismatch"
            }
            val clear = synchronized(keyLock) {
                check(!closed) { "recording source closed" }
                codec.openChunk(key, identity, index, encrypted) ?: error("recording chunk authentication failed")
            }
            try {
                val text = clear.decodeToString(throwOnInvalidSequence = true)
                check(text.endsWith('\n')) { "incomplete recording segment" }
                val header = "{\"version\":2,\"width\":${manifest.columns},\"height\":${manifest.rows}}\n"
                val cast = parseAsciicast(if (index == 0) text else header + text)
                    ?: error("invalid recording cast")
                check(!cast.truncated && cast.columns == manifest.columns && cast.rows == manifest.rows)
                val previous = previousTimes.getOrElse(index) { 0.0 }
                return cast.events.map { it.copy(at = maxOf(it.at, previous)) }
            } finally { clear.fill(0) }
        } finally { encrypted.fill(0) }
    }

    override fun close() = synchronized(keyLock) {
        closed = true
        key.zeroize()
        cachedEvents = emptyList()
    }
}
