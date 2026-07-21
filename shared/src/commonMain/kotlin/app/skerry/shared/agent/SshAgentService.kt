package app.skerry.shared.agent

import kotlinx.coroutines.CancellationException

/**
 * The agent itself: turns one request message into one response message, over whatever key source
 * it was given. Transport-agnostic on purpose — the same instance serves forwarded agent channels
 * (`auth-agent@openssh.com`) and the local socket (`SSH_AUTH_SOCK`), which is exactly what makes
 * "the key never leaves the vault" true for both.
 *
 * Every request is attributed to an [SshAgentOrigin] and reported through [onUse], including the
 * bare listing: a remote that only enumerates the keys leaves a trace too. The report is what the
 * Settings → SSH agent activity list shows; nothing is written to disk (the key set is already in
 * the vault, and an on-disk trail of which key was used where is a liability of its own).
 */
class SshAgentService(
    private val keys: SshAgentKeys,
    /**
     * Asked before every signature, the equivalent of `ssh-agent -c`. Suspends for as long as the
     * user takes to answer — the requesting channel waits, which is what the protocol expects of a
     * slow agent. `false` refuses the request. The default answers yes, i.e. behaves like a plain
     * `ssh-agent`; the policy (and the UI) lives in the app.
     */
    private val confirm: suspend (SshAgentSignPrompt) -> Boolean = { true },
    // Kept last so `SshAgentService(keys) { ... }` still reads as "report uses here".
    private val onUse: (SshAgentUsage) -> Unit = {},
) {

    /**
     * Handle one agent message (without the length prefix) and return the reply to write back.
     * Never throws for peer-controlled input: anything unparseable, oversized or unsupported is a
     * plain `SSH_AGENT_FAILURE`, which is all the protocol can say anyway.
     */
    suspend fun handle(
        request: ByteArray,
        origin: SshAgentOrigin,
        scope: SshAgentScope = SshAgentScope.All,
    ): ByteArray {
        if (request.size > SshAgentCodec.MAX_MESSAGE_BYTES) return refuse(origin)
        val parsed = try {
            SshAgentCodec.parseRequest(request)
        } catch (e: SshAgentProtocolException) {
            return refuse(origin)
        }
        return when (parsed) {
            is SshAgentRequest.ListIdentities -> list(origin, scope)
            is SshAgentRequest.Sign -> sign(parsed, origin, scope)
            // Answered, but NOT reported: OpenSSH sends `session-bind@openssh.com` before every
            // single login, so recording these would stamp a "Refused" on every successful
            // connection and drown the entries that mean something.
            is SshAgentRequest.Unsupported -> SshAgentCodec.failure()
        }
    }

    /** Record something the agent did outside a request (e.g. a server refusing forwarding). */
    fun note(origin: SshAgentOrigin, action: SshAgentAction) = report(origin, action, null)

    private suspend fun list(origin: SshAgentOrigin, scope: SshAgentScope): ByteArray {
        // Same contract as sign(): the keyring reads the vault, and an unexpected failure there
        // must come back as a protocol refusal rather than killing the serving coroutine.
        val identities = try {
            keys.identities(scope)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return refuse(origin)
        }
        report(origin, SshAgentAction.Listed, null)
        return SshAgentCodec.identitiesAnswer(identities)
    }

    private suspend fun sign(request: SshAgentRequest.Sign, origin: SshAgentOrigin, scope: SshAgentScope): ByteArray {
        // Resolve the key before asking anything: a blob we do not hold is refused outright, so a
        // remote cannot raise confirmation prompts by naming keys at random. The listing is served
        // from the keyring's cache, so this costs nothing per request.
        val identity = try {
            keys.identities(scope).firstOrNull { it.keyBlob.contentEquals(request.keyBlob) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return refuse(origin)

        // A broken confirmation channel (no UI to ask, prompt path failed) is not an answer, and
        // "no answer" refuses: it must never fall through to a signature, nor throw at the peer's
        // serving coroutine.
        val allowed = try {
            confirm(SshAgentSignPrompt(origin, identity.comment))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return refuse(origin)
        }
        if (!allowed) {
            report(origin, SshAgentAction.Declined, identity.comment)
            return SshAgentCodec.failure()
        }

        // The keyring is the only place that decides whether a key may be used; an unknown blob
        // (or a key the user has not put in the agent) comes back null and is refused here.
        val signature = try {
            keys.sign(request.keyBlob, request.data, request.flags, scope)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return refuse(origin)
        report(origin, SshAgentAction.Signed, signature.keyComment)
        return SshAgentCodec.signResponse(signature.blob)
    }

    private fun refuse(origin: SshAgentOrigin): ByteArray {
        report(origin, SshAgentAction.Refused, null)
        return SshAgentCodec.failure()
    }

    private fun report(origin: SshAgentOrigin, action: SshAgentAction, keyComment: String?) {
        onUse(SshAgentUsage(origin, action, keyComment))
    }
}

/**
 * Key material the agent may use. Implementations live on the platform side (vault secrets are
 * parsed with JVM crypto); the contract stays here so the service and its tests are pure.
 */
interface SshAgentKeys {
    /** Keys currently offered — empty when the agent is off or the vault is locked. */
    suspend fun identities(scope: SshAgentScope = SshAgentScope.All): List<SshAgentIdentity>

    /**
     * Sign [data] with the key identified by [keyBlob], honouring the `rsa-sha2-*` request
     * [flags]. `null` = we do not hold that key (or may not use it), which the caller turns into a
     * protocol failure.
     */
    suspend fun sign(
        keyBlob: ByteArray,
        data: ByteArray,
        flags: Int,
        scope: SshAgentScope = SshAgentScope.All,
    ): SshAgentSignature?
}

/**
 * Which of the agent's keys a caller may reach.
 *
 * A forwarded session gets the set its host profile allows; the local socket and anything that does
 * not narrow it get [All]. Restricting per host matters because forwarding hands the far side a
 * live agent: without this, every server the user forwards to could ask for a signature by ANY key
 * in the agent, and see the names of all of them.
 *
 * [allowedKeyIds] is `null` for "no restriction" — an EMPTY set would be a host allowed nothing,
 * which is a different (and legitimate) answer.
 */
data class SshAgentScope(val allowedKeyIds: Set<String>? = null) {
    fun allows(keyId: String): Boolean = allowedKeyIds?.contains(keyId) ?: true

    companion object {
        val All = SshAgentScope()
    }
}

/** A produced signature: the ssh-wire blob plus the comment of the key that made it (for the audit). */
class SshAgentSignature(val blob: ByteArray, val keyComment: String)

/** Who asked the agent to do something. */
sealed interface SshAgentOrigin {
    /** A forwarded agent channel opened by the server of a live session to [address]. */
    data class Session(val address: String) : SshAgentOrigin

    /** A local process connected to the agent socket (`SSH_AUTH_SOCK`; desktop only). */
    data object LocalSocket : SshAgentOrigin
}

/** What the agent did, as shown in the activity list. */
enum class SshAgentAction {
    /** The peer enumerated the offered keys. */
    Listed,

    /** The peer got a signature. */
    Signed,

    /** The request was refused (unknown key, unsupported or malformed message). */
    Refused,

    /** The server declined agent forwarding for a session that asked for it. */
    ForwardingDenied,

    /** The user was asked to confirm a signature and said no (or let the prompt time out). */
    Declined,
}

/** One signature waiting for the user's answer — who is asking, and with which key. */
class SshAgentSignPrompt(val origin: SshAgentOrigin, val keyComment: String)

/** One entry for the activity list. */
class SshAgentUsage(
    val origin: SshAgentOrigin,
    val action: SshAgentAction,
    /** Comment of the key involved, when the action names one. */
    val keyComment: String?,
)
