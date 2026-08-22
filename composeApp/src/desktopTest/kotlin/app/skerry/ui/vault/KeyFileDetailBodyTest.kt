package app.skerry.ui.vault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_key_file_device_only
import app.skerry.ui.generated.resources.vault_key_file_missing
import app.skerry.ui.generated.resources.vault_stored_ciphertext
import app.skerry.ui.generated.resources.vault_stored_local
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one thing issue #174 puts on screen. A file-backed secret is silently excluded from sync
 * everywhere else — the pane is where the user finds out, so the line has to be there whether or not
 * the file itself can be read from here (a record an older release synced in shows both).
 */
@OptIn(ExperimentalTestApi::class)
class KeyFileDetailBodyTest {

    private val secret = CredentialSecret.KeyFile(privateKeyRef = "/home/maya/.tsh/keys/id")

    @Test
    fun `a file-backed secret says it does not sync`() {
        runForm({ KeyFileDetailBody(secret, state = null, mono = FontFamily.Monospace) }) {
            val expected = string(Res.string.vault_key_file_device_only)
            assertTrue(drawnText().any { it.contains(expected) }, "was ${drawnText()}")
        }
    }

    @Test
    fun `it says so about the kind, not about where this record came from`() {
        // Inherited from a pre-fix release: the file is absent here, so the pane also draws "not
        // readable from this device". The two lines must not read as one claim about provenance.
        val absent = KeyFileState(
            keyReadable = false,
            certificateRef = null,
            certificateExpected = false,
            certificateReadable = false,
            certificate = null,
        )
        runForm({ KeyFileDetailBody(secret, state = absent, mono = FontFamily.Monospace) }) {
            val drawn = drawnText()
            val missing = string(Res.string.vault_key_file_missing)
            val kind = string(Res.string.vault_key_file_device_only)
            assertTrue(drawn.any { it.contains(missing) }, "the pane still says the file is absent, was $drawn")
            assertTrue(drawn.any { it.contains(kind) }, "and still states the kind does not sync, was $drawn")
            // The order is the claim: where this record is readable comes first, what it is comes last.
            assertTrue(
                drawn.indexOfFirst { it.contains(missing) } < drawn.indexOfFirst { it.contains(kind) },
                "was $drawn",
            )
        }
    }

    /**
     * The claim the pane makes twice. "Stored on server" is an account-level fact everywhere else,
     * and for a file-backed secret the account-level answer is wrong: the record never leaves, so the
     * row a user reads to find out where the ciphertext is must say so too.
     */
    @Test
    fun `a file-backed secret is stored on this device even with an account connected`() {
        runForm({ SecretEncryptionRows(syncing = true, secret = secret) }) {
            val drawn = drawnText()
            assertTrue(drawn.any { it.contains(string(Res.string.vault_stored_local)) }, "was $drawn")
            assertTrue(drawn.none { it.contains(string(Res.string.vault_stored_ciphertext)) }, "was $drawn")
        }
    }

    @Test
    fun `an ordinary secret still reports the server it is synced to`() {
        val password = CredentialSecret.Password("hunter2")
        runForm({ SecretEncryptionRows(syncing = true, secret = password) }) {
            assertTrue(drawnText().any { it.contains(string(Res.string.vault_stored_ciphertext)) }, "was ${drawnText()}")
        }
    }
}
