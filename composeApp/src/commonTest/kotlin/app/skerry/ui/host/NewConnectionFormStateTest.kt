package app.skerry.ui.host

import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewConnectionFormStateTest {

    @Test
    fun defaults_port_22_and_blank_rest() {
        val f = NewConnectionFormState()
        assertEquals("22", f.port)
        assertFalse(f.canSave) // name/address/username пустые
    }

    @Test
    fun requires_name_address_username_and_valid_port() {
        val f = NewConnectionFormState().apply {
            name = "prod-web-01"; address = "192.168.1.45"; username = "root"
        }
        assertTrue(f.canSave)
        f.username = "   "
        assertFalse(f.canSave)
    }

    @Test
    fun invalid_or_out_of_range_port_blocks_save() {
        val f = NewConnectionFormState().apply {
            name = "h"; address = "a"; username = "u"
        }
        f.port = "abc"; assertFalse(f.canSave)
        f.port = "0"; assertFalse(f.canSave)
        f.port = "70000"; assertFalse(f.canSave)
        f.port = "2222"; assertTrue(f.canSave)
    }

    @Test
    fun toDraft_trims_and_maps_blank_group_to_null() {
        val f = NewConnectionFormState().apply {
            name = "  prod  "; address = " 10.0.0.1 "; port = " 2222 "; username = " root "; group = "  "
        }
        val draft = f.toDraft(id = "keep-me")
        assertEquals("keep-me", draft.id)
        assertEquals("prod", draft.label)
        assertEquals("10.0.0.1", draft.address)
        assertEquals(2222, draft.port)
        assertEquals("root", draft.username)
        assertNull(draft.group)
    }

    @Test
    fun toDraft_keeps_non_blank_group() {
        val f = NewConnectionFormState().apply {
            name = "h"; address = "a"; username = "u"; group = "Production"
        }
        assertEquals("Production", f.toDraft().group)
        assertNull(f.toDraft().id)
    }

    @Test
    fun toDraft_carries_credential_id() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertEquals("cred-7", f.toDraft(credentialId = "cred-7").credentialId)
        assertNull(f.toDraft().credentialId)
    }

    // --- Аутентификация ---

    private fun validBase() = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }

    // Колбэк-ловушка: saveCredential возвращает id секрета и захватывает черновик.
    private class Captures {
        var credentialDraft: CredentialDraft? = null
        val saveCredential: (CredentialDraft) -> String? = { credentialDraft = it; "cred-id" }
    }

    @Test
    fun default_auth_is_ask_and_resolves_to_null_without_saving() {
        val f = validBase()
        assertEquals(AuthMode.ASK, f.authMode)
        assertTrue(f.canSave) // ASK не требует секрета
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertNull(id)
        assertNull(cap.credentialDraft) // секрет не создаётся
    }

    @Test
    fun existing_credential_requires_selection_and_resolves_to_its_id() {
        val f = validBase().apply { authMode = AuthMode.EXISTING }
        assertFalse(f.canSave) // ничего не выбрано
        f.existingCredentialId = "saved-1"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("saved-1", f.resolveCredentialId(cap.saveCredential))
        assertNull(cap.credentialDraft) // существующий не пересоздаём
    }

    @Test
    fun new_password_requires_value_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_PASSWORD; username = "root"; address = "10.0.0.1" }
        assertFalse(f.canSave) // пароль пуст
        f.password = "s3cr3t"
        assertTrue(f.canSave)
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertEquals("cred-id", id) // возвращается id созданного секрета
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("s3cr3t", cap.credentialDraft?.password)
        assertEquals("root@10.0.0.1", cap.credentialDraft?.label)
        assertNull(cap.credentialDraft?.id) // создаётся новый
    }

    @Test
    fun new_key_requires_pem_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_KEY; username = "ci"; address = "build.host" }
        assertFalse(f.canSave) // PEM пуст
        f.privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"
        f.passphrase = "pp"
        assertTrue(f.canSave)
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertEquals("cred-id", id)
        assertEquals(CredentialKind.PRIVATE_KEY, cap.credentialDraft?.kind)
        assertEquals(f.privateKeyPem, cap.credentialDraft?.privateKeyPem)
        assertEquals("pp", cap.credentialDraft?.passphrase)
        assertEquals("ci@build.host", cap.credentialDraft?.label)
    }

    @Test
    fun new_password_with_failed_credential_save_resolves_to_null() {
        val f = validBase().apply { authMode = AuthMode.NEW_PASSWORD; password = "s3cr3t" }
        val id = f.resolveCredentialId(saveCredential = { null }) // секрет не сохранился (например, без vault)
        assertNull(id)
    }
}
