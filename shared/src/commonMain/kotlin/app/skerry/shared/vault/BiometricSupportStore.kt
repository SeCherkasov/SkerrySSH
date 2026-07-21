package app.skerry.shared.vault

import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

/**
 * Remembers that biometric unlock is impossible on this device: every [BiometricKeyHardening] rung
 * was tried and the enclave still refused to decrypt with an authorized key (#23 — Xiaomi/HyperOS
 * and friends). Without persistence the toggle would keep inviting the user into a flow that cannot
 * work; with it the UI offers the master password instead, plus an explicit "check again" (a ROM
 * update or a reboot can fix the enclave).
 *
 * The verdict is a UX hint, never a security decision: nothing here can enable biometrics or expose
 * key material.
 */
interface BiometricSupportStore {

    /** Whether this device already proved it cannot decrypt the vault via biometrics. */
    fun isUnsupported(): Boolean

    /** Record the verdict after the whole hardening ladder failed. */
    fun markUnsupported()

    /**
     * Record one enclave refusal at unlock time and return how many happened in a row. A single
     * refusal is weak evidence — a keystore daemon hiccup looks the same — so the caller only acts on
     * it once the count reaches its threshold, instead of destroying a working enrollment on the spot.
     */
    fun recordRefusal(): Int

    /** Forget the refusal streak after a biometric unlock worked. */
    fun clearRefusals()

    /** Remember which [BiometricKeyHardening] the current enrollment ended up on. */
    fun rememberHardening(hardening: BiometricKeyHardening)

    /** The rung the current enrollment uses, or `null` if unknown (never enabled, or written by an older build). */
    fun hardening(): BiometricKeyHardening?

    /** Forget everything — on a successful enable, or when the user asks to re-check. */
    fun clear()

    /** Session-scoped default: the verdict holds until the app restarts. */
    class Volatile : BiometricSupportStore {
        private var unsupported = false
        private var refusals = 0
        private var hardening: BiometricKeyHardening? = null
        override fun isUnsupported(): Boolean = unsupported
        override fun markUnsupported() { unsupported = true }
        override fun recordRefusal(): Int = ++refusals
        override fun clearRefusals() { refusals = 0 }
        override fun rememberHardening(hardening: BiometricKeyHardening) { this.hardening = hardening }
        override fun hardening(): BiometricKeyHardening? = hardening
        override fun clear() { unsupported = false; refusals = 0; hardening = null }
    }
}

/**
 * On-disk form of the verdict. [deviceId] scopes it, like [BioArtifact] — a copied workspace
 * directory must not silence biometrics on another device. [refusals] counts consecutive enclave
 * refusals at unlock; it survives restarts because the process usually dies between two attempts.
 */
@Serializable
data class BiometricSupportVerdict(
    val deviceId: String,
    val unsupported: Boolean = false,
    val refusals: Int = 0,
    /**
     * Name of the [BiometricKeyHardening] the current enrollment runs on. A plain string, not the
     * enum: a rung added by a newer build must degrade to "unknown" instead of making the whole file
     * unreadable.
     */
    val hardening: String? = null,
)

/**
 * File-backed [BiometricSupportStore] over okio, stored next to `vault.json` as
 * `vault.bio.unsupported`. Reads never throw (a corrupt file just means "no verdict"): a failure
 * here must not keep the app from starting, and the worst case is offering a toggle that fails once.
 */
class FileBiometricSupportStore(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val deviceId: String,
    private val harden: (Path) -> Unit = {},
) : BiometricSupportStore {

    private val file = JsonFileStore(path, fileSystem, BiometricSupportVerdict.serializer(), harden)

    override fun isUnsupported(): Boolean = read()?.unsupported == true

    override fun markUnsupported() {
        write((read() ?: BiometricSupportVerdict(deviceId)).copy(unsupported = true))
    }

    override fun recordRefusal(): Int {
        val current = read() ?: BiometricSupportVerdict(deviceId)
        write(current.copy(refusals = current.refusals + 1))
        return current.refusals + 1
    }

    override fun clearRefusals() {
        val current = read() ?: return
        if (current.refusals != 0) write(current.copy(refusals = 0))
    }

    override fun rememberHardening(hardening: BiometricKeyHardening) {
        val current = read() ?: BiometricSupportVerdict(deviceId)
        write(current.copy(hardening = hardening.name))
    }

    override fun hardening(): BiometricKeyHardening? =
        read()?.hardening?.let { name -> BiometricKeyHardening.entries.firstOrNull { it.name == name } }

    override fun clear() {
        runCatching { file.clear() }
    }

    /** `null` for a missing/corrupt file, or one written by another device (see [deviceId]). */
    private fun read(): BiometricSupportVerdict? = file.read()?.takeIf { it.deviceId == deviceId }

    private fun write(verdict: BiometricSupportVerdict) {
        runCatching { file.write(verdict) }
    }
}
