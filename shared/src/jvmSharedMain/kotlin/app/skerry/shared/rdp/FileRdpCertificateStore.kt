package app.skerry.shared.rdp

import app.skerry.shared.trust.HostTrustCertificate
import app.skerry.shared.trust.HostTrustDecider
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.shared.vault.atomicWriteUtf8
import okio.FileSystem
import okio.Path

/**
 * Trust-on-first-use store for RDP server certificates, in the spirit of `known_hosts`: the first
 * fingerprint seen for a host is remembered, and a different one later fails the connection.
 *
 * TOFU rather than the platform trust store because an RDP host signs its own certificate unless an
 * enterprise CA issued one — so "untrusted by the platform" is the normal case, and refusing it
 * would refuse nearly every server.
 *
 * One line per host: `host:port sha256-fingerprint`.
 */
class FileRdpCertificateStore(
    private val file: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val harden: (Path) -> Unit = {},
    private val trust: HostTrustDecider = HostTrustDecider.SilentTofu,
) : RdpCertificateVerifier {

    /**
     * What the user approved in [verify], by `host:port`, until the handshake it belongs to finishes.
     * Guarded by this store's monitor.
     *
     * An approval names the certificate that was on screen *and* the entry it was weighed against,
     * because the two answers are not interchangeable: "trust this host, which I have never seen"
     * does not also mean "replace the fingerprint that appeared in the meantime". Keyed by
     * fingerprint rather than one slot per host, so two connections the user accepts at the same
     * moment do not overwrite each other's answer.
     *
     * Bounded per host: a handshake that dies after the user accepted never reaches [remember], so
     * nothing else drops its approval. Each one needs a click, so the set is small by construction —
     * the bound is there so a session that reconnects to a host with a rotating certificate all day
     * cannot grow it without end. Dropping the oldest is safe: an approval only ever authorises a
     * write of the fingerprint it names, against the entry it names.
     */
    private val approvals = mutableMapOf<String, LinkedHashSet<Approval>>()

    /** [fingerprint] was approved for a host that was recorded as [replacing] at the time (null: unknown). */
    private data class Approval(val fingerprint: String, val replacing: String?)

    /**
     * Whether [offer] may be talked to. A certificate this host is not remembered by — a first sight
     * or a changed one — is put to [trust]; what the user accepts is recorded in [remember] and not
     * before, since the question is asked while the handshake is still in flight and anyone who can
     * answer the connection could have sent the chain.
     *
     * The decision is taken outside the monitor on purpose: a dialog waits for a person, and holding
     * the store's lock for that long would stall every other connection reading the same file.
     */
    override fun verify(offer: RdpCertificateOffer): Boolean {
        val key = key(offer) ?: return false
        val known = synchronized(this) { read()[key] }
        // A platform-trusted, name-matching certificate is remembered too, so a later swap to a
        // self-signed one still shows up as a change.
        if (known == offer.fingerprintSha256) return true
        if (!trust.decide(request(offer, recorded = known))) return false
        // The approval is for the exact fingerprint that was on screen, against the entry it was
        // shown beside: [remember] takes nothing else, so a second certificate arriving mid-handshake
        // can't ride on the answer.
        synchronized(this) {
            val held = approvals.getOrPut(key) { LinkedHashSet() }
            held += Approval(offer.fingerprintSha256, known)
            while (held.size > MAX_APPROVALS_PER_HOST) held.remove(held.first())
        }
        return true
    }

    /**
     * Commit trust, now that the handshake has proven the server holds the key.
     *
     * Serialized, and the decision is re-taken here rather than trusted from [verify]: two first
     * connections to the same host both pass [verify] against an empty store, and between them the
     * certificate can differ. Whoever writes first owns the entry; the other is refused, which is
     * what the single locked read-decide-write did before the two steps were split. A host already
     * remembered by a different fingerprint is overwritten only for the replacement the user
     * approved in [verify].
     */
    @Synchronized
    override fun remember(offer: RdpCertificateOffer): Boolean {
        val key = key(offer) ?: return false
        val entries = read()
        val known = entries[key]
        if (known == offer.fingerprintSha256) return true
        // The store has to be in the state the user judged. Two first connections racing both hold
        // an approval against an empty entry; once the first one writes, the second's answer was
        // given for a host that was unknown and says nothing about replacing what is there now.
        if (Approval(offer.fingerprintSha256, known) !in approvals[key].orEmpty()) return false
        approvals -= key
        write(entries + (key to offer.fingerprintSha256))
        return true
    }

    /**
     * The store line's key, or null for a host this format cannot hold. One entry per line, the key
     * up to the first space and a leading `#` reserved for a comment, so a host carrying whitespace,
     * a control character or that marker would write a line that reads back as an entry for a
     * different host or as no entry at all — and an RDP server picks its own host string through the
     * redirection PDU. A key the reader would not parse back means the store silently cannot hold
     * what it just recorded: every connection is then a first contact and a swapped certificate
     * never reads as a change. Refusing beats encoding — nothing legitimate reaches here with a
     * newline or a `#` in its name.
     */
    private fun key(offer: RdpCertificateOffer) = key(offer.host, offer.port)

    private fun key(host: String, port: Int): String? = when {
        host.isEmpty() || host.startsWith(COMMENT_MARKER) -> null
        host.any { it.isWhitespace() || it.isISOControl() } -> null
        else -> "$host:$port"
    }

    private fun request(offer: RdpCertificateOffer, recorded: String?) = HostTrustRequest(
        kind = HostTrustKind.RdpCertificate,
        host = offer.host,
        port = offer.port,
        // No key type: what identifies an RDP certificate to a person is its subject and issuer,
        // both of which travel in [HostTrustRequest.certificate].
        keyType = "",
        fingerprint = offer.fingerprintSha256,
        recordedFingerprint = recorded,
        certificate = HostTrustCertificate(
            subject = offer.subject,
            issuer = offer.issuer,
            notAfterMillis = offer.notAfterMillis,
            trustedByPlatform = offer.trustedByPlatform,
            hostnameMatches = offer.hostnameMatches,
        ),
    )

    /** Forget the entry for [host]:[port], so the next connection trusts on first use again. */
    @Synchronized
    fun forget(host: String, port: Int) {
        val key = key(host, port) ?: return
        write(read() - key)
    }

    /** Every remembered host and its fingerprint. */
    @Synchronized
    fun entries(): Map<String, String> = read()

    private fun read(): Map<String, String> {
        if (!fileSystem.exists(file)) return emptyMap()
        val text = fileSystem.read(file) { readUtf8() }
        return text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_MARKER)) return@mapNotNull null
                val parts = trimmed.split(" ", limit = 2)
                if (parts.size != 2) null else parts[0] to parts[1]
            }
            .toMap()
    }

    private fun write(entries: Map<String, String>) {
        val text = entries.entries.sortedBy { it.key }.joinToString("") { "${it.key} ${it.value}\n" }
        // atomicWriteUtf8 hardens the file to 0600 before it lands and never leaves a half-written
        // store behind (the same discipline as known_hosts).
        atomicWriteUtf8(fileSystem, file, text, harden)
    }
}

/** A line the reader skips — and so a prefix a store key must refuse. */
private const val COMMENT_MARKER = "#"

/** How many un-committed approvals one host keeps before the oldest is dropped. */
internal const val MAX_APPROVALS_PER_HOST = 16
