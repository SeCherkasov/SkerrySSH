package app.skerry.shared.ssh

import app.skerry.shared.trust.HostTrustDecider
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import kotlinx.serialization.Serializable

/**
 * A known host key record. Key identity is the triple (host, port, keyType): one host can present
 * keys of different types. [fingerprint] uses OpenSSH format (`SHA256:` + unpadded base64), same as
 * [HostKeyVerifier].
 *
 * [firstSeen] is the timestamp of first trust (ISO-8601, from the injected clock in
 * [TofuHostKeyVerifier]); empty for records imported from an older dateless format.
 *
 * `@Serializable` — the record syncs into the vault ([app.skerry.shared.ssh.VaultKnownHostsStore]):
 * host key trust travels to other devices.
 */
@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val firstSeen: String = "",
)

/** Persistent store of known host keys. Platform implementation is file-backed. */
interface KnownHostsStore {
    fun all(): List<KnownHost>

    /**
     * [all], or `null` when the backing storage is currently unreadable (vault locked, including an
     * auto-lock firing mid-read) — as opposed to readable-but-empty. Host key verifiers use this and
     * **fail closed** on `null`: an unreadable store must not look like "host never seen", or a
     * changed key (the MITM signal) would be TOFU-accepted. Stores whose reads can't fail keep the
     * default.
     */
    fun allOrNull(): List<KnownHost>? = all()

    /** Add a new record. Called only for a previously unknown (host, port, keyType) triple. */
    fun add(host: KnownHost)

    /**
     * Atomically replace the trusted key of the same identity (host, port, keyType) with [host]
     * (new fingerprint/timestamp) — accepts a changed key without a window where the record is
     * absent and [TofuHostKeyVerifier] could TOFU an arbitrary key.
     */
    fun replace(host: KnownHost)

    /** Forget a trusted key by identity (host, port, keyType). No-op if absent. */
    fun remove(host: String, port: Int, keyType: String)
}

/**
 * A recorded host key change event: on connect, [offeredFingerprint] was presented but differs from
 * the trusted [recordedFingerprint] for (host, port, keyType). Persisted so the known-hosts manager
 * can warn and let the user accept/reject the new key after [TofuHostKeyVerifier] rejected the
 * connection.
 */
data class HostKeyMismatch(
    val host: String,
    val port: Int,
    val keyType: String,
    val recordedFingerprint: String,
    val offeredFingerprint: String,
    val observedAt: String = "",
)

/** Persistent store of unresolved key change events. Platform implementation is file-backed. */
interface HostKeyMismatchStore {
    fun all(): List<HostKeyMismatch>

    /**
     * Record a key change. At most one record per (host, port, keyType) triple — a repeat event
     * overwrites the previous one (the latest offered key wins).
     */
    fun record(mismatch: HostKeyMismatch)

    /** Clear the event for an identity (host, port, keyType), after accept/reject. No-op if absent. */
    fun clear(host: String, port: Int, keyType: String)
}

/** No-op mismatch store: TOFU without logging (tests, minimal graphs). */
object NoopHostKeyMismatchStore : HostKeyMismatchStore {
    override fun all(): List<HostKeyMismatch> = emptyList()
    override fun record(mismatch: HostKeyMismatch) {}
    override fun clear(host: String, port: Int, keyType: String) {}
}

/**
 * Trust-on-first-use over [KnownHostsStore]: the first key for a (host, port, keyType) triple is
 * put to [trust] and, if accepted, remembered; later connections require a matching fingerprint. A
 * mismatch is put to [trust] as well — accepting replaces the trusted record, refusing leaves it
 * alone and records the event in [mismatches] for the known-hosts manager to resolve. A new key type
 * for a known host is treated as a new key.
 *
 * [trust] defaults to [HostTrustDecider.SilentTofu], the behaviour of every release before the trust
 * dialog: a first key accepted without asking, a changed one refused.
 *
 * An unreadable store ([KnownHostsStore.allOrNull] == `null`, e.g. the vault auto-locked during the
 * handshake) rejects the key — fail closed. Trust decisions need the trusted set; without it a
 * changed key is indistinguishable from a first contact.
 *
 * [now] stamps [KnownHost.firstSeen]/[HostKeyMismatch.observedAt] (ISO-8601); defaults to empty for
 * tests and graphs without a clock.
 */
class TofuHostKeyVerifier(
    private val store: KnownHostsStore,
    private val mismatches: HostKeyMismatchStore = NoopHostKeyMismatchStore,
    private val now: () -> String = { "" },
    private val trust: HostTrustDecider = HostTrustDecider.SilentTofu,
) : HostKeyVerifier {
    override fun check(offer: HostKeyOffer): HostKeyRefusal? {
        // A certificate is remembered by the key inside it: the certificate blob is re-issued on a
        // schedule, so trusting its fingerprint would report "host key changed" on every rotation.
        // Done here rather than only in [HostCertificateVerifier] so the rule holds even where this
        // verifier is wired up on its own.
        val (host, port, keyType, fingerprint, _) = offer.bareKey()
        val known = store.allOrNull() ?: return HostKeyRefusal.TrustStoreUnreadable
        val existing = known.firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return when {
            existing == null -> {
                // A host already recorded under another algorithm is not a first contact. The server
                // picks which host-key algorithm the exchange uses, so a peer offering only one this
                // store has no record of would otherwise turn "this key changed" into "never seen".
                val others = known.filter { it.host == host && it.port == port }.map { it.keyType }
                if (!trust.decide(request(host, port, keyType, fingerprint, recorded = null, others = others))) {
                    return HostKeyRefusal.RejectedByUser
                }
                commit(host, port, keyType, fingerprint, shown = null)
            }
            existing.fingerprint == fingerprint -> null
            else -> keyChanged(host, port, keyType, fingerprint, existing.fingerprint)
        }
    }

    /**
     * The trusted key differs from what was offered. The user is shown both fingerprints and decides:
     * accepting replaces the record they were shown (atomically — see [KnownHostsStore.replace], and
     * see [commit] for what happens when it moved while they read) and clears the event the
     * known-hosts manager would otherwise still warn about; refusing leaves the trusted key alone and
     * records the event, which is what happens with no decider wired up at all.
     */
    private fun keyChanged(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
        recorded: String,
    ): HostKeyRefusal? {
        if (trust.decide(request(host, port, keyType, fingerprint, recorded))) {
            val refusal = commit(host, port, keyType, fingerprint, shown = recorded)
            if (refusal != null) return refusal
            mismatches.clear(host, port, keyType)
            return null
        }
        mismatches.record(HostKeyMismatch(host, port, keyType, recorded, fingerprint, now()))
        return HostKeyRefusal.KeyChanged
    }

    /**
     * Writes the answer the user just gave, but only against the record they were shown.
     *
     * The dialog holds the handshake open for as long as a person takes to read a fingerprint, and a
     * second connection to the same host can record a key inside that window. Committing the answer
     * blind would then overwrite what landed — with the question having said "new host key" and no
     * change warning ever shown. So the record is read again at the write: unchanged, the answer
     * applies; already carrying the offered key, someone else wrote it and there is nothing to do;
     * anything else and this connection is refused, and the key it offered goes to the known-hosts
     * manager as the mismatch it now is. Gone entirely — forgotten in the manager, or a sync merge
     * landing a deletion — is refused as well, but as a plain refusal: there is no mismatch to
     * record and nothing for the user to compare, so reporting a key change would send them to a
     * panel holding neither a key nor a warning.
     *
     * The read and the write are still two steps — the store is the only thing that could make them
     * one — but they are microseconds apart again instead of minutes.
     */
    private fun commit(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
        shown: String?,
    ): HostKeyRefusal? {
        val known = store.allOrNull() ?: return HostKeyRefusal.TrustStoreUnreadable
        val landed = known.firstOrNull { it.host == host && it.port == port && it.keyType == keyType }?.fingerprint
        if (landed == fingerprint) return null
        if (landed != shown) {
            // The record the answer was about is gone — forgotten in the manager, or a sync merge
            // landing a deletion. Nothing changed and nothing is trusted, so reporting a key change
            // would send the user to a known-hosts panel with neither a key nor a warning in it.
            if (landed == null) return HostKeyRefusal.RejectedByUser
            mismatches.record(HostKeyMismatch(host, port, keyType, landed, fingerprint, now()))
            return HostKeyRefusal.KeyChanged
        }
        val record = KnownHost(host, port, keyType, fingerprint, now())
        if (shown == null) store.add(record) else store.replace(record)
        return null
    }

    private fun request(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
        recorded: String?,
        others: List<String> = emptyList(),
    ) = HostTrustRequest(
        kind = HostTrustKind.SshHostKey,
        host = host,
        port = port,
        keyType = keyType,
        fingerprint = fingerprint,
        recordedFingerprint = recorded,
        recordedKeyTypes = others.distinct().sorted(),
    )
}

/**
 * What a [ReadOnlyHostKeyVerifier] does with a host it holds no key for.
 *
 * Neither answer establishes trust — that is the whole point of the verifier this belongs to — so the
 * question is only whether *this* connection may proceed on a key nobody has vouched for. The answer
 * turns on whether a person is waiting for the result.
 */
enum class UnknownHost {
    /**
     * Connect, and remember nothing. For a check the user asked for and is reading the answer to: a
     * "test connection" from the form names a host that is usually not saved yet, and refusing it
     * would make the button useless for the case it exists to serve.
     */
    Accept,

    /**
     * Refuse. For a connection that runs with nobody watching — activating a saved tunnel opens a
     * forward with no terminal and no prompt, so it must not be the connection that settles a host's
     * identity. The host has to be reached once by a real session (or covered by a trusted CA) first.
     */
    Refuse,
}

/**
 * Host key verifier that never writes to [store], and so can never establish trust: that is reserved
 * for a real session ([TofuHostKeyVerifier]).
 *
 * A matching stored key is accepted; a mismatch for an already-known host is rejected (MITM
 * protection); a host with no entry is decided by [unknownHost], which every call site states
 * explicitly because the two answers are not interchangeable — see [UnknownHost]. An unreadable store
 * rejects everything — fail closed, the same rule as [TofuHostKeyVerifier], since a locked vault must
 * not read as "host never seen".
 */
class ReadOnlyHostKeyVerifier(
    private val store: KnownHostsStore,
    private val unknownHost: UnknownHost,
) : HostKeyVerifier {
    override fun check(offer: HostKeyOffer): HostKeyRefusal? {
        // Compared by the key inside a certificate, for the same reason as in [TofuHostKeyVerifier].
        val bare = offer.bareKey()
        val known = store.allOrNull() ?: return HostKeyRefusal.TrustStoreUnreadable
        val existing = known.firstOrNull {
            it.host == bare.host && it.port == bare.port && it.keyType == bare.keyType
        }
        return when {
            existing == null -> HostKeyRefusal.NotTrustedYet.takeIf { unknownHost == UnknownHost.Refuse }
            existing.fingerprint == bare.fingerprint -> null
            else -> HostKeyRefusal.KeyChanged
        }
    }
}
