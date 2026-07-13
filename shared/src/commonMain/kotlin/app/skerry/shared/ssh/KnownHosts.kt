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
    override fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean {
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
 * Host key verifier for a one-off "test connection" check: read-only with respect to [store]. A
 * matching trusted key is accepted; a mismatch for an already-known host is rejected (MITM
 * protection); a previously unknown host is accepted but not written to [store] — a probe must not
 * leave traces in known_hosts or establish permanent trust. Permanent trust is only established on a
 * real connection ([TofuHostKeyVerifier]). An unreadable store rejects — fail closed, same rule as
 * [TofuHostKeyVerifier].
 */
class ProbeHostKeyVerifier(
    private val store: KnownHostsStore,
) : HostKeyVerifier {
    override fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean {
        val known = store.allOrNull() ?: return false
        val existing = known.firstOrNull {
            it.host == host && it.port == port && it.keyType == keyType
        }
        return existing == null || existing.fingerprint == fingerprint
    }
}
