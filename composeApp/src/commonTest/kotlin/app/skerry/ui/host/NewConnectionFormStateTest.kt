package app.skerry.ui.host

import app.skerry.shared.host.Host
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
        assertFalse(f.canSave) // name/address/username blank
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

    @Test
    fun toDraft_carries_jump_host_and_fromHost_prefills_it() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; jumpHostId = "bastion-1" }
        assertEquals("bastion-1", f.toDraft().jumpHostId)
        assertNull(NewConnectionFormState().toDraft().jumpHostId)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", jumpHostId = "bastion-1")
        assertEquals("bastion-1", NewConnectionFormState.fromHost(host).jumpHostId)
    }

    @Test
    fun keep_alive_defaults_to_30_travels_the_draft_and_prefills_from_host() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertEquals(30, f.keepAliveSeconds)
        f.keepAliveSeconds = 0
        assertEquals(0, f.toDraft().keepAliveSeconds)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", keepAliveSeconds = 120)
        assertEquals(120, NewConnectionFormState.fromHost(host).keepAliveSeconds)
    }

    @Test
    fun agent_forwarding_defaults_to_off_travels_the_draft_and_prefills_from_host() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertFalse(f.forwardAgent)
        f.forwardAgent = true
        assertTrue(f.toDraft().forwardAgent)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", forwardAgent = true)
        assertTrue(NewConnectionFormState.fromHost(host).forwardAgent)
    }

    @Test
    fun switching_away_from_ssh_drops_agent_forwarding() {
        // Only an SSH session channel can carry the agent; Mosh runs its own protocol after the
        // bootstrap, so leaving the flag on would promise something the profile cannot do.
        val f = NewConnectionFormState().apply { forwardAgent = true }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.MOSH)
        assertFalse(f.forwardAgent)
    }

    @Test
    fun switching_away_from_ssh_drops_the_jump_host() {
        val f = NewConnectionFormState().apply { jumpHostId = "bastion-1" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.TELNET)
        assertNull(f.jumpHostId)
    }

    @Test
    fun mosh_requires_username_and_auth_like_ssh() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.MOSH)
        assertEquals("22", f.port) // SSH and Mosh share the default port (the SSH hop's)
        assertFalse(f.canSave) // username is required, same as SSH
        f.username = "root"
        assertTrue(f.canSave)
    }

    @Test
    fun switching_to_mosh_keeps_jump_host_and_resolves_credentials() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; jumpHostId = "bastion-1" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.MOSH)
        assertEquals("bastion-1", f.jumpHostId) // Mosh rides the SSH hop, the jump stays valid
        f.authMode = AuthMode.EXISTING
        f.existingCredentialId = "cred-1"
        assertEquals("cred-1", f.resolveCredentialId { null })
    }

    @Test
    fun vnc_defaults_to_port_5900_and_needs_no_username() {
        val f = NewConnectionFormState().apply { name = "desk"; address = "10.0.0.9" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals("5900", f.port) // RFB display :0
        // VNC has no username; the default ASK auth is enough to save.
        assertTrue(f.canSave)
        assertEquals(app.skerry.shared.ssh.ConnectionType.VNC, f.toDraft().connectionType)
        assertEquals(5900, f.toDraft().port)
    }

    @Test
    fun vnc_out_of_range_port_blocks_save() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        f.port = "70000"; assertFalse(f.canSave)
        f.port = "5901"; assertTrue(f.canSave)
    }

    @Test
    fun vnc_stores_a_password_credential_without_username() {
        val f = NewConnectionFormState().apply { name = "d"; address = "10.0.0.9" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        f.authMode = AuthMode.NEW_PASSWORD
        assertFalse(f.canSave) // password blank
        f.password = "sekret"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("cred-id", f.resolveCredentialId(cap.saveCredential))
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("sekret", cap.credentialDraft?.password)
    }

    @Test
    fun vnc_ask_auth_resolves_to_null() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.resolveCredentialId { error("ask must not save a credential") })
    }

    @Test
    fun switching_to_vnc_drops_key_auth_state() {
        // Started as SSH with a key: switching to VNC must not carry the key over — VNC auth is
        // password-only and a key credential would silently degrade to no auth at connect.
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.NEW_KEY
        f.privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----"
        f.passphrase = "pp"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertEquals("", f.privateKeyPem)
        assertEquals("", f.passphrase)
        assertNull(f.resolveCredentialId { error("no key credential may be created for VNC") })
    }

    @Test
    fun switching_to_vnc_drops_existing_credential_selection() {
        // The form can't tell a key secret from a password one by id, so the selection resets.
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.EXISTING
        f.existingCredentialId = "key-cred"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.existingCredentialId)
    }

    @Test
    fun switching_to_vnc_keeps_new_password_auth() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.NEW_PASSWORD
        f.password = "sekret"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.NEW_PASSWORD, f.authMode)
        assertEquals("sekret", f.password)
        assertTrue(f.canSave)
    }

    // Authentication

    private fun validBase() = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }

    // Capture helper: saveCredential returns the secret id and captures the draft.
    private class Captures {
        var credentialDraft: CredentialDraft? = null
        val saveCredential: (CredentialDraft) -> String? = { credentialDraft = it; "cred-id" }
    }

    @Test
    fun default_auth_is_ask_and_resolves_to_null_without_saving() {
        val f = validBase()
        assertEquals(AuthMode.ASK, f.authMode)
        assertTrue(f.canSave) // ASK does not require a secret
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertNull(id)
        assertNull(cap.credentialDraft) // secret not created
    }

    @Test
    fun existing_credential_requires_selection_and_resolves_to_its_id() {
        val f = validBase().apply { authMode = AuthMode.EXISTING }
        assertFalse(f.canSave) // nothing selected
        f.existingCredentialId = "saved-1"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("saved-1", f.resolveCredentialId(cap.saveCredential))
        assertNull(cap.credentialDraft) // existing credential is not recreated
    }

    @Test
    fun new_password_requires_value_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_PASSWORD; username = "root"; address = "10.0.0.1" }
        assertFalse(f.canSave) // password blank
        f.password = "s3cr3t"
        assertTrue(f.canSave)
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertEquals("cred-id", id) // returns the id of the created secret
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("s3cr3t", cap.credentialDraft?.password)
        assertEquals("root@10.0.0.1", cap.credentialDraft?.label)
        assertNull(cap.credentialDraft?.id) // creates a new one
    }

    @Test
    fun new_key_requires_pem_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_KEY; username = "ci"; address = "build.host" }
        assertFalse(f.canSave) // PEM blank
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
        val id = f.resolveCredentialId(saveCredential = { null }) // secret not saved (e.g. no vault)
        assertNull(id)
    }

    // Editing an existing host: fromHost prefills the form

    @Test
    fun fromHost_prefills_fields_and_round_trips_via_draft() {
        val host = Host(
            id = "h1", label = "prod-web-01", address = "10.0.0.5", port = 2222,
            username = "root", group = "Production", credentialId = "cred-9",
        )
        val f = NewConnectionFormState.fromHost(host)
        assertEquals("prod-web-01", f.name)
        assertEquals("10.0.0.5", f.address)
        assertEquals("2222", f.port)
        assertEquals("root", f.username)
        assertEquals("Production", f.group)
        // bound secret -> EXISTING mode with the same id; form is valid immediately
        assertEquals(AuthMode.EXISTING, f.authMode)
        assertEquals("cred-9", f.existingCredentialId)
        assertTrue(f.canSave)
        // Saving an edit keeps the host id and binding without recreating the secret.
        val credentialId = f.resolveCredentialId { error("existing credential must not be re-saved") }
        val draft = f.toDraft(id = host.id, credentialId = credentialId)
        assertEquals("h1", draft.id)
        assertEquals("cred-9", draft.credentialId)
        assertEquals("prod-web-01", draft.label)
    }

    @Test
    fun fromHost_without_credential_defaults_to_ask_and_blank_group() {
        val host = Host(id = "h2", label = "box", address = "a", port = 22, username = "u")
        val f = NewConnectionFormState.fromHost(host)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.existingCredentialId)
        assertEquals("", f.group)
        assertNull(f.resolveCredentialId { error("ask must not save a credential") })
    }

    // Tags (single-tag canonicalization is in app.skerry.shared.host.HostTagsTest)

    @Test
    fun addTag_normalizes_dedupes_and_keeps_order() {
        val f = NewConnectionFormState()
        f.addTag("#Prod")
        f.addTag("docker")
        f.addTag("PROD") // duplicate after normalization is ignored
        assertEquals(listOf("prod", "docker"), f.tags)
    }

    @Test
    fun addTag_splits_on_commas() {
        val f = NewConnectionFormState()
        f.addTag("prod, #docker ,, db")
        assertEquals(listOf("prod", "docker", "db"), f.tags)
    }

    @Test
    fun addTag_caps_total_count() {
        val f = NewConnectionFormState()
        f.addTag((1..50).joinToString(",") { "tag$it" })
        assertEquals(app.skerry.shared.tag.MAX_TAGS_PER_RECORD, f.tags.size)
    }

    @Test
    fun addTag_blank_is_noop() {
        val f = NewConnectionFormState()
        f.addTag("   ")
        f.addTag("#")
        assertEquals(emptyList(), f.tags)
    }

    @Test
    fun removeTag_drops_the_tag() {
        val f = NewConnectionFormState().apply { addTag("prod"); addTag("docker") }
        f.removeTag("prod")
        assertEquals(listOf("docker"), f.tags)
    }

    @Test
    fun toDraft_carries_tags() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; addTag("prod") }
        assertEquals(listOf("prod"), f.toDraft().tags)
        assertEquals(emptyList(), NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }.toDraft().tags)
    }

    @Test
    fun fromHost_restores_tags() {
        val host = Host(id = "h3", label = "box", address = "a", port = 22, username = "u", tags = listOf("prod", "db"))
        val f = NewConnectionFormState.fromHost(host)
        assertEquals(listOf("prod", "db"), f.tags)
    }

    @Test
    fun `keeps the host's agent key set through save and reopen`() {
        // The set is the host's, not the agent's: reopening the profile must show what was chosen,
        // and an untouched profile must keep meaning "every key in the agent".
        val form = NewConnectionFormState().apply {
            name = "bastion"
            address = "10.0.0.1"
            username = "ops"
            forwardAgent = true
            agentKeyIds = listOf("deploy")
        }

        val draft = form.toDraft(credentialId = null)
        assertEquals(listOf("deploy"), draft.agentKeyIds)

        val host = Host(
            id = "h1", label = draft.label, address = draft.address, username = draft.username,
            forwardAgent = draft.forwardAgent, agentKeyIds = draft.agentKeyIds,
        )
        assertEquals(listOf("deploy"), NewConnectionFormState.fromHost(host).agentKeyIds)
        assertEquals(emptyList(), NewConnectionFormState.fromHost(host.copy(agentKeyIds = emptyList())).agentKeyIds)
    }

    @Test
    fun `choosing a key for a host also puts it in the agent`() {
        // Otherwise the profile would name a key the agent does not hold, and forwarding would
        // quietly offer nothing — the user would have to guess that Settings needs a second visit.
        val form = NewConnectionFormState()
        val putInAgent = mutableListOf<String>()

        form.toggleAgentKey("deploy") { putInAgent += it }

        assertEquals(listOf("deploy"), form.agentKeyIds)
        assertEquals(listOf("deploy"), putInAgent)
    }

    @Test
    fun `unticking a key leaves it in the agent for everyone else`() {
        // Other hosts and the local socket may still be using it; this host just stops naming it.
        val form = NewConnectionFormState()
        val putInAgent = mutableListOf<String>()
        form.toggleAgentKey("deploy") { putInAgent += it }

        form.toggleAgentKey("deploy") { putInAgent += it }

        assertTrue(form.agentKeyIds.isEmpty())
        assertEquals(listOf("deploy"), putInAgent, "unticking must not touch the agent")
    }
}
