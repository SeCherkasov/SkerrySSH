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
    private val onUse: (SshAgentUsage) -> Unit = {},
) {

    /**
     * Handle one agent message (without the length prefix) and return the reply to write back.
     * Never throws for peer-controlled input: anything unparseable, oversized or unsupported is a
     * plain `SSH_AGENT_FAILURE`, which is all the protocol can say anyway.
     */
    suspend fun handle(request: ByteArray, origin: SshAgentOrigin): ByteArray {
        if (request.size > SshAgentCodec.MAX_MESSAGE_BYTES) return refuse(origin)
        val parsed = try {
            SshAgentCodec.parseRequest(request)
        } catch (e: SshAgentProtocolException) {
            return refuse(origin)
        }
        return when (parsed) {
            is SshAgentRequest.ListIdentities -> list(origin)
            is SshAgentRequest.Sign -> sign(parsed, origin)
            // Answered, but NOT reported: OpenSSH sends `session-bind@openssh.com` before every
            // single login, so recording these would stamp a "Refused" on every successful
            // connection and drown the entries that mean something.
            is SshAgentRequest.Unsupported -> SshAgentCodec.failure()
        }
    }

    /** Record something the agent did outside a request (e.g. a server refusing forwarding). */
    fun note(origin: SshAgentOrigin, action: SshAgentAction) = report(origin, action, null)

    private suspend fun list(origin: SshAgentOrigin): ByteArray {
        // Same contract as sign(): the keyring reads the vault, and an unexpected failure there
        // must come back as a protocol refusal rather than killing the serving coroutine.
        val identities = try {
            keys.identities()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return refuse(origin)
        }
        report(origin, SshAgentAction.Listed, null)
        return SshAgentCodec.identitiesAnswer(identities)
    }

    private suspend fun sign(request: SshAgentRequest.Sign, origin: SshAgentOrigin): ByteArray {
        // The keyring is the only place that decides whether a key may be used; an unknown blob
        // (or a key the user has not put in the agent) comes back null and is refused here.
        val signature = try {
            keys.sign(request.keyBlob, request.data, request.flags)
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
    suspend fun identities(): List<SshAgentIdentity>

    /**
     * Sign [data] with the key identified by [keyBlob], honouring the `rsa-sha2-*` request
     * [flags]. `null` = we do not hold that key (or may not use it), which the caller turns into a
     * protocol failure.
     */
    suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int): SshAgentSignature?
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
}

/** One entry for the activity list. */
class SshAgentUsage(
    val origin: SshAgentOrigin,
    val action: SshAgentAction,
    /** Comment of the key involved, when the action names one. */
    val keyComment: String?,
)
