package app.skerry.shared.rdp

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
) : RdpCertificateVerifier {

    /**
     * Whether [offer] is trusted, recording it on first sight.
     *
     * Serialized: one store answers every connection this client makes, and reading the file and
     * writing it back is not one step. Two first connections at once would otherwise write over
     * each other, and the host whose fingerprint was lost is trusted on first use a second time —
     * which is the connection a swapped certificate would have been caught on.
     */
    @Synchronized
    override fun verify(offer: RdpCertificateOffer): Boolean {
        val key = "${offer.host}:${offer.port}"
        val entries = read()
        val known = entries[key]
        return when (known) {
            null -> {
                // First sight: remember it. A platform-trusted, name-matching certificate is
                // recorded too, so a later swap to a self-signed one still shows up as a change.
                write(entries + (key to offer.fingerprintSha256))
                true
            }

            offer.fingerprintSha256 -> true
            // The certificate changed. That is a server rebuild as often as an attack, and a client
            // cannot tell them apart — accepting it quietly would make the store pointless.
            else -> false
        }
    }

    /** Forget the entry for [host]:[port], so the next connection trusts on first use again. */
    @Synchronized
    fun forget(host: String, port: Int) = write(read() - "$host:$port")

    /** Every remembered host and its fingerprint. */
    @Synchronized
    fun entries(): Map<String, String> = read()

    private fun read(): Map<String, String> {
        if (!fileSystem.exists(file)) return emptyMap()
        val text = fileSystem.read(file) { readUtf8() }
        return text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
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
