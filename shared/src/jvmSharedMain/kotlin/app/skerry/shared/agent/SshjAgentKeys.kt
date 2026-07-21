package app.skerry.shared.agent

import app.skerry.shared.ssh.ensureCryptoProvider
import com.hierynomus.sshj.signature.SignatureEdDSA
import java.security.PrivateKey
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import net.schmizz.sshj.userauth.password.PasswordUtils

/**
 * [SshAgentKeys] over the vault's keys, parsed with the same stack the transport authenticates
 * with (sshj + BouncyCastle) so a key that works for a direct connection works through the agent.
 *
 * [material] is re-read on every request instead of being captured once: the user can add a key
 * to the agent, remove one, or lock the vault while a forwarded channel is open, and the next
 * request must see that. Parsed keys are cached by credential id (a passphrase-protected key costs
 * a bcrypt KDF round to open, and `ssh` asks the agent on every connection), but the cache is
 * checked against the current PEM/passphrase, so editing a key in place does not serve the old one.
 *
 * [clear] drops every parsed private key — called when the vault locks, so agent keys do not
 * outlive the vault they came from. A load that was already in flight cannot repopulate the cache
 * afterwards ([epoch] guard).
 */
class SshjAgentKeys(
    private val material: () -> List<SshAgentKeyMaterial>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshAgentKeys {

    private val lock = Any()
    private var cache: Map<String, LoadedKey> = emptyMap()
    private var epoch = 0

    override suspend fun identities(): List<SshAgentIdentity> =
        load().flatMap { key -> key.keyBlobs.map { SshAgentIdentity(it, key.comment) } }

    override suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int): SshAgentSignature? {
        val key = load().firstOrNull { key -> key.keyBlobs.any { it.contentEquals(keyBlob) } } ?: return null
        return withContext(dispatcher) {
            val signature = signatureFor(key.type, flags) ?: return@withContext null
            runCatching {
                signature.initSign(key.privateKey)
                signature.update(data)
                // Agent wire format for a signature is `string algorithm || string blob` — the same
                // shape sshj builds for userauth (KeyedAuthMethod.putSig), minus the outer string
                // the userauth packet adds.
                val blob = Buffer.PlainBuffer()
                    .putString(signature.signatureName)
                    .putBytes(signature.encode(signature.sign()))
                    .compactData
                SshAgentSignature(blob, key.comment)
            }.getOrNull()
        }
    }

    /** Forget every parsed key (vault locked / agent switched off). */
    fun clear() = synchronized(lock) {
        cache = emptyMap()
        epoch++
    }

    private suspend fun load(): List<LoadedKey> = withContext(dispatcher) {
        val wanted = material()
        val (snapshot, startedAt) = synchronized(lock) { cache to epoch }
        val loaded = wanted.mapNotNull { entry ->
            snapshot[entry.id]?.takeIf { it.matches(entry) } ?: parse(entry)
        }
        synchronized(lock) {
            // A clear() in the meantime (vault locked) wins: publishing here would restore keys the
            // lock was supposed to drop.
            if (epoch == startedAt) cache = loaded.associateBy { it.id }
        }
        loaded
    }

    /**
     * Parse one secret into a usable key. A key that cannot be opened (wrong passphrase, damaged
     * PEM, unsupported type) is silently skipped rather than failing the whole listing: the agent
     * offers what it can, exactly as `ssh-add` does, and the user sees the key missing from the
     * Settings list.
     */
    private fun parse(entry: SshAgentKeyMaterial): LoadedKey? = runCatching {
        // Android's system BouncyCastle is stripped down and cannot parse PEM keys; register the
        // full provider first (idempotent, no-op on desktop) — as the transport and key generator do.
        ensureCryptoProvider()
        val pwdf = entry.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        // SSHClient opens no connection here; `use` just frees the parsing resources (as in
        // BouncyCastleSshKeyGenerator.inspect). Both key halves are taken inside the block.
        val (privateKey, publicKey) = SSHClient().use { client ->
            val keys = client.loadKeys(entry.privateKeyPem, null, pwdf)
            keys.private to keys.public
        }
        val publicBlob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        LoadedKey(
            id = entry.id,
            comment = entry.comment,
            // A certificate credential offers BOTH identities, as `ssh-add` does: the certificate
            // (its base64 field already IS the ssh-wire blob, no re-encoding needed) and the plain
            // key behind it. Offering only the certificate would lock the user out of every server
            // that knows the key but does not trust the CA. A certificate that cannot be parsed
            // costs only itself — the plain key is still offered.
            keyBlobs = listOfNotNull(entry.certificate?.let { runCatching { certificateBlob(it) }.getOrNull() }, publicBlob),
            privateKey = privateKey,
            // Type comes from the PUBLIC half: sshj can classify a private key for RSA/Ed25519 but
            // not always for EC (a provider that reports the algorithm differently drops it to
            // UNKNOWN), and an unclassified key would silently refuse to sign.
            type = KeyType.fromKey(publicKey),
            pem = entry.privateKeyPem,
            passphrase = entry.passphrase,
            certificate = entry.certificate,
        )
    }.getOrNull()

    private fun certificateBlob(certificate: String): ByteArray {
        val fields = certificate.trim().split(Regex("\\s+"))
        require(fields.size >= 2) { "expected format '<type> <base64> [comment]'" }
        return Base64.getDecoder().decode(fields[1])
    }

    /**
     * Signature algorithm for a key. RSA honours the client's `rsa-sha2-*` flags; with no flags it
     * falls back to `ssh-rsa` (SHA-1), which modern servers refuse but old ones still require —
     * the agent answers what was asked for, it does not pick the policy.
     */
    private fun signatureFor(type: KeyType, flags: Int): Signature? = when (type) {
        // SHA-256 is tested first, as OpenSSH's own agent does: a client that (wrongly) sets both
        // flags gets the algorithm it names in the userauth request, not the other one.
        KeyType.RSA -> when {
            flags and SshAgentCodec.FLAG_RSA_SHA2_256 != 0 -> SignatureRSA.FactoryRSASHA256().create()
            flags and SshAgentCodec.FLAG_RSA_SHA2_512 != 0 -> SignatureRSA.FactoryRSASHA512().create()
            else -> SignatureRSA.FactorySSHRSA().create()
        }
        KeyType.ED25519 -> SignatureEdDSA.Factory().create()
        KeyType.ECDSA256 -> SignatureECDSA.Factory256().create()
        KeyType.ECDSA384 -> SignatureECDSA.Factory384().create()
        KeyType.ECDSA521 -> SignatureECDSA.Factory521().create()
        else -> null
    }

    /** A parsed key held in memory while the vault is unlocked. */
    private class LoadedKey(
        val id: String,
        val comment: String,
        /** Wire blobs this key answers to: the certificate (if any) first, then the plain key. */
        val keyBlobs: List<ByteArray>,
        val privateKey: PrivateKey,
        val type: KeyType,
        private val pem: String,
        private val passphrase: String?,
        private val certificate: String?,
    ) {
        /** Still the same secret? An edited key must be re-parsed, not served from the cache. */
        fun matches(entry: SshAgentKeyMaterial): Boolean =
            pem == entry.privateKeyPem &&
                passphrase == entry.passphrase &&
                certificate == entry.certificate &&
                comment == entry.comment
    }
}
