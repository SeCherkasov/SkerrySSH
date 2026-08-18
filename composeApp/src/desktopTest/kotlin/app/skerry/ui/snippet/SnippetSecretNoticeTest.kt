package app.skerry.ui.snippet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.UiTags
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.desktop.CROSS_THREAD_TIMEOUT_MS
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededSnippets
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_secret_note
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.identity.FakeCredVault
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Secrets are inserted as plain text" is a warning about what the run puts on the wire. A command
 * whose only vault reference is a key carries the key's public half — a warning there is false, and
 * a warning that cries wolf is the one nobody reads before the run that does carry a password.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetSecretNoticeTest {

    /** Whether the plaintext warning is drawn for a snippet referencing [entry]. */
    private fun noticeShownFor(entry: Credential, reference: String): Boolean {
        val store = CredentialStore(FakeCredVault()).apply { put(entry) }
        val credentials = CredentialManagerController(store) { "gen" }.apply { reload() }
        val manager = seededSnippets()
        val id = manager.save(SnippetDraft(label = "grant", command = "echo \${{vault:$reference}}"))
        manager.run(id) { _, _ -> }

        var shown = false
        runForm({
            CompositionLocalProvider(
                LocalCredentials provides credentials,
                LocalSshKeyGenerator provides FakeSshKeyGenerator(),
            ) {
                SnippetRunDialog(manager)
            }
        }) {
            // A key reference resolves through Dispatchers.Default, which the composition's idle check
            // cannot see, and Run stays disabled until every reference has answered. Asserting the
            // notice's *absence* straight after waitForIdle would pass on an unresolved dialog —
            // which is exactly the state this test must not accept as evidence.
            waitUntil("the run is resolved enough to send", timeoutMillis = CROSS_THREAD_TIMEOUT_MS) {
                runCatching { onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled() }.isSuccess
            }
            shown = onAllNodes(hasText(string(Res.string.lib_snippet_vars_secret_note)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return shown
    }

    @Test
    fun `a run carrying a password is warned about it`() {
        val password = Credential("c-1", "prod-db", CredentialSecret.Password("s3cret"))

        assertTrue(noticeShownFor(password, "prod-db"))
    }

    @Test
    fun `a run carrying only a public key is not`() {
        val key = Credential("c-1", "temp_pubkey", CredentialSecret.PrivateKey("pem"))

        assertFalse(noticeShownFor(key, "temp_pubkey"))
    }
}
