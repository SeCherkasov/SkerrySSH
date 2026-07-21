package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.vault.Credential

/**
 * Why a ProxyJump chain could not be assembled. Typed (not a message string) so every surface
 * resolves it through `jumpProblemText` — same pattern as `SyncFailureReason`.
 */
enum class JumpChainProblem {
    /** [Host.jumpHostId] points to a deleted/unknown profile. */
    MISSING_HOST,

    /** The referenced jump host is not an SSH profile (Telnet/Serial can't carry a tunnel). */
    NOT_SSH,

    /**
     * The jump host has no saved keychain secret (ask-at-connect profiles can't be a hop: only the
     * final destination gets a password prompt).
     */
    NO_CREDENTIAL,

    /** Following the references returns to an already-visited host (would dial forever). */
    CYCLE,
}

/** Result of [resolveJumpChain]: a connect-ready chain (`null` = direct) or a typed problem. */
sealed interface JumpChainResolution {
    data class Resolved(val jump: SshJump?) : JumpChainResolution
    data class Unavailable(val problem: JumpChainProblem) : JumpChainResolution
}

/**
 * Resolve [host]'s saved ProxyJump references into an [SshJump] chain: each hop is looked up by id
 * ([findHost]) and its keychain secret by [Host.credentialId] ([findCredential]). Multi-hop chains
 * follow the hop's own [Host.jumpHostId] recursively; [host] itself only contributes the starting
 * reference (its own address/auth are the caller's business). Fails closed: any broken link yields
 * [JumpChainResolution.Unavailable] rather than silently connecting direct.
 */
fun resolveJumpChain(
    host: Host,
    findHost: (String) -> Host?,
    findCredential: (String?) -> Credential?,
): JumpChainResolution = resolveJumpChain(host.jumpHostId, host.id, findHost, findCredential)

/**
 * [resolveJumpChain] from a raw starting reference — for a not-yet-saved profile (the connection
 * test in the New connection form). [originId] is the profile the chain hangs off (`null` for a
 * new one); revisiting it counts as a cycle.
 */
fun resolveJumpChain(
    jumpHostId: String?,
    originId: String?,
    findHost: (String) -> Host?,
    findCredential: (String?) -> Credential?,
): JumpChainResolution {
    var jumpId = jumpHostId ?: return JumpChainResolution.Resolved(null)
    // The chain is walked destination-outward; hops[0] is the hop nearest to the target. The
    // credential is captured with its hop so [findCredential] is queried once per hop (safe even
    // if a future implementation is one-shot or expensive).
    val hops = mutableListOf<Pair<Host, Credential>>()
    val visited = mutableSetOf<String>()
    originId?.let(visited::add)
    while (true) {
        if (!visited.add(jumpId)) return JumpChainResolution.Unavailable(JumpChainProblem.CYCLE)
        val hop = findHost(jumpId) ?: return JumpChainResolution.Unavailable(JumpChainProblem.MISSING_HOST)
        if (hop.connectionType != ConnectionType.SSH) return JumpChainResolution.Unavailable(JumpChainProblem.NOT_SSH)
        val credential = findCredential(hop.credentialId)
            ?: return JumpChainResolution.Unavailable(JumpChainProblem.NO_CREDENTIAL)
        hops += hop to credential
        jumpId = hop.jumpHostId ?: break
    }
    // Assemble innermost-first: the outermost hop (entry point) ends up deepest in the structure.
    var chain: SshJump? = null
    for ((hop, credential) in hops.asReversed()) {
        chain = SshJump(
            host = hop.address,
            port = hop.port,
            username = hop.username,
            auth = credential.toSshAuth(),
            jump = chain,
        )
    }
    return JumpChainResolution.Resolved(chain)
}

/**
 * Display route of [host]'s ProxyJump chain for info panels: hop labels entry-point-first
 * ("bastion" for one hop, "outer → inner" for a chain — the order the connection travels), or
 * `null` when the profile has no jump. A dangling reference renders as "?" (the panel shows facts,
 * it doesn't validate — connect-time resolution reports the problem); a cycle stops at the repeat.
 */
fun jumpRouteLabel(host: Host, findHost: (String) -> Host?): String? {
    var jumpId = host.jumpHostId ?: return null
    val visited = mutableSetOf(host.id)
    val labels = mutableListOf<String>() // destination-outward; reversed below for travel order
    while (true) {
        if (!visited.add(jumpId)) break
        val hop = findHost(jumpId)
        labels += hop?.label ?: "?"
        jumpId = hop?.jumpHostId ?: break
    }
    return labels.asReversed().joinToString(" → ")
}

/**
 * Saved profiles that may be offered as [editingId]'s jump host: SSH-only, not the edited profile
 * itself, and not one whose own chain already routes through it (picking such a host would create
 * a cycle). `editingId == null` (a not-yet-saved profile) can't be referenced by anyone, so every
 * SSH host qualifies. Input order is preserved (the picker shows the sidebar's order).
 */
fun jumpHostCandidates(all: List<Host>, editingId: String?): List<Host> {
    val byId = all.associateBy { it.id }
    return all.filter { candidate ->
        candidate.connectionType == ConnectionType.SSH &&
            candidate.id != editingId &&
            (editingId == null || !chainReaches(candidate, editingId, byId))
    }
}

/** Whether [from]'s jump chain contains [targetId]. Visited-set guards against pre-existing cycles. */
private fun chainReaches(from: Host, targetId: String, byId: Map<String, Host>): Boolean {
    val visited = mutableSetOf<String>()
    var current: Host? = from
    while (current != null) {
        val next = current.jumpHostId ?: return false
        if (next == targetId) return true
        if (!visited.add(next)) return false
        current = byId[next]
    }
    return false
}
