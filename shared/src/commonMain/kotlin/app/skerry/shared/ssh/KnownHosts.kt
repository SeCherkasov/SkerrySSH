package app.skerry.shared.ssh

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
 * accepted and remembered; later connections require a matching fingerprint. A mismatch is rejected
 * (key change / possible MITM); the trusted record is left unchanged and the event is recorded in
 * [mismatches] for the known-hosts manager to resolve. A new key type for a known host is treated as
 * a new key.
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
) : HostKeyVerifier {
    override fun verify(offer: HostKeyOffer): Boolean {
        // A certificate is remembered by the key inside it: the certificate blob is re-issued on a
        // schedule, so trusting its fingerprint would report "host key changed" on every rotation.
        // Done here rather than only in [HostCertificateVerifier] so the rule holds even where this
        // verifier is wired up on its own.
        val (host, port, keyType, fingerprint, _) = offer.bareKey()
        val known = store.allOrNull() ?: return false
        val existing = known.firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return when (existing) {
            null -> {
                store.add(KnownHost(host, port, keyType, fingerprint, now()))
                true
            }
            else -> {
                if (existing.fingerprint == fingerprint) {
                    true
                } else {
                    mismatches.record(
                        HostKeyMismatch(host, port, keyType, existing.fingerprint, fingerprint, now()),
                    )
                    false
                }
            }
        }
    }
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
    override fun verify(offer: HostKeyOffer): Boolean {
        // Compared by the key inside a certificate, for the same reason as in [TofuHostKeyVerifier].
        val bare = offer.bareKey()
        val known = store.allOrNull() ?: return false
        val existing = known.firstOrNull {
            it.host == bare.host && it.port == bare.port && it.keyType == bare.keyType
        }
        return if (existing == null) unknownHost == UnknownHost.Accept else existing.fingerprint == bare.fingerprint
    }
}
