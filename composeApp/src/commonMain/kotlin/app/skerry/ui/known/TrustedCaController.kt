package app.skerry.ui.known

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.CaKeyParser
import app.skerry.shared.ssh.HostPattern
import app.skerry.shared.ssh.TrustedCa
import app.skerry.shared.ssh.TrustedCaStore

/** Outcome of adding a certificate authority; every failure names what the user has to fix. */
sealed interface AddCaResult {
    data class Added(val id: String) : AddCaResult

    /** The pasted text isn't a CA public key (see [CaKeyParser] for what's refused). */
    data object InvalidKey : AddCaResult

    /** No host pattern, neither typed nor carried by a pasted `@cert-authority` line. */
    data object MissingPattern : AddCaResult

    /** A pattern that can't match any host — e.g. negations only. */
    data object InvalidPattern : AddCaResult

    /** This CA already covers this pattern. */
    data object Duplicate : AddCaResult

    /** The entry didn't reach the vault — it locked between the check and the write. */
    data object NotStored : AddCaResult
}

/**
 * Trusted certificate authorities over [TrustedCaStore]: holds the list as Compose state and
 * routes mutations to the store, rereading after each — same shape as [KnownHostsController], with
 * which it shares a screen.
 *
 * Adding is the only non-trivial step: the pasted text is parsed by [parser] (platform-side, it
 * needs crypto), and the host pattern may come either from the form or from a whole `known_hosts`
 * line the user pasted. Ids are injected ([newId]) so tests stay deterministic; [now] stamps
 * [TrustedCa.addedAt].
 */
@Stable
class TrustedCaController(
    private val store: TrustedCaStore,
    private val parser: CaKeyParser,
    private val newId: () -> String,
    private val now: () -> String = { "" },
) {
    var authorities by mutableStateOf(emptyList<TrustedCa>())
        private set

    init {
        refresh()
    }

    /**
     * Trust [keyText] (a public key line, or a whole `@cert-authority` entry) for [hostPattern].
     * An empty [hostPattern] falls back to the pattern inside a pasted line; an empty [label] to
     * the key's trailing comment.
     */
    fun add(keyText: String, hostPattern: String, label: String = ""): AddCaResult {
        val parsed = parser.parse(keyText) ?: return AddCaResult.InvalidKey
        val typed = hostPattern.trim().ifBlank { parsed.hostPattern.orEmpty() }
        if (typed.isBlank()) return AddCaResult.MissingPattern
        // Rules live in HostPattern, so what the form accepts and what the verifier matches can't
        // drift apart: an entry that covers nothing (negations only, an over-long element) would
        // otherwise sit in the list looking like trust.
        if (!HostPattern.coversAnyHost(typed)) return AddCaResult.InvalidPattern
        val pattern = HostPattern.normalize(typed)
        if (store.all().any { it.fingerprint == parsed.fingerprint && it.hostPattern == pattern }) {
            return AddCaResult.Duplicate
        }
        val id = newId()
        store.put(
            TrustedCa(
                id = id,
                hostPattern = pattern,
                keyType = parsed.keyType,
                publicKey = parsed.publicKey,
                fingerprint = parsed.fingerprint,
                label = label.trim().ifBlank { parsed.comment },
                addedAt = now(),
            ),
        )
        refresh()
        // The vault can auto-lock between the checks above and the write, which is a no-op there —
        // reporting success would leave the user believing a CA is trusted when nothing was stored.
        if (authorities.none { it.id == id }) return AddCaResult.NotStored
        return AddCaResult.Added(id)
    }

    fun remove(id: String) {
        store.remove(id)
        refresh()
    }

    /**
     * Reread the list from the store: the screen outlives it, and sync can bring in a CA trusted on
     * another device while it's open.
     */
    fun refresh() {
        authorities = store.all()
    }
}

/**
 * Whether [ca] applies to [host]:[port] — the same rule the verifier uses, exposed so the manager
 * can show which hosts an entry covers.
 */
fun TrustedCa.covers(host: String, port: Int): Boolean = HostPattern.matches(hostPattern, host, port)
