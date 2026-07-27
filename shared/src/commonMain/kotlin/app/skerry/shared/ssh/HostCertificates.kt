package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * A certificate authority the user trusts to vouch for host keys — the `@cert-authority` line of an
 * OpenSSH `known_hosts` file, as a record.
 *
 * [hostPattern] limits what the CA covers (see [HostPattern]); a CA for the production fleet must
 * not silently certify a laptop on the same network. [publicKey] is the key's base64 wire blob (the
 * second field of the line) and [fingerprint] its `SHA256:` form — the comparison is made on the
 * fingerprint, the blob is kept so the entry can be shown and exported as written.
 *
 * `@Serializable`: the record syncs like [KnownHost], so trusting a CA on one device covers the
 * fleet from every device.
 */
@Serializable
data class TrustedCa(
    val id: String,
    val hostPattern: String,
    val keyType: String,
    val publicKey: String,
    val fingerprint: String,
    val label: String = "",
    val addedAt: String = "",
)

/** Persistent store of trusted certificate authorities. */
interface TrustedCaStore {
    fun all(): List<TrustedCa>

    /**
     * [all], or `null` when the backing storage is unreadable (locked vault) — as opposed to
     * readable-but-empty. [HostCertificateVerifier] **fails closed** on `null`: without the trusted
     * set, "no CA configured" is indistinguishable from "the CA covering this host is in there".
     */
    fun allOrNull(): List<TrustedCa>? = all()

    /** Add or replace by [TrustedCa.id]. */
    fun put(ca: TrustedCa)

    fun remove(id: String)
}

/**
 * A CA public key the user pasted, once it has been parsed and checked. [hostPattern] is non-null
 * only when a whole `@cert-authority` line was pasted (it carries the pattern); otherwise the user
 * supplies it in the form. [publicKey] is the base64 wire blob, [comment] the trailing free text
 * OpenSSH allows (often an email), which the form offers as the entry's label.
 */
data class ParsedCaKey(
    val keyType: String,
    val publicKey: String,
    val fingerprint: String,
    val hostPattern: String? = null,
    val comment: String = "",
)

/**
 * Parses a pasted CA key. Platform implementation (SSH wire format via sshj) is injected, like
 * [app.skerry.shared.vault.SshCertificateInspector] — `commonMain` has no crypto to compute a
 * fingerprint with.
 */
fun interface CaKeyParser {
    /** @return null when [text] is not a usable CA key (see the implementation for what's refused). */
    fun parse(text: String): ParsedCaKey?
}

/**
 * Host key trust by CA signature instead of a remembered fingerprint — OpenSSH's `@cert-authority`.
 * On a fleet whose machines are recreated (autoscaling, immutable infra) trust-on-first-use reports
 * a changed key every time, which trains the user to accept it; a certificate signed by a CA they
 * trust is checkable without ever having seen the machine.
 *
 * Decision order for an offered certificate:
 *  1. no certificate at all -> [fallback] (ordinary TOFU);
 *  2. no trusted CA matches the signing key **and** the host -> [fallback] on the key inside the
 *     certificate. Not a rejection: a user who hasn't configured a CA still connects, and TOFU now
 *     remembers the host's key rather than the certificate, so re-issues stop looking like key
 *     changes;
 *  3. a trusted CA covers this host -> the certificate must hold up completely (below), otherwise
 *     the connection is refused. Once a CA is trusted for a host, falling back to TOFU there would
 *     let a bad certificate downgrade into a first-contact prompt.
 *
 * What "hold up" means: the transport verified the CA's signature ([OfferedHostCertificate
 * .caSignatureVerified]), it is a host certificate rather than a user one, it carries no critical
 * options (OpenSSH defines none for host certificates, so an unknown one is a demand we can't
 * honour), the current time is inside its validity window, and one of its principals matches the
 * host being dialed — or it lists none at all, which PROTOCOL.certkeys defines as "valid for any
 * host".
 *
 * [nowEpochSeconds] supplies the clock (no datetime library in `commonMain`).
 */
class HostCertificateVerifier(
    private val cas: TrustedCaStore,
    private val fallback: HostKeyVerifier,
    private val nowEpochSeconds: () -> Long,
) : HostKeyVerifier {

    override fun verify(offer: HostKeyOffer): Boolean {
        val certificate = offer.certificate ?: return fallback.verify(offer)
        val trusted = cas.allOrNull() ?: return false
        // A blank fingerprint means the CA key didn't parse; it must not match a stored entry whose
        // own fingerprint is blank (a corrupt or hand-edited synced record).
        if (certificate.caFingerprint.isBlank()) return fallback.verify(offer.bareKey())
        val covered = trusted.any {
            it.fingerprint == certificate.caFingerprint && HostPattern.matches(it.hostPattern, offer.host, offer.port)
        }
        if (!covered) return fallback.verify(offer.bareKey())

        if (!certificate.caSignatureVerified) return false
        if (!certificate.hostCertificate) return false
        if (certificate.criticalOptions.isNotEmpty()) return false
        val now = nowEpochSeconds()
        if (now < certificate.validAfterEpochSeconds || now >= certificate.validBeforeEpochSeconds) return false
        // Bound the work a hostile server can ask for: an offered certificate can list any number
        // of principals, each matched against the host.
        if (certificate.principals.isNotEmpty() &&
            certificate.principals.asSequence().take(MAX_PRINCIPALS).none { principalMatches(it, offer.host) }
        ) {
            return false
        }
        // Deliberately no store write: see the class doc — certificates rotate, trust is in the CA.
        return true
    }

    private fun principalMatches(principal: String, host: String): Boolean =
        principal.length <= HostPattern.MAX_PATTERN_LENGTH && globMatches(principal.lowercase(), host.lowercase())

    private companion object {
        /** Principals actually checked from one certificate; real ones list a handful of names. */
        const val MAX_PRINCIPALS = 256
    }
}
