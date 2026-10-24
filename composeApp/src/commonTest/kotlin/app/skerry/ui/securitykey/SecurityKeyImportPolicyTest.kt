package app.skerry.ui.securitykey

import app.skerry.shared.securitykey.SecurityKeyMetadata
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.vault.hardwareKeyImportAllowed
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityKeyImportPolicyTest {
    @Test fun hardwareImportRequiresOptInButSoftwareImportDoesNot() {
        val software = SshPublicKeyInfo("ssh-ed25519 AAAA", "SHA256:test", "ED25519")
        val hardware = software.copy(securityKey = SecurityKeyMetadata("ssh:production", true, 5))
        assertTrue(hardwareKeyImportAllowed(software, false))
        assertFalse(hardwareKeyImportAllowed(hardware, false))
        assertTrue(hardwareKeyImportAllowed(hardware, true))
        // Unknown/unreadable metadata never makes an import valid.
        assertFalse(hardwareKeyImportAllowed(null, true))
    }
}
