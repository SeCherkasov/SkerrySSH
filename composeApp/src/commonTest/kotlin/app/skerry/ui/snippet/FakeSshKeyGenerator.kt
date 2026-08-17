package app.skerry.ui.snippet

import app.skerry.shared.vault.GeneratedSshKey
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.SshPublicKeyInfo

/** The public line [FakeSshKeyGenerator] derives, so a test can assert on what was spliced. */
internal const val FAKE_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI temp"

/**
 * Stands in for the platform key parser: [inspect] answers [answer] without touching a PEM, so a test
 * can drive the derived-public-key path (and its failures — a null answer, a blank line, a throw)
 * without a crypto provider. [generate] is not part of any of that and refuses.
 */
internal class FakeSshKeyGenerator(
    private val answer: () -> SshPublicKeyInfo? = { SshPublicKeyInfo(FAKE_PUBLIC_KEY, "SHA256:x", "ED25519") },
) : SshKeyGenerator {
    override fun generate(type: SshKeyType, comment: String): GeneratedSshKey =
        throw UnsupportedOperationException("the tests that use this fake never generate")

    override fun inspect(privateKeyPem: String, passphrase: String?): SshPublicKeyInfo? = answer()
}
