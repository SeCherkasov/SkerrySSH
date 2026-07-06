package app.skerry.ui.known

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore

/** Status of a trusted key in the known-hosts table. */
enum class KnownHostStatus { Verified, Changed }

/** Known-hosts table row: a trusted key plus status (Changed if it has a pending key change). */
@Immutable
data class KnownHostEntry(
    val host: KnownHost,
    val status: KnownHostStatus,
)

/**
 * Known-hosts manager state over [KnownHostsStore] (trusted keys) and [HostKeyMismatchStore]
 * (pending key-change events). Holds both projections as Compose state and reduces mutations to
 * the stores, reloading [entries]/[mismatches] after each, mirroring
 * [app.skerry.ui.host.HostManagerController].
 *
 * Source of truth is the stores: [app.skerry.shared.ssh.TofuHostKeyVerifier] writes to them from
 * the sshj thread on connect. The controller reloads them on refresh, so a key change noticed
 * during a session shows up on the next view build.
 *
 * Mutations are synchronous (write to file-backed stores directly from UI handlers), like
 * [HostManagerController]: they are rare (accept/reject/forget a key), so the controller holds no
 * coroutine scope.
 *
 * [now] stamps [KnownHost.firstSeen] when accepting a new key (ISO-8601); empty by default.
 */
@Stable
class KnownHostsController(
    private val store: KnownHostsStore,
    private val mismatchStore: HostKeyMismatchStore,
    private val now: () -> String = { "" },
) {
    var entries by mutableStateOf(emptyList<KnownHostEntry>())
        private set
    var mismatches by mutableStateOf(emptyList<HostKeyMismatch>())
        private set

    init {
        refresh()
    }

    /**
     * Accepts a new key: replaces the trusted fingerprint with the offered one (new timestamp)
     * and clears the event. The host becomes Verified again with the current key.
     */
    fun acceptNewKey(mismatch: HostKeyMismatch) {
        // Atomic replace, not remove+add: otherwise the sshj thread could observe a missing entry
        // between the two and re-TOFU an arbitrary offered key as newly trusted.
        store.replace(KnownHost(mismatch.host, mismatch.port, mismatch.keyType, mismatch.offeredFingerprint, now()))
        mismatchStore.clear(mismatch.host, mismatch.port, mismatch.keyType)
        refresh()
    }

    /**
     * Rejects a new key (Reject & block / Dismiss): clears the event, keeping the previous key
     * trusted. The offered key stays untrusted; future connections offering it keep being rejected.
     */
    fun reject(mismatch: HostKeyMismatch) {
        mismatchStore.clear(mismatch.host, mismatch.port, mismatch.keyType)
        refresh()
    }

    /** Forgets a trusted key entirely (and its associated change event, if any). */
    fun forget(entry: KnownHostEntry) {
        val host = entry.host
        store.remove(host.host, host.port, host.keyType)
        mismatchStore.clear(host.host, host.port, host.keyType)
        refresh()
    }

    /**
     * Reloads both projections from the stores. Called when the known-hosts screen opens: the
     * TOFU verifier writes new/changed keys to the same store from the sshj thread during a
     * session, and the controller outlives the screen, so such records would otherwise surface
     * only on app restart.
     */
    fun refresh() {
        val pending = mismatchStore.all()
        mismatches = pending
        entries = store.all().map { host ->
            val changed = pending.any {
                it.host == host.host && it.port == host.port && it.keyType == host.keyType
            }
            KnownHostEntry(host, if (changed) KnownHostStatus.Changed else KnownHostStatus.Verified)
        }
    }
}

/**
 * Short fingerprint form for the table: strips the `SHA256:` prefix, keeps the first 10 and last
 * 4 characters (e.g. `8c3F1a2bQz…pK9R`). Short values are returned as-is.
 */
fun shortFingerprint(fingerprint: String): String {
    val body = fingerprint.removePrefix("SHA256:")
    return if (body.length <= 16) body else body.take(10) + "…" + body.takeLast(4)
}
