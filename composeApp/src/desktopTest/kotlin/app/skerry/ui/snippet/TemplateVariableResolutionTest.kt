package app.skerry.ui.snippet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.desktop.CROSS_THREAD_TIMEOUT_MS
import app.skerry.ui.desktop.runForm
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.identity.FakeCredVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The confirmation actually resolving its `${'$'}{{vault:…}}` references against the keychain. The
 * pieces are unit-tested elsewhere; what this covers is the wiring — without it a snippet holding a
 * key reference would open with Run disabled forever and a row that never leaves "reading", and
 * every other test in the suite would still pass.
 */
@OptIn(ExperimentalTestApi::class)
class TemplateVariableResolutionTest {

    private fun keychain(vararg secrets: Credential): CredentialManagerController {
        val store = CredentialStore(FakeCredVault())
        secrets.forEach { store.put(it) }
        return CredentialManagerController(store) { "gen" }.apply { reload() }
    }

    private fun reference(name: String) =
        SnippetSegment.Variable(SnippetVariableKind.VAULT, "vault", name, "\${{vault:$name}}")

    /**
     * Drives one confirmation's worth of resolution and hands back the settled values. Waits on the
     * map, not on `waitForIdle`: a key parse runs on [kotlinx.coroutines.Dispatchers.Default], which
     * the composition's idle check cannot see, and asserting straight after it is the frame race
     * this suite already swept once.
     */
    private fun resolve(
        credentials: CredentialManagerController,
        vararg names: String,
    ): TemplateVariableValues {
        lateinit var values: TemplateVariableValues
        runForm({
            CompositionLocalProvider(
                LocalCredentials provides credentials,
                LocalSshKeyGenerator provides FakeSshKeyGenerator(),
            ) {
                values = rememberTemplateVariableValues("request", names.map { reference(it) })
            }
        }) {
            waitUntil("every vault reference answered", timeoutMillis = CROSS_THREAD_TIMEOUT_MS) { values.vaultResolutions.size == names.size }
        }
        return values
    }

    @Test
    fun `a key reference resolves to the public half and unblocks the run`() {
        val credentials = keychain(Credential("c-1", "temp_pubkey", CredentialSecret.PrivateKey("pem", "pp")))

        val values = resolve(credentials, "temp_pubkey")

        assertTrue(values.canRun, "the confirmation never left its pending state")
        assertEquals(FAKE_PUBLIC_KEY, values.value(reference("temp_pubkey"), masked = false))
        // Public material: nothing for the production guard to mask further down the line.
        assertEquals(emptyList(), values.vaultSecrets())
    }

    @Test
    fun `a password reference resolves without the key parser and is masked`() {
        val credentials = keychain(Credential("c-1", "prod-db", CredentialSecret.Password("s3cret")))

        val values = resolve(credentials, "prod-db")

        assertTrue(values.canRun)
        assertEquals(SECRET_MASK, values.value(reference("prod-db"), masked = true))
        assertEquals(listOf("s3cret"), values.vaultSecrets())
    }

    @Test
    fun `a password and a key in one command both survive the async half`() {
        val credentials = keychain(
            Credential("c-1", "prod-db", CredentialSecret.Password("s3cret")),
            Credential("c-2", "temp_pubkey", CredentialSecret.PrivateKey("pem")),
        )

        val values = resolve(credentials, "prod-db", "temp_pubkey")

        // The password is resolved on the spot and the key lands later: the late write merges into
        // the map rather than replacing it, or the password would vanish as the key arrives.
        assertTrue(values.canRun)
        assertEquals("s3cret", values.value(reference("prod-db"), masked = false))
        assertEquals(FAKE_PUBLIC_KEY, values.value(reference("temp_pubkey"), masked = false))
    }

    @Test
    fun `a secret landing mid-dialog does not change what the preview already means`() {
        val credentials = keychain(Credential("c-1", "prod-db", CredentialSecret.Password("s3cret")))
        lateinit var values: TemplateVariableValues
        runForm({
            CompositionLocalProvider(
                LocalCredentials provides credentials,
                LocalSshKeyGenerator provides FakeSshKeyGenerator(),
            ) {
                values = rememberTemplateVariableValues("request", listOf(reference("prod-db")))
            }
        }) {
            waitUntil("the reference answered", timeoutMillis = CROSS_THREAD_TIMEOUT_MS) { values.vaultResolutions.isNotEmpty() }

            // Background sync rotating the secret while the confirmation is open: the keychain is
            // read once when it opens, so the line the user is reading stays the line that is sent.
            credentials.save(CredentialDraft(id = "c-1", label = "prod-db", kind = CredentialKind.PASSWORD, password = "rotated"))
            waitForIdle()

            assertEquals("s3cret", values.value(reference("prod-db"), masked = false))
        }
    }

    @Test
    fun `a reference to nothing keeps the run blocked`() {
        val values = resolve(keychain(), "ghost")

        assertFalse(values.canRun)
        assertEquals("", values.value(reference("ghost"), masked = false))
    }
}
