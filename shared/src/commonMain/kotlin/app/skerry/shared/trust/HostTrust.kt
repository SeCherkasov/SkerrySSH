package app.skerry.shared.trust

/** Which kind of identity a server presented — the two differ in what a dialog can show about them. */
enum class HostTrustKind {
    /** An SSH host key (`ssh-ed25519`, `rsa-sha2-512`, …). Nothing to show beyond type and fingerprint. */
    SshHostKey,

    /** A TLS certificate offered by an RDP server. Carries [HostTrustRequest.certificate]. */
    RdpCertificate,
}

/**
 * The certificate fields a trust dialog shows. [subject] and [issuer] are text the server authored:
 * run them through `sanitizeServerText` before they reach a screen.
 *
 * [trustedByPlatform] and [hostnameMatches] describe the certificate rather than deciding anything —
 * a Windows host signs its own certificate and names itself after the machine, so both are commonly
 * false on a perfectly ordinary server. They are shown so the user can weigh what they are accepting.
 */
data class HostTrustCertificate(
    val subject: String,
    val issuer: String,
    val notAfterMillis: Long,
    val trustedByPlatform: Boolean,
    val hostnameMatches: Boolean,
)

/**
 * What the user is being asked to accept: the identity [host]:[port] just presented, and — when the
 * host is already known by a different one — what was recorded before.
 *
 * [keyType] is the algorithm as the protocol names it (`ssh-ed25519`, `rsa-sha2-512`), empty for a
 * certificate — what identifies one to a person is [certificate]'s subject and issuer.
 * [fingerprint] is what the store compares, in the same shape it is kept there (`SHA256:` + unpadded
 * base64 for SSH, the hex digest for RDP).
 *
 * [recordedFingerprint] `null` means first contact — nobody has vouched for this host yet. A value
 * means the trusted key changed, which is a server rebuild as often as an attack; the two cannot be
 * told apart from here, which is why it is the user being asked.
 *
 * [recordedKeyTypes] are the algorithms this host is *already* recorded under, and it is only ever
 * non-empty when [recordedFingerprint] is null. An SSH server picks which host-key algorithm the
 * exchange uses, so a peer that offers only an algorithm the store has no record of turns "this key
 * changed" into "I have never seen this host" — the question loses its warning and its button order.
 * OpenSSH says the same thing here ("keys of different type are already known for this host").
 */
data class HostTrustRequest(
    val kind: HostTrustKind,
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val recordedFingerprint: String? = null,
    val recordedKeyTypes: List<String> = emptyList(),
    val certificate: HostTrustCertificate? = null,
) {
    /** Whether this host is already trusted by a different key — the wording and the danger differ. */
    val keyChanged: Boolean get() = recordedFingerprint != null

    /**
     * Whether accepting this would add a second identity for a host that already has one. Either
     * shape means the host is not who it was: the dialog leads with the refusal for both.
     */
    val hostAlreadyKnown: Boolean get() = keyChanged || recordedKeyTypes.isNotEmpty()
}

/**
 * Asks the user to accept or refuse a host's identity. Implemented by the UI, which suspends until
 * a dialog is answered; the connection waits meanwhile.
 */
fun interface HostTrustPrompt {
    /** @return true to trust this identity and remember it, false to refuse the connection. */
    suspend fun confirm(request: HostTrustRequest): Boolean
}

/**
 * The same decision as [HostTrustPrompt], asked the way the verifiers can ask it: they run inside a
 * handshake, on the transport's own thread, and have no coroutine to suspend. A UI-backed decider is
 * a [HostTrustPrompt] bridged on the JVM (`asDecider`); the graphs without a UI use [SilentTofu].
 *
 * An implementation must not block indefinitely — the connection is held open while it thinks. It
 * may throw, and a caller must let that through rather than read it as a refusal: the two are
 * different events, and only one of them is the user's answer.
 */
fun interface HostTrustDecider {
    fun decide(request: HostTrustRequest): Boolean

    companion object {
        /**
         * Trust-on-first-use with nobody asked: a first key is accepted, a changed one is not. This
         * is what every release before the trust dialog did, and it stays the default so a graph
         * assembled without a UI (tests, probes, the harness) keeps deciding the safe way rather
         * than inheriting whatever the last caller passed.
         */
        val SilentTofu: HostTrustDecider = HostTrustDecider { !it.keyChanged }

        /** Refuses everything not already trusted. For a connection with nobody watching. */
        val Refuse: HostTrustDecider = HostTrustDecider { false }
    }
}

/**
 * How long a trust question waits for the user before it counts as refused, measured from the moment
 * the dialog is actually on screen. A handshake is held open the whole time — RDP asks from inside
 * the TLS exchange — so this is deliberately shorter than the keyboard-interactive deadline.
 */
const val HOST_TRUST_TIMEOUT_MILLIS: Long = 90_000

/**
 * Backstop for a prompt implementation that never returns at all (a UI that dropped the question on
 * the floor). Longer than [HOST_TRUST_TIMEOUT_MILLIS], which is the deadline users experience; this
 * one only keeps a broken implementation from pinning a transport thread for the life of the process.
 */
const val HOST_TRUST_BACKSTOP_MILLIS: Long = 300_000
