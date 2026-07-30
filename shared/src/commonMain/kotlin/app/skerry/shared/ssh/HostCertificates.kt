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
 * Decision order turns on whether any trusted CA *claims* the host — its [TrustedCa.hostPattern]
 * matches — and not on what the server chose to offer:
 *  1. no CA claims the host -> [fallback] (ordinary TOFU), on the key inside the certificate if one
 *     came with the offer. Not a rejection: a user who hasn't configured a CA still connects, and
 *     TOFU remembers the host's own key rather than the certificate, so re-issues stop looking like
 *     key changes;
 *  2. a CA claims the host -> the offer must be a certificate that holds up completely (below).
 *     Anything else is refused and [fallback] is never consulted — a bare key, a certificate signed
 *     by some other CA, and one whose CA key did not parse all land here. The reason is the store
 *     write this class deliberately skips: a CA-verified connection records nothing, so as far as
 *     TOFU is concerned such a host is permanently a first contact, and a first contact is adopted
 *     silently with no fingerprint to disagree with. Passing any of those down would turn a
 *     configured trust anchor back into blind trust-on-first-use, which is what an on-path server
 *     gets by simply omitting its certificate.
 *
 * An unreadable CA store ([TrustedCaStore.allOrNull] == `null`, e.g. the vault auto-locked during
 * the handshake) refuses the key outright: without the trusted set an ordinary host cannot be told
 * from a claimed one, and guessing "ordinary" is the downgrade above.
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

    override fun check(offer: HostKeyOffer): HostKeyRefusal? {
        // Read before anything else: deciding a bare key needs the trusted set too, now that a CA
        // claiming the host is what makes that key inadmissible.
        val trusted = cas.allOrNull() ?: return HostKeyRefusal.TrustStoreUnreadable
        val claiming = trusted.filter { HostPattern.matches(it.hostPattern, offer.host, offer.port) }
        val certificate = offer.certificate
            ?: return if (claiming.isEmpty()) fallback.check(offer) else HostKeyRefusal.CertificateRejected
        // A blank fingerprint means the CA key didn't parse; it must not match a stored entry whose
        // own fingerprint is blank (a corrupt or hand-edited synced record).
        if (certificate.caFingerprint.isBlank()) return unclaimedFallback(claiming, offer)
        if (claiming.none { it.fingerprint == certificate.caFingerprint }) return unclaimedFallback(claiming, offer)

        // One reason for every failed check below, on purpose: which one it was is a hint to whoever
        // offered the certificate, and the user's move — inspect what the host serves — is the same.
        if (!certificate.caSignatureVerified) return HostKeyRefusal.CertificateRejected
        if (!certificate.hostCertificate) return HostKeyRefusal.CertificateRejected
        if (certificate.criticalOptions.isNotEmpty()) return HostKeyRefusal.CertificateRejected
        val now = nowEpochSeconds()
        if (now < certificate.validAfterEpochSeconds || now >= certificate.validBeforeEpochSeconds) {
            return HostKeyRefusal.CertificateRejected
        }
        // Bound the work a hostile server can ask for: an offered certificate can list any number
        // of principals, each matched against the host.
        if (certificate.principals.isNotEmpty() &&
            certificate.principals.asSequence().take(MAX_PRINCIPALS).none { principalMatches(it, offer.host) }
        ) {
            return HostKeyRefusal.CertificateRejected
        }
        // Deliberately no store write: see the class doc — certificates rotate, trust is in the CA.
        return null
    }

    /**
     * A certificate this class cannot use: TOFU decides only if no CA claims the host, and then on
     * the key inside the certificate rather than the certificate itself, whose fingerprint changes
     * on every re-issue. TOFU's own reason is passed through — the host it refused is not a
     * certificate host, and saying so would send the user after the wrong thing.
     */
    private fun unclaimedFallback(claiming: List<TrustedCa>, offer: HostKeyOffer): HostKeyRefusal? =
        if (claiming.isEmpty()) fallback.check(offer.bareKey()) else HostKeyRefusal.CertificateRejected

    private fun principalMatches(principal: String, host: String): Boolean =
        principal.length <= HostPattern.MAX_PATTERN_LENGTH && globMatches(principal.lowercase(), host.lowercase())

    private companion object {
        /** Principals actually checked from one certificate; real ones list a handful of names. */
        const val MAX_PRINCIPALS = 256
    }
}
