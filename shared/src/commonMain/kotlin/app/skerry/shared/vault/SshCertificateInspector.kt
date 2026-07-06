package app.skerry.shared.vault

/**
 * Public (non-secret) SSH certificate metadata for display in the vault manager. Computed
 * entirely from the certificate's public part (`*-cert.pub`) — no private key needed or held.
 * Dates are pre-formatted as strings (`yyyy-MM-dd`, UTC) plus an [expired] flag, since
 * `commonMain` has no datetime library; formatting is done by the platform inspector.
 *
 * [keyTypeLabel] — label of the underlying key (as in [SshPublicKeyInfo.keyTypeLabel], e.g.
 * `ED25519`). [keyId] — certificate identifier (`-I` in `ssh-keygen`). [principals] — names the
 * certificate is issued for (empty == "any"). [serial] — serial number as a string (uint64 is
 * wider than `Long`). [validFrom]/[validUntil] — validity window (`validUntil` == [FOREVER] for
 * unlimited). [caFingerprintSha256] — fingerprint of the CA key (`SHA256:`+base64, no padding).
 */
data class SshCertificateInfo(
    val keyTypeLabel: String,
    val keyId: String,
    val principals: List<String>,
    val serial: String,
    val validFrom: String,
    val validUntil: String,
    val expired: Boolean,
    val caFingerprintSha256: String,
) {
    companion object {
        /** [validUntil] value for a certificate with no expiry (OpenSSH `valid before` == max). */
        const val FOREVER: String = "forever"
    }
}

/**
 * SSH certificate inspector. Platform implementation (desktop/Android — sshj over the SSH wire
 * format) is injected; the UI only depends on this contract. Coroutine-free: parsing is
 * synchronous and cheap (triggered by user action).
 */
interface SshCertificateInspector {
    /**
     * Extract public metadata from certificate string [certificate] (`*-cert.pub`, format
     * `ssh-…-cert-v01@openssh.com <base64> [comment]`). Returns `null` if the certificate can't
     * be parsed (malformed string / not a certificate / unsupported type) — a corrupt secret
     * must not crash the vault list.
     */
    fun inspect(certificate: String): SshCertificateInfo?
}
